/*
 * Copyright (C) 2026 Foxhole Messages contributors
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
package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.db.FoxholeDatabase
import dev.octoshrimpy.quik.model.TrustedSender
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class TrustedSenderRepositoryImpl @Inject constructor(
    private val database: FoxholeDatabase,
    private val phoneNumberUtils: PhoneNumberUtils
) : TrustedSenderRepository {

    private val senders get() = database.trustedSenderDao()

    override fun getTrustedSenders(): Flow<List<TrustedSender>> = senders.observeAll()

    override fun isTrusted(address: String): Boolean =
        senders.all().any { phoneNumberUtils.compare(it.address, address) }

    override fun addTrustedSender(address: String, locked: Boolean) {
        database.runInTransaction {
            val alreadyTrusted = senders.all().any { phoneNumberUtils.compare(it.address, address) }
            if (alreadyTrusted) return@runInTransaction
            val nextId = (senders.maxId() ?: -1) + 1
            senders.insert(TrustedSender(nextId, address, locked))
        }
    }

    override fun removeTrustedSender(id: Long, force: Boolean) {
        database.runInTransaction {
            val sender = senders.get(id) ?: return@runInTransaction
            if (force || !sender.locked) senders.delete(id)
        }
    }

}
