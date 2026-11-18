/*
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.classifier

import dev.octoshrimpy.quik.model.Conversation

/**
 * Which inbox tab a conversation sits under, as a category name (PERSONAL / TRANSACTIONAL /
 * PROMOTIONAL). Mirrors the inbox query (ConversationRepositoryImpl.getConversationsByCategoryBase):
 * a "Move to" override wins, otherwise the last message's category, and anything that isn't
 * transactional or promotional is personal. Notifications and the home-screen widget both use
 * this, so neither can disagree with the tab the conversation is actually shown under.
 */
object InboxTab {

    /** The three conversation tabs, in display order (Starred lists messages, not conversations). */
    val ALL = listOf(Category.PERSONAL.name, Category.TRANSACTIONAL.name, Category.PROMOTIONAL.name)

    fun of(categoryOverride: String?, lastMessageCategory: String?): String {
        categoryOverride?.takeIf { it.isNotEmpty() }?.let { return it }
        return lastMessageCategory
            ?.takeIf { it == Category.TRANSACTIONAL.name || it == Category.PROMOTIONAL.name }
            ?: Category.PERSONAL.name
    }

    fun of(conversation: Conversation): String = of(conversation.categoryOverride, conversation.lastMessage?.category)
}
