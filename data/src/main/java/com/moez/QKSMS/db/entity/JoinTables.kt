package dev.octoshrimpy.quik.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/** A conversation's recipients, in order (was Conversation.recipients, a Realm list). */
@Entity(primaryKeys = ["conversationId", "recipientId"])
data class ConversationRecipient(
    val conversationId: Long,
    @ColumnInfo(index = true) val recipientId: Long,
    val position: Int
)

/** A contact group's members (was ContactGroup.contacts, a Realm list). */
@Entity(primaryKeys = ["groupId", "lookupKey"])
data class ContactGroupMember(
    val groupId: Long,
    @ColumnInfo(index = true) val lookupKey: String
)
