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
package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.db.FoxholeDatabase
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.MessageContentFilterData
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class BlockingRepositoryImpl @Inject constructor(
    private val database: FoxholeDatabase,
    private val phoneNumberUtils: PhoneNumberUtils
) : BlockingRepository {

    private val numbers get() = database.blockedNumberDao()
    private val filters get() = database.messageContentFilterDao()

    override fun blockNumber(vararg addresses: String) {
        // Existing numbers and the next id are read inside the transaction, so a concurrent insert
        // (e.g. the notification Block action) can't take the same primary key in between.
        database.runInTransaction {
            val blockedNumbers = numbers.all()
            val newAddresses = addresses.filter { address ->
                blockedNumbers.none { number -> phoneNumberUtils.compare(number.address, address) }
            }
            val maxId = numbers.maxId() ?: -1
            numbers.insert(newAddresses.mapIndexed { index, address -> BlockedNumber(maxId + 1 + index, address) })
        }
    }

    override fun getBlockedNumbers(): Flow<List<BlockedNumber>> = numbers.observeAll()

    override fun exportBlockedAddresses(): List<String> = numbers.all().map { it.address }

    override fun importBlockingRules(
        addresses: List<String>,
        filters: List<MessageContentFilterData>
    ): Pair<Int, Int> {
        var result = Pair(0, 0)
        // One transaction for numbers and filters, so a failure part-way leaves nothing behind
        database.runInTransaction {
            val existingNumbers = numbers.all().map { it.address }
            // Fuzzy-compare against the saved list (usually short); within the file, dedupe on the
            // normalized form only, so a large file doesn't cost a full pairwise phone-number match.
            val seenInFile = HashSet<String>()
            val newAddresses = addresses.filter { address ->
                seenInFile.add(phoneNumberUtils.normalizeNumber(address).lowercase()) &&
                        existingNumbers.none { phoneNumberUtils.compare(it, address) }
            }
            val maxNumberId = numbers.maxId() ?: -1
            numbers.insert(newAddresses.mapIndexed { index, address -> BlockedNumber(maxNumberId + 1 + index, address) })

            val existingFilters = this.filters.all()
                .map { MessageContentFilterData(it.value, it.caseSensitive, it.isRegex, it.includeContacts) }
                .toSet()
            val newFilters = filters.distinct().filter { it !in existingFilters }
            val maxFilterId = this.filters.maxId() ?: -1
            this.filters.insert(newFilters.mapIndexed { index, filter ->
                MessageContentFilter(maxFilterId + 1 + index, filter.value, filter.caseSensitive, filter.isRegex, filter.includeContacts)
            })

            result = Pair(newAddresses.size, newFilters.size)
        }
        return result
    }

    override fun getBlockedNumber(id: Long): BlockedNumber? = numbers.get(id)

    override fun isBlocked(address: String): Boolean =
        numbers.all().any { number -> phoneNumberUtils.compare(number.address, address) }

    override fun unblockNumber(id: Long) = numbers.delete(listOf(id))

    override fun unblockNumbers(vararg addresses: String) {
        val ids = numbers.all()
            .filter { number -> addresses.any { address -> phoneNumberUtils.compare(number.address, address) } }
            .map { number -> number.id }
        numbers.delete(ids)
    }

}
