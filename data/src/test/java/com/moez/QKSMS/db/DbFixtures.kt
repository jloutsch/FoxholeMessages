package dev.octoshrimpy.quik.db

import dev.octoshrimpy.quik.db.entity.ConversationRecipient
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.Recipient

fun message(id: Long, threadId: Long = 1, date: Long = id, body: String = "", read: Boolean = true,
            deletedAt: Long? = null, type: String = "sms", contentId: Long = id) = Message().apply {
    this.id = id; this.threadId = threadId; this.date = date; this.body = body; this.read = read
    this.deletedAt = deletedAt; this.type = type; this.contentId = contentId
}

/** Saves a conversation with one recipient, pointing at [lastMessageId]. */
fun FoxholeDatabase.addConversation(id: Long, lastMessageId: Long? = null, pinned: Boolean = false,
                                    archived: Boolean = false, blocked: Boolean = false, draft: String = "",
                                    draftDate: Long = 0, recipients: Boolean = true) {
    conversationDao().upsert(Conversation(id = id, lastMessageId = lastMessageId, pinned = pinned,
        archived = archived, blocked = blocked, draft = draft, draftDate = draftDate))
    if (recipients) {
        recipientDao().upsert(listOf(Recipient(id = id * 100, address = "555$id")))
        conversationDao().insertRecipientLinks(listOf(ConversationRecipient(id, id * 100, 0)))
    }
}

fun part(id: Long, contentId: Long, type: String, text: String? = null) =
    MmsPart().apply { this.id = id; messageId = contentId; this.type = type; this.text = text }
