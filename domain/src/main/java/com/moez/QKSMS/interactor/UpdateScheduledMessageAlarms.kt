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

import dev.octoshrimpy.quik.manager.AlarmManager
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import io.reactivex.Flowable
import javax.inject.Inject

class UpdateScheduledMessageAlarms @Inject constructor(
    private val alarmManager: AlarmManager,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val sendScheduledMessage: SendScheduledMessage
) : Interactor<Unit>() {

    override fun buildObservable(params: Unit): Flowable<*> {
        return Flowable.just(params)
                .map { scheduledMessageRepo.getScheduledMessages() } // Get all the scheduled messages
                .map { it.map { message -> Pair(message.id, message.date) } } // Map the data we need out of Realm
                .flatMap { messages -> Flowable.fromIterable(messages) } // Turn the list into a stream
                .filter { (id, date) ->
                    // Messages still to come get an alarm. Overdue ones (the phone was off at send time)
                    // are sent below instead. They used to get an alarm as well, and an alarm set in the
                    // past fires straight away, so after a reboot they were sent twice.
                    val overdue = date <= System.currentTimeMillis()
                    if (!overdue) alarmManager.setAlarm(date, alarmManager.getScheduledMessageIntent(id))
                    overdue
                }
                .flatMap { (id, _) -> sendScheduledMessage.buildObservable(id) } // Send them
    }

}