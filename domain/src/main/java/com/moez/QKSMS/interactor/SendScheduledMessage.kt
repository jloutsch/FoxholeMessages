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

import android.content.Context
import android.net.Uri
import dev.octoshrimpy.quik.compat.TelephonyCompat
import dev.octoshrimpy.quik.model.Attachment
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import java.util.Collections
import javax.inject.Inject

class SendScheduledMessage @Inject constructor(
    private val context: Context,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val deleteScheduledMessagesInteractor: DeleteScheduledMessages,
    private val sendNewMessage: SendNewMessage
) : Interactor<Long>() {

    companion object {
        // Scheduled messages being sent right now. The alarm, the boot-time catch-up and "Send now"
        // can all ask for the same message; the scheduled copy is only deleted once sending is done,
        // so without this a second request in that window sent it again.
        private val sending: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    }

    override suspend fun perform(params: Long) {
        if (!sending.add(params)) return

        try {
            val message = scheduledMessageRepo.getScheduledMessage(params) ?: return
            val messages = if (message.sendAsGroup) {
                listOf(message)
            } else {
                message.recipients.map { recipient -> message.copy(recipients = listOf(recipient)) }
            }

            messages.forEach { each ->
                val threadId = TelephonyCompat.getOrCreateThreadId(context, each.recipients)
                val attachments = each.attachments.mapNotNull(Uri::parse).map { Attachment(context, it) }
                val sent = sendNewMessage.perform(SendNewMessage.Params(
                    each.subId, threadId, each.recipients, each.body, each.sendAsGroup, attachments
                ))
                if (sent) deleteScheduledMessagesInteractor.execute(listOf(params))
            }
        } finally {
            sending.remove(params)
        }
    }

}
