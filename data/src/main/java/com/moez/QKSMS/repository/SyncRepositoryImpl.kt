/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import dev.octoshrimpy.quik.worker.ClassifyExistingMessagesWorker
import android.net.Uri
import android.provider.Telephony
import com.f2prateek.rx.preferences2.RxSharedPreferences
import dev.octoshrimpy.quik.extensions.forEach
import dev.octoshrimpy.quik.extensions.map
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.mapper.CursorToContact
import dev.octoshrimpy.quik.mapper.CursorToContactGroup
import dev.octoshrimpy.quik.mapper.CursorToContactGroupMember
import dev.octoshrimpy.quik.mapper.CursorToConversation
import dev.octoshrimpy.quik.mapper.CursorToMessage
import dev.octoshrimpy.quik.mapper.CursorToPart
import dev.octoshrimpy.quik.mapper.CursorToRecipient
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.PhoneNumber
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.SyncLog
import dev.octoshrimpy.quik.interactor.DeduplicateMessages
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.tryOrNull
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.reactivex.Completable
import io.reactivex.schedulers.Schedulers
import dev.octoshrimpy.quik.db.FoxholeDatabase
import dev.octoshrimpy.quik.db.Relations
import dev.octoshrimpy.quik.db.entity.ContactGroupMember
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val conversationRepo: ConversationRepository,
    private val cursorToConversation: CursorToConversation,
    private val cursorToMessage: CursorToMessage,
    private val cursorToPart: CursorToPart,
    private val cursorToRecipient: CursorToRecipient,
    private val cursorToContact: CursorToContact,
    private val cursorToContactGroup: CursorToContactGroup,
    private val cursorToContactGroupMember: CursorToContactGroupMember,
    private val keys: KeyManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val messageRepo: Provider<MessageRepository>,
    private val rxPrefs: RxSharedPreferences,
    // The implementation, not the interface: these call its database-level methods
    private val reactions: EmojiReactionRepositoryImpl,
    private val database: FoxholeDatabase,
    private val relations: Relations,
) : SyncRepository {

    override val syncProgress: Subject<SyncRepository.SyncProgress> =
            BehaviorSubject.createDefault(SyncRepository.SyncProgress.Idle)

    private val messageDao get() = database.messageDao()
    private val conversationDao get() = database.conversationDao()
    private val contactDao get() = database.contactDao()

    // Fire and forget: runs once in the background for the app's lifetime, nothing to cancel
    @Suppress("CheckResult")
    override fun syncMessages() {
        // If the sync is already running, don't try to do another one
        if (syncProgress.blockingFirst() is SyncRepository.SyncProgress.Running) return

        Completable.fromAction { syncMessagesNow() }
            .subscribeOn(Schedulers.io())
            .subscribe({}, { error -> Timber.e(error, "syncMessages Failed") })
    }

    /** The full sync, on the calling thread (not the main thread); throws if it fails. */
    @Suppress("CheckResult") // the dedupe below is fire and forget, like the sync itself
    private fun syncMessagesNow() {
        val oldBlockedSenders = rxPrefs.getStringSet("pref_key_blocked_senders")

        syncProgress.onNext(SyncRepository.SyncProgress.Running(0, 0, true))
        try {
            database.runInTransaction { rebuildFromProvider(oldBlockedSenders.get()) }
        } catch (e: Exception) {
            // The rebuild reset the id counter to 0, but its rollback brought the old rows back:
            // without this, later saves would reuse their ids and overwrite them
            keys.forget()
            throw e
        } finally {
            syncProgress.onNext(SyncRepository.SyncProgress.Idle)
        }
        oldBlockedSenders.delete()

        // Only once the sync has committed, so the dedupe's own writes see the new rows
        if (rxPrefs.getBoolean("autoDeduplicateMessages").get()) {
            DeduplicateMessages(messageRepo.get(), conversationRepo)
                .buildObservable(Unit)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { result ->
                    when (result) {
                        is MessageRepository.DeduplicationResult.NoDuplicates -> {
                            Timber.i("No duplicate messages found.")
                        }
                        is MessageRepository.DeduplicationResult.Success -> {
                            Timber.i("Moved ${result.trashedCount} duplicate messages to Trash")
                        }
                        is MessageRepository.DeduplicationResult.Failure -> {
                            Timber.e(result.error, "Deduplication failed")
                        }
                    }
                }
        }

        // A full sync imports messages that have no category yet (every message, on a
        // fresh install or after clearing data). Sort them now instead of leaving them all
        // in Personal until the user finds "Sort existing messages". Only unsorted messages
        // are touched, unless the sorting rules changed (see MessageCategoryBackfill).
        if (rxPrefs.getBoolean("fh_autoSortEnabled", true).get()) {
            ClassifyExistingMessagesWorker.trigger(context)
        }
    }

    /** Replaces the messages, conversations, recipients and contacts with the provider's. Call inside a transaction. */
    private fun rebuildFromProvider(oldBlockedSenders: Set<String>) {
        // What only this app knows about each conversation (archived, pinned, drafts, ...). The
        // rebuild re-creates every conversation from the system provider, which doesn't have them.
        val persistedConversations = conversationDao.all()
            .associate { conversation -> conversation.id to AppOnlyConversationFields.of(conversation) }
            .toMutableMap()

        // Message fields only this app keeps (Trash, stars, sorting, OTP tags), carried across by
        // provider identity or they're wiped by every full sync.
        val persistedMessages = messageDao.all()
            .filter { message ->
                message.contentId != 0L &&
                    (message.deletedAt != null || message.isStarred || message.category != null || message.isOtp)
            }
            .associate { message -> AppOnlyMessageFields.key(message) to AppOnlyMessageFields.of(message) }

        // Read before the old numbers are cleared: it keeps each number's "default" choice
        val contacts = getContacts()

        removeOldMessages()

        keys.reset()

        val partsCursor = cursorToPart.getPartsCursor()
        val messageCursor = cursorToMessage.getMessagesCursor()
        val conversationCursor = cursorToConversation.getConversationsCursor()
        val recipientCursor = cursorToRecipient.getRecipientCursor()

        val max = (partsCursor?.count ?: 0) +
                (messageCursor?.count ?: 0) +
                (conversationCursor?.count ?: 0) +
                (recipientCursor?.count ?: 0)

        var progress = 0

        // Sync message parts. They link to their message by MmsPart.messageId (the provider's id).
        partsCursor?.use {
            val batch = ArrayList<MmsPart>(BATCH)
            partsCursor.forEach { _ ->
                tryOrNull {
                    batch += cursorToPart.map(partsCursor)
                    progress++
                }
                if (batch.size == BATCH) { database.mmsPartDao().upsert(batch); batch.clear() }
            }
            database.mmsPartDao().upsert(batch)
        }

        // Sync messages
        messageCursor?.use {
            val messageColumns = CursorToMessage.MessageColumns(messageCursor)
            val batch = ArrayList<Message>(BATCH)
            messageCursor.forEach { cursor ->
                tryOrNull {
                    syncProgress.onNext(SyncRepository.SyncProgress.Running(max, ++progress, false))
                    batch += cursorToMessage.map(Pair(cursor, messageColumns)).apply {
                        persistedMessages[AppOnlyMessageFields.key(this)]?.applyTo(this)
                    }
                }
                if (batch.size == BATCH) { messageDao.upsert(batch); batch.clear() }
            }
            messageDao.upsert(batch)
        }

        // Migrate blocked conversations from 2.7.3
        oldBlockedSenders
            .map { threadIdString -> threadIdString.toLong() }
            .filter { threadId -> !persistedConversations.contains(threadId) }
            .forEach { threadId ->
                persistedConversations[threadId] = AppOnlyConversationFields.of(Conversation(id = threadId, blocked = true))
            }

        // Sync conversations
        conversationCursor?.use {
            conversationCursor.forEach { cursor ->
                tryOrNull {
                    syncProgress.onNext(SyncRepository.SyncProgress.Running(max, ++progress, false))
                    val conversation = cursorToConversation.map(cursor).apply {
                        persistedConversations[id]?.applyTo(this)
                        // Trashed messages are kept above, so skip them here: otherwise a
                        // conversation deleted to Trash would come back with a trashed snippet.
                        lastMessageId = messageDao.latestVisible(id)?.id
                    }
                    relations.saveConversation(conversation)
                }
            }
        }

        // Sync recipients
        saveContacts(contacts)
        recipientCursor?.use {
            recipientCursor.forEach { cursor ->
                tryOrNull {
                    syncProgress.onNext(SyncRepository.SyncProgress.Running(max, ++progress, false))
                    val rec = cursorToRecipient.map(cursor).apply {
                        contact = contacts.firstOrNull { c ->
                            c.numbers.any { num -> phoneNumberUtils.compare(address, num.address) }
                        }
                    }
                    relations.saveRecipients(listOf(rec))
                }
            }
        }

        syncProgress.onNext(SyncRepository.SyncProgress.ParsingEmojis(0, 0, true))

        // Now that we have all the messages, we can scan for emoji reactions
        reactions.deleteAndReparseAllEmojiReactions(onProgress = syncProgress::onNext)

        database.syncLogDao().insert(SyncLog())
    }

    override fun syncMessage(uri: Uri, messageId: Long): Message? {

        // If we don't have a valid type, return null
        val type = when {
            uri.toString().contains(Message.TYPE_MMS) -> Message.TYPE_MMS
            uri.toString().contains(Message.TYPE_SMS) -> Message.TYPE_SMS
            else -> return null
        }

        // If we don't have a valid id, return null
        val contentId = tryOrNull(false) { ContentUris.parseId(uri) } ?: return null

        // The uri might be something like content://mms/inbox/id
        // The box might change though, so we should just use the mms/id uri
        val stableUri = when (type) {
            Message.TYPE_MMS -> ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, contentId)
            else -> ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, contentId)
        }

        return contentResolver.query(stableUri, null, null, null, null)?.use { cursor ->

            // If there are no rows, return null. Otherwise, we've moved to the first row
            if (!cursor.moveToFirst()) return null

            val columnsMap = CursorToMessage.MessageColumns(cursor)
            cursorToMessage.map(Pair(cursor, columnsMap)).apply {
                if (isMms()) {
                    parts = cursorToPart.getPartsCursor(contentId)?.map { cursorToPart.map(it) }.orEmpty()
                }

                conversationRepo.getOrCreateConversation(threadId)
                database.runInTransaction {
                    // If the message is already saved, reuse its id and keep what only this app
                    // stores on it (Trash, star, category, code tag); the row is replaced wholesale.
                    // Reactions on it are rows keyed by its id, so reusing the id keeps them.
                    // Looked up inside the write: Android sends this sync for our own inserts too,
                    // so it runs alongside the receive path, and a lookup made earlier missed a row
                    // or tags saved in between (the message was then stored twice, or its tags were
                    // wiped). saveEmojiReaction below writes this object again, so the fields stay on it.
                    (messageDao.getByProviderId(type, contentId)
                        ?: messageDao.get(messageId)?.takeIf { it.contentId == 0L })
                        ?.let { current ->
                            this.id = current.id
                            AppOnlyMessageFields.of(current).applyTo(this)
                        }
                    relations.saveMessage(this)

                    reactions.parseEmojiReaction(getText(false))?.let { parsedReaction ->
                        val targetMessage = reactions.findTargetMessage(this, parsedReaction)
                        reactions.saveEmojiReaction(this, parsedReaction, targetMessage)
                    }
                }
            }
        }
    }

    override fun getLastSyncDate(): Long = database.syncLogDao().lastSyncDate() ?: 0

    override fun syncContacts() {
        // Load all the contacts
        val contacts = getContacts()

        database.runInTransaction {
            val recipients = database.recipientDao().all()

            contactDao.clear()
            contactDao.clearNumbers()
            contactDao.clearGroups()
            contactDao.clearGroupMembers()

            saveContacts(contacts)
            saveContactGroups(getContactGroups(contacts))

            // Update all the recipients with the new contacts
            recipients.forEach { recipient ->
                recipient.contactLookupKey = contacts.find { contact ->
                    contact.numbers.any { phoneNumberUtils.compare(recipient.address, it.address) }
                }?.lookupKey
            }

            database.recipientDao().upsert(recipients)
        }
    }

    private fun saveContacts(contacts: List<Contact>) {
        contactDao.upsert(contacts)
        contactDao.upsertNumbers(contacts.flatMap { contact -> contact.numbers })
    }

    private fun saveContactGroups(groups: List<ContactGroup>) {
        contactDao.upsertGroups(groups)
        contactDao.insertGroupMembers(groups.flatMap { group ->
            group.contacts.map { contact -> ContactGroupMember(group.id, contact.lookupKey) }
        })
    }

    private fun getContacts(): List<Contact> {
        val defaultNumberIds = contactDao.allNumbers().filter { number -> number.isDefault }.map { number -> number.id }

        return cursorToContact.getContactsCursor()
                ?.map { cursor -> cursorToContact.map(cursor) }
                ?.groupBy { contact -> contact.lookupKey }
                ?.map { contacts ->
                    // Sometimes, contacts providers on the phone will create duplicate phone number entries. This
                    // commonly happens with Whatsapp. Let's try to detect these duplicate entries and filter them out
                    val uniqueNumbers = mutableListOf<PhoneNumber>()
                    contacts.value
                            .flatMap { it.numbers }
                            .forEach { number ->
                                number.isDefault = defaultNumberIds.any { id -> id == number.id }
                                val duplicate = uniqueNumbers.find { other ->
                                    phoneNumberUtils.compare(number.address, other.address)
                                }

                                if (duplicate == null) {
                                    uniqueNumbers += number
                                } else if (!duplicate.isDefault && number.isDefault) {
                                    duplicate.isDefault = true
                                }
                            }

                    contacts.value.first().apply { numbers = uniqueNumbers }
                } ?: listOf()
    }

    private fun getContactGroups(contacts: List<Contact>): List<ContactGroup> {
        val groupMembers = cursorToContactGroupMember.getGroupMembersCursor()
                ?.map(cursorToContactGroupMember::map)
                .orEmpty()

        val groups = cursorToContactGroup.getContactGroupsCursor()
                ?.map(cursorToContactGroup::map)
                .orEmpty()

        groups.forEach { group ->
            group.contacts = groupMembers
                    .filter { member -> member.groupId == group.id }
                    .mapNotNull { member -> contacts.find { contact -> contact.lookupKey == member.lookupKey } }
        }

        return groups
    }

    private fun removeOldMessages() {
        contactDao.clear()
        contactDao.clearNumbers()
        contactDao.clearGroups()
        contactDao.clearGroupMembers()
        conversationDao.clear()
        conversationDao.clearAllRecipientLinks()
        messageDao.clear()
        database.mmsPartDao().clear()
        database.recipientDao().clear()
    }

    private companion object {
        const val BATCH = 500
    }

}

