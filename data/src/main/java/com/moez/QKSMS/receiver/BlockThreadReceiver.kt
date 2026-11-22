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
package dev.octoshrimpy.quik.receiver

import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.interactor.MarkBlocked
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import javax.inject.Inject

class BlockThreadReceiver : BroadcastReceiver() {

    @Inject lateinit var blockingClient: BlockingClient
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var markBlocked: MarkBlocked
    @Inject lateinit var prefs: Preferences

    @SuppressLint("CheckResult")
    override fun onReceive(context: Context, intent: Intent) {
        AndroidInjection.inject(this, context)

        Timber.v("received")

        // goAsync() must be called before onReceive returns; calling it later from the async
        // callback returns an already-finished result (or null), which crashes the process.
        val pendingResult = goAsync()
        val threadId = intent.getLongExtra("threadId", 0)

        @OptIn(DelicateCoroutinesApi::class) // a receiver has no scope; goAsync() keeps it alive until finish()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val addresses = conversationRepo.getConversation(threadId)?.recipients?.map { it.address } ?: listOf()
                blockingClient.block(addresses)
                markBlocked.perform(MarkBlocked.Params(listOf(threadId), prefs.blockingManager.get(), null))
            } catch (e: Exception) {
                Timber.e(e, "blocking failed")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
