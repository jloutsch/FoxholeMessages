package dev.octoshrimpy.quik.db

import dev.octoshrimpy.quik.db.entity.ConversationRecipient
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.Recipient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Realm stored relations inside objects (a message's parts, a conversation's recipients); Room
 * stores them as columns and join rows. This fills the models' @Ignore relation fields after a
 * query, and saves a model together with its relations.
 */
@Singleton
class Relations @Inject constructor(private val database: FoxholeDatabase) {

    companion object {
        // Older Android SQLite allows at most 999 values in one query
        private const val CHUNK = 900
    }

    private val messageDao get() = database.messageDao()
    private val partDao get() = database.mmsPartDao()
    private val reactionDao get() = database.emojiReactionDao()
    private val conversationDao get() = database.conversationDao()
    private val recipientDao get() = database.recipientDao()
    private val contactDao get() = database.contactDao()

    /** Fills each message's parts (MMS) and the reactions on it. */
    fun messages(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val contentIds = messages.filter { it.isMms() }.map { it.contentId }.distinct()
        val parts = contentIds.chunked(CHUNK).flatMap(partDao::forMessages).groupBy { it.messageId }
        val reactions = messages.map { it.id }.distinct().chunked(CHUNK)
            .flatMap(reactionDao::forTargets).groupBy { it.targetMessageId }
        messages.forEach { message ->
            if (message.isMms()) message.parts = parts[message.contentId].orEmpty()
            message.emojiReactions = reactions[message.id].orEmpty()
        }
        return messages
    }

    /** [messages] for one thread's messages, with one query each for parts and reactions. */
    fun threadMessages(threadId: Long, messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val parts = partDao.forThread(threadId).groupBy { it.messageId }
        val reactions = reactionDao.forThread(threadId).groupBy { it.targetMessageId }
        messages.forEach { message ->
            if (message.isMms()) message.parts = parts[message.contentId].orEmpty()
            message.emojiReactions = reactions[message.id].orEmpty()
        }
        return messages
    }

    fun message(message: Message?): Message? = message?.also { messages(listOf(it)) }

    /** Fills each contact's phone numbers. */
    fun contacts(contacts: List<Contact>): List<Contact> {
        if (contacts.isEmpty()) return contacts
        val numbers = contacts.map { it.lookupKey }.distinct().chunked(CHUNK)
            .flatMap(contactDao::numbersFor).groupBy { it.contactLookupKey }
        contacts.forEach { contact -> contact.numbers = numbers[contact.lookupKey].orEmpty() }
        return contacts
    }

    /** Fills each group's contacts. */
    fun contactGroups(groups: List<ContactGroup>): List<ContactGroup> {
        if (groups.isEmpty()) return groups
        val members = groups.map { it.id }.chunked(CHUNK).flatMap(contactDao::groupMembers)
        val contacts = contacts(members.map { it.lookupKey }.distinct().chunked(CHUNK).flatMap(contactDao::get))
            .associateBy { it.lookupKey }
        val membersByGroup = members.groupBy { it.groupId }
        groups.forEach { group ->
            group.contacts = membersByGroup[group.id].orEmpty().mapNotNull { member -> contacts[member.lookupKey] }
        }
        return groups
    }

    /** Fills each recipient's matched contact (with its numbers). */
    fun recipients(recipients: List<Recipient>): List<Recipient> {
        val keys = recipients.mapNotNull { it.contactLookupKey }.distinct()
        if (keys.isEmpty()) return recipients
        val contacts = contacts(keys.chunked(CHUNK).flatMap(contactDao::get)).associateBy { it.lookupKey }
        recipients.forEach { recipient -> recipient.contact = recipient.contactLookupKey?.let(contacts::get) }
        return recipients
    }

    /** Fills each conversation's recipients, last message, and the reaction emoji for its preview. */
    fun conversations(conversations: List<Conversation>): List<Conversation> {
        if (conversations.isEmpty()) return conversations
        val links = conversations.map { it.id }.distinct().chunked(CHUNK).flatMap(conversationDao::recipientLinks)
        val recipients = recipients(links.map { it.recipientId }.distinct().chunked(CHUNK).flatMap(recipientDao::get))
            .associateBy { it.id }
        val linksByConversation = links.groupBy { it.conversationId }

        val lastMessages = messages(conversations.mapNotNull { it.lastMessageId }.distinct()
            .chunked(CHUNK).flatMap(messageDao::get)).associateBy { it.id }
        val reactionEmoji = lastMessages.values.filter { it.isEmojiReaction }.map { it.id }.chunked(CHUNK)
            .flatMap(reactionDao::forReactionMessages).associate { it.reactionMessageId to it.emoji }

        conversations.forEach { conversation ->
            conversation.recipients = linksByConversation[conversation.id].orEmpty()
                .sortedBy { it.position }
                .mapNotNull { link -> recipients[link.recipientId] }
            conversation.lastMessage = conversation.lastMessageId?.let(lastMessages::get)
            conversation.lastReactionEmoji = conversation.lastMessageId?.let(reactionEmoji::get)
        }
        return conversations
    }

    fun conversation(conversation: Conversation?): Conversation? = conversation?.also { conversations(listOf(it)) }

    /** Saves a message and, for MMS, replaces its parts. Call inside a transaction. */
    fun saveMessage(message: Message) {
        messageDao.upsert(message)
        if (message.isMms() && message.parts.isNotEmpty()) {
            partDao.deleteForMessages(listOf(message.contentId))
            partDao.upsert(message.parts.onEach { part -> part.messageId = message.contentId })
        }
    }

    /**
     * Saves a message read from the phone's store, reusing the row already saved for it (same
     * type and store id) so it isn't stored twice. The provider-changed sync can save a message
     * the app just sent before the send path does (#82).
     */
    fun saveProviderMessage(message: Message) = database.runInTransaction {
        if (message.contentId != 0L) {
            messageDao.getByProviderId(message.type, message.contentId)?.let { saved -> message.id = saved.id }
        }
        saveMessage(message)
    }

    /** Saves recipients, recording which contact each one matched. Call inside a transaction. */
    fun saveRecipients(recipients: List<Recipient>) {
        recipients.forEach { recipient -> recipient.contact?.let { recipient.contactLookupKey = it.lookupKey } }
        recipientDao.upsert(recipients)
    }

    /** Saves a conversation with its recipients (in order) and last message link. Call inside a transaction. */
    fun saveConversation(conversation: Conversation) {
        conversation.lastMessage?.let { conversation.lastMessageId = it.id }
        conversationDao.upsert(conversation)
        saveRecipients(conversation.recipients)
        conversationDao.clearRecipientLinks(conversation.id)
        conversationDao.insertRecipientLinks(conversation.recipients.mapIndexed { index, recipient ->
            ConversationRecipient(conversation.id, recipient.id, index)
        })
    }
}
