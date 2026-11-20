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

import io.realm.annotations.Ignore
import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey

open class Conversation(
    @PrimaryKey var id: Long = 0,
    @Index var archived: Boolean = false,
    @Index var blocked: Boolean = false,
    @Index var pinned: Boolean = false,
    var recipients: RealmList<Recipient> = RealmList(),
    var lastMessage: Message? = null,
    var draft: String = "",
    var draftDate: Long = 0,

    var blockingClient: Int? = null,
    var blockReason: String? = null,

    var name: String = "", // custom title

    var sendAsGroup: Boolean = true,

    // Message sorting: manual override of the classifier-derived category (see classifier/)
    var categoryOverride: String? = null,
) : RealmObject() {

    /**
     * The reaction emoji for [snippet], filled in on the copies screens receive: a copy can't query
     * Realm for it. Not stored.
     */
    @Ignore
    var lastReactionEmoji: String? = null

    val date: Long get() = lastMessage?.date ?: if (draft.isNotEmpty()) draftDate else 0
    val snippet: String? get() = lastMessage?.let { message ->
        val summary = message.getSummary()
        // A reaction's text is what the sender's phone wrote ("Loved “On my way!”"). Inside the
        // conversation it's shown as an emoji on the message instead, so lead with the emoji here
        // too, so the preview reads as a reaction rather than as something they typed. Skipped if
        // the text already contains it ("Reacted 🎉 to …").
        (lastReactionEmoji ?: reactionEmoji(message))?.takeUnless { it in summary }?.let { "$it $summary" } ?: summary
    }

    /** [lastMessage]'s reaction emoji, read from Realm; call on a managed conversation. */
    fun lastMessageReactionEmoji(): String? = lastMessage?.let(::reactionEmoji)

    /** The emoji of a recognised reaction message, or null (not a reaction, a removal, or unmanaged). */
    private fun reactionEmoji(message: Message): String? {
        if (!message.isEmojiReaction || !message.isManaged || !message.isValid) return null
        return message.realm.where(EmojiReaction::class.java)
            .equalTo("reactionMessageId", message.id)
            .findFirst()
            ?.emoji
    }
    val unread: Boolean get() = lastMessage?.read == false
    val me: Boolean get() = lastMessage?.isMe() == true

    fun getTitle(): String {
        return name.takeIf { it.isNotBlank() } ?: recipients.joinToString { recipient -> recipient.getDisplayName() }
    }

}