/**
 * Message fields that exist only in this app's database, never in the system SMS/MMS provider.
 * Syncing rebuilds Message rows from the provider, so anything here has to be read before the row is
 * replaced and written back onto the new one, or it is silently lost.
 */
internal class AppOnlyMessageFields(
    val deletedAt: Long?,
    val isStarred: Boolean,
    val category: String?,
    val isOtp: Boolean
) {
    fun applyTo(message: Message) {
        message.deletedAt = deletedAt
        message.isStarred = isStarred
        message.category = category
        message.isOtp = isOtp
    }

    companion object {
        fun of(message: Message) =
            AppOnlyMessageFields(message.deletedAt, message.isStarred, message.category, message.isOtp)

        /** Provider identity. Database ids are reassigned by a full sync, so they can't be the key. */
        fun key(message: Message) = "${message.type}:${message.contentId}"
    }
}

/**
 * Conversation fields only this app stores. A full sync rebuilds every conversation from the
 * provider, so these are read first and written back by conversation id (the provider's thread id).
 * Drafts and "send as group" were lost by every sync before this was kept for all conversations.
 */
internal class AppOnlyConversationFields(
    private val archived: Boolean,
    private val blocked: Boolean,
    private val pinned: Boolean,
    private val name: String,
    private val blockingClient: Int?,
    private val blockReason: String?,
    private val categoryOverride: String?,
    private val sendAsGroup: Boolean,
    private val draft: String,
    private val draftDate: Long
) {
    fun applyTo(conversation: Conversation) {
        conversation.archived = archived
        conversation.blocked = blocked
        conversation.pinned = pinned
        conversation.name = name
        conversation.blockingClient = blockingClient
        conversation.blockReason = blockReason
        conversation.categoryOverride = categoryOverride
        conversation.sendAsGroup = sendAsGroup
        conversation.draft = draft
        conversation.draftDate = draftDate
    }

    companion object {
        fun of(c: Conversation) = AppOnlyConversationFields(
            c.archived, c.blocked, c.pinned, c.name, c.blockingClient, c.blockReason,
            c.categoryOverride, c.sendAsGroup, c.draft, c.draftDate
        )
    }
}
