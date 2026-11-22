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

import android.content.Context
import dev.octoshrimpy.quik.common.util.extensions.now
import dev.octoshrimpy.quik.compat.TelephonyCompat
import dev.octoshrimpy.quik.db.FoxholeDatabase
import dev.octoshrimpy.quik.db.Relations
import dev.octoshrimpy.quik.db.Tables
import dev.octoshrimpy.quik.db.observe
import dev.octoshrimpy.quik.extensions.map
import dev.octoshrimpy.quik.filter.ConversationFilter
import dev.octoshrimpy.quik.mapper.CursorToConversation
import dev.octoshrimpy.quik.mapper.CursorToRecipient
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.SearchResult
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.tryOrNull
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ConversationRepositoryImpl @Inject constructor(
    private val context: Context,
    private val conversationFilter: ConversationFilter,
    private val cursorToConversation: CursorToConversation,
    private val cursorToRecipient: CursorToRecipient,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val database: FoxholeDatabase,
    private val relations: Relations
) : ConversationRepository {

    private val conversationDao get() = database.conversationDao()
    private val messageDao get() = database.messageDao()

    /** Live conversations with their relations, refreshed when any of them changes. */
    private fun observeConversations(query: () -> List<Conversation>): Flow<List<Conversation>> =
        database.observe(Tables.CONVERSATIONS) { relations.conversations(query()) }

    override fun getConversations(unreadAtTop: Boolean, archived: Boolean): Flow<List<Conversation>> =
        observeConversations { conversationDao.listed(archived, unreadAtTop) }

    override fun getConversationsSnapshot(unreadAtTop: Boolean): List<Conversation> =
        relations.conversations(conversationDao.listed(false, unreadAtTop))

    override fun getTopConversations(): List<Conversation> {
        val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val conversations = relations.conversations(conversationDao.top(weekAgo))
        // Counted once each up front: a count inside the comparator re-ran the query on every comparison
        val recentCounts = conversations.associate { it.id to messageDao.countSince(it.id, weekAgo) }
        return conversations.sortedWith(compareByDescending<Conversation> { it.pinned }
            .thenByDescending { recentCounts[it.id] })
    }

    override fun setConversationName(id: Long, name: String) =
        editConversations(longArrayOf(id)) { this.name = name }

    override fun searchConversations(query: CharSequence): List<SearchResult> {
        val searchQuery = query.toString()
        val conversations = relations.conversations(conversationDao.searchable())
        val byId = conversations.associateBy { it.id }

        // search shouldn't surface messages that are in Trash (the query already excludes them)
        val messagesByConversation = messageDao.search(searchQuery)
            .groupBy { message -> message.threadId }
            .mapNotNull { (threadId, messages) -> byId[threadId]?.let { it to messages.size } }
            .map { (conversation, messages) -> SearchResult(searchQuery, conversation, messages) }
            .sortedByDescending { result -> result.messages }

        return conversations
            .filter { conversation -> conversationFilter.filter(conversation, searchQuery) }
            .map { conversation -> SearchResult(searchQuery, conversation, 0) } + messagesByConversation
    }

    override fun getBlockedConversations(): List<Conversation> = relations.conversations(conversationDao.blocked())

    override fun observeBlockedConversations(): Flow<List<Conversation>> = observeConversations { conversationDao.blocked() }

    override fun observeConversation(threadId: Long): Flow<Conversation?> =
        database.observe(Tables.CONVERSATIONS) { relations.conversation(conversationDao.get(threadId)) }

    override fun getConversation(threadId: Long): Conversation? =
        tryOrNull(true) { relations.conversation(conversationDao.get(threadId)) }

    override fun updateSendAsGroup(threadId: Long, sendAsGroup: Boolean): Unit? =
        conversationDao.get(threadId)?.let { editConversations(longArrayOf(threadId)) { this.sendAsGroup = sendAsGroup } }

    // A synchronous query: an async one was empty when read straight away, so the "speak unseen"
    // widget always said there were no messages
    override fun getUnseenIds(archived: Boolean): List<Long> = conversationDao.unseenIds(archived)

    override fun getConversationAndLastSenderContactName(threadId: Long): Pair<Conversation?, String?>? =
        getConversation(threadId)?.let { conversation ->
            // A draft-only conversation has no last message
            val lastAddress = conversation.lastMessage?.address
            val lastSenderName = lastAddress?.let { address ->
                conversation.recipients.find { recipient -> phoneNumberUtils.compare(recipient.address, address) }?.contact?.name
            }
            Pair(conversation, lastSenderName)
        }

    override fun getConversations(vararg threadIds: Long): List<Conversation> =
        relations.conversations(threadIds.toList().chunked(900).flatMap(conversationDao::get))

    override fun getUnmanagedConversations(): Flow<List<Conversation>> =
        observeConversations { conversationDao.recent() }

    override fun getRecipients(): List<Recipient> = relations.recipients(database.recipientDao().all())

    override fun getUnmanagedRecipients(): Flow<List<Recipient>> =
        database.observe(Tables.RECIPIENTS) { relations.recipients(database.recipientDao().withContact()) }

    override fun getRecipient(recipientId: Long): Recipient? =
        database.recipientDao().get(recipientId)?.let { relations.recipients(listOf(it)).first() }

    private fun createConversation(threadId: Long, sendAsGroup: Boolean) =
        createConversationFromCp(threadId, sendAsGroup)

    override fun getConversation(addresses: Collection<String>): Conversation? =
        relations.conversations(conversationDao.all())
            .filter { conversation -> conversation.recipients.size == addresses.size }
            .find { conversation ->
                conversation.recipients.map { it.address }.all { recipientAddress ->
                    addresses.any { phoneNumberUtils.compare(it, recipientAddress) }
                }
            }

    private fun createConversation(addresses: Collection<String>, sendAsGroup: Boolean) =
        TelephonyCompat.getOrCreateThreadId(context, addresses.toSet())
            .takeIf { it != 0L }
            ?.let { providerThreadId ->
                createConversationFromCp(providerThreadId, sendAsGroup) ?:
                    createEmptyConversation(providerThreadId, addresses, sendAsGroup)
            }

    override fun getOrCreateConversation(threadId: Long, sendAsGroup: Boolean) =
        getConversation(threadId) ?: createConversation(threadId, sendAsGroup)

    override fun getOrCreateConversation(addresses: Collection<String>, sendAsGroup: Boolean) =
        getConversation(addresses) ?: createConversation(addresses, sendAsGroup)

    override fun saveDraft(threadId: Long, draft: String) =
        editConversations(longArrayOf(threadId)) {
            this.draft = draft
            this.draftDate = System.currentTimeMillis()
        }

    override fun updateConversations(threadIds: Collection<Long>) {
        // Each conversation's last message is its newest message that isn't in Trash
        database.runInTransaction {
            val conversations = threadIds.toList().chunked(900).flatMap(conversationDao::get)
            conversations.forEach { conversation -> conversation.lastMessageId = messageDao.latestVisible(conversation.id)?.id }
            conversationDao.update(conversations)
        }
    }

    override fun markArchived(vararg threadIds: Long) = editConversations(threadIds) { archived = true }

    override fun markUnarchived(threadIds: Collection<Long>) =
        editConversations(threadIds.toLongArray()) { archived = false }

    override fun markPinned(vararg threadIds: Long) = editConversations(threadIds) { pinned = true }

    override fun markUnpinned(vararg threadIds: Long) = editConversations(threadIds) { pinned = false }

    /** Applies [change] to each of the conversations [threadIds] in one write. */
    private fun editConversations(threadIds: LongArray, change: Conversation.() -> Unit) {
        database.runInTransaction {
            val conversations = threadIds.toList().chunked(900).flatMap(conversationDao::get)
            conversations.forEach(change)
            conversationDao.update(conversations)
        }
    }

    override fun markBlocked(threadIds: Collection<Long>, blockingClient: Int, blockReason: String?) =
        editConversations(threadIds.toLongArray()) {
            if (!blocked) {
                blocked = true
                this.blockingClient = blockingClient
                this.blockReason = blockReason
            }
        }

    override fun markUnblocked(vararg threadIds: Long) = editConversations(threadIds) {
        blocked = false
        blockingClient = null
        blockReason = null
    }

    // Trashes (soft-deletes) the conversation's messages rather than removing them outright, so a
    // deleted conversation is recoverable from Trash within the retention window (see PurgeTrash).
    // The conversation row is kept with its last message and draft cleared, which is enough for
    // the inbox query (last message or draft required) to hide it; restoring any of its messages
    // brings it back through updateConversations() recomputing the last message.
    override fun deleteConversations(vararg threadIds: Long) {
        database.runInTransaction {
            threadIds.toList().chunked(900).forEach { chunk -> messageDao.trashThreads(chunk, now()) }
            editConversations(threadIds) {
                lastMessageId = null
                draft = ""
                draftDate = 0
            }
        }
    }

    override fun getConversationsByCategory(unreadAtTop: Boolean, category: String): Flow<List<Conversation>> =
        observeConversations { conversationDao.inCategory(category, unreadAtTop) }

    override fun getUnreadCountByCategory(category: String): Long = conversationDao.unreadCountInCategory(category)

    override fun updateCategoryOverride(threadIds: Collection<Long>, category: String?) =
        editConversations(threadIds.toLongArray()) { categoryOverride = category }

    /**
     * Returns a [Conversation] from the system SMS ContentProvider, based on the [threadId]
     *
     * It should be noted that even if we have a valid [threadId], that does not guarantee that
     * we can return a [Conversation]. On some devices, the ContentProvider won't return the
     * conversation unless it contains at least 1 message
     */
    private fun createConversationFromCp(threadId: Long, sendAsGroup: Boolean) =
        tryOrNull(true) {
            cursorToConversation.getConversationsCursor()
                ?.map(cursorToConversation::map)
                ?.firstOrNull { conversation -> conversation.id == threadId }
                ?.also { conversation ->
                    val contacts = relations.contacts(database.contactDao().all())

                    // match recipients from provider to the saved contacts
                    val matchedRecipients = conversation.recipients
                        .mapNotNull { recipient ->
                            cursorToRecipient.getRecipientCursor(recipient.id)?.use { cursor ->
                                cursor.map { cursorToRecipient.map(it) }
                            }
                        }
                        .flatten()
                        .map { recipient ->
                            recipient.apply {
                                contact = contacts.firstOrNull { contact ->
                                    contact.numbers.any { phoneNumberUtils.compare(it.address, address) }
                                }
                            }
                        }

                    conversation.apply {
                        recipients = matchedRecipients
                        this.sendAsGroup = if (recipients.size <= 1) false else sendAsGroup
                        lastMessage = messageDao.latestVisible(threadId)
                    }

                    database.runInTransaction { relations.saveConversation(conversation) }
                }
        }

    /**
     * In some cases [createConversationFromCp] will return null if there are no messages present in
     * the convo. To allow the conversation to be accessed, create an empty one here to match the
     * conversation created in the content provider. A workaround needed on HyperOS and variants.
     */
    private fun createEmptyConversation(threadId: Long, addresses: Collection<String>, sendAsGroup: Boolean): Conversation {
        val contacts = relations.contacts(database.contactDao().all())
        val ids = relations.newLocalRecipientIds(addresses.size)
        val matchedRecipients = addresses.zip(ids) { address, recipientId ->
            Recipient().apply {
                id = recipientId
                this.address = address
                contact = contacts.firstOrNull { contact ->
                    contact.numbers.any { phoneNumberUtils.compare(it.address, address) }
                }
            }
        }
        val conversation = Conversation().apply {
            id = threadId
            recipients = matchedRecipients
            this.sendAsGroup = if (recipients.size <= 1) false else sendAsGroup
        }
        database.runInTransaction { relations.saveConversation(conversation) }
        return conversation
    }

}
