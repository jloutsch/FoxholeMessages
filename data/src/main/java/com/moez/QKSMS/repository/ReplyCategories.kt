/*
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

/**
 * The inbox tab a message the user sent should carry: the category of the most recent *received*
 * message before it in the same conversation. A conversation's tab follows its latest message, so
 * this keeps replying from moving a conversation between tabs (see
 * MessageRepository.inheritReplyCategories). Kept apart from the database code for unit tests.
 */
internal object ReplyCategories {

    /**
     * For one conversation's messages, oldest first, the sent messages whose category should change,
     * paired with the category they should get (null if nothing has been received yet).
     */
    fun <T> updates(conversation: List<T>, isSent: (T) -> Boolean, category: (T) -> String?): List<Pair<T, String?>> {
        val updates = ArrayList<Pair<T, String?>>()
        var latestReceived: String? = null
        conversation.forEach { message ->
            if (!isSent(message)) {
                // A received message not categorized yet doesn't reset the conversation.
                category(message)?.let { latestReceived = it }
            } else if (category(message) != latestReceived) {
                updates += message to latestReceived
            }
        }
        return updates
    }
}
