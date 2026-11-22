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
package dev.octoshrimpy.quik.interactor

import com.moez.QKSMS.manager.SpeakManager
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import javax.inject.Inject

class SpeakThreads @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val speakManager: SpeakManager
) : Interactor<List<Long>>() {

    companion object {
        private var noMessagesStr = "No messages"       // default value

        fun setNoMessagesString(newNoMessagesString: String) {
            noMessagesStr = newNoMessagesString
        }
    }

    override suspend fun perform(params: List<Long>) {
        if (params.isEmpty()) {
            speakManager.startSpeakSession(noMessagesStr)
            try {
                speakManager.speak(noMessagesStr)
            } finally {
                speakManager.endSpeakSession()
            }
            return
        }

        speakManager.startSpeakSession("threads:" + params.sorted().joinToString())
        try {
            params.forEach { threadId ->
                val (conversation, sender) = conversationRepo.getConversationAndLastSenderContactName(threadId)
                    ?: return@forEach
                if (speakManager.speakConversationLastSms(Pair(conversation, sender)) && conversation != null)
                    messageRepo.markSeen(listOf(conversation.id))
            }
        } finally {
            speakManager.endSpeakSession()
        }
    }

}