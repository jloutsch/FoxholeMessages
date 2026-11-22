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
package dev.octoshrimpy.quik.model

import androidx.room.Ignore
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity
open class Conversation(
    @PrimaryKey var id: Long = 0,
    @ColumnInfo(index = true) var archived: Boolean = false,
    @ColumnInfo(index = true) var blocked: Boolean = false,
    @ColumnInfo(index = true) var pinned: Boolean = false,
    // The newest message not in Trash (was a Realm link); see [lastMessage]
    var lastMessageId: Long? = null,
    var draft: String = "",
    var draftDate: Long = 0,

    var blockingClient: Int? = null,
    var blockReason: String? = null,

    var name: String = "", // custom title

    var sendAsGroup: Boolean = true,

    // Message sorting: manual override of the classifier-derived category (see classifier/)
    @ColumnInfo(index = true) var categoryOverride: String? = null,
) {

    /** Filled in by the repository from ConversationRecipient rows; not stored here. */
    @Ignore
    var recipients: List<Recipient> = emptyList()

    /** Filled in by the repository from [lastMessageId]; not stored here. */
    @Ignore
    var lastMessage: Message? = null


    /**
     * The reaction emoji for [snippet], filled in by the repository when [lastMessage] is a
     * reaction. Not stored.
     */
    @Ignore
    var lastReactionEmoji: String? = null

    /** Unread messages outside Trash, filled in by the repository when [unread]; not stored. */
    @Ignore
    var unreadCount: Long = 0

    val date: Long get() = lastMessage?.date ?: if (draft.isNotEmpty()) draftDate else 0
    val snippet: String? get() = lastMessage?.summaryWithReaction(lastReactionEmoji)


    val unread: Boolean get() = lastMessage?.read == false
    val me: Boolean get() = lastMessage?.isMe() == true

    fun getTitle(): String {
        return name.takeIf { it.isNotBlank() } ?: recipients.joinToString { recipient -> recipient.getDisplayName() }
    }

}
