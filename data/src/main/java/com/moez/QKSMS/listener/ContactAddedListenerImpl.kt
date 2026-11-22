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
package dev.octoshrimpy.quik.listener

import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.ContactsContract
import javax.inject.Inject
import android.os.Looper

/**
 * Listens for a contact being added, and then syncs it to Realm
 */
class ContactAddedListenerImpl @Inject constructor(
    private val context: Context
) : ContactAddedListener {

    companion object {
        private val URI = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    }

    override fun listen(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                this.onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        trySend(Unit) // what the BehaviorSubject's default value gave
        context.contentResolver.registerContentObserver(URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

}
