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
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.MessageContentFilterData
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class MessageContentFilterRepositoryImpl @Inject constructor(
    private val database: FoxholeDatabase
) : MessageContentFilterRepository {

    private val filters get() = database.messageContentFilterDao()

    override fun exportFilters(): List<MessageContentFilterData> = filters.all().map {
        MessageContentFilterData(it.value, it.caseSensitive, it.isRegex, it.includeContacts)
    }

    override fun createFilter(data: MessageContentFilterData) {
        // Next id read inside the transaction so a concurrent insert can't take it first
        database.runInTransaction {
            val maxId = filters.maxId() ?: -1
            filters.insert(listOf(MessageContentFilter(maxId + 1, data.value, data.caseSensitive, data.isRegex, data.includeContacts)))
        }
    }

    override fun getMessageContentFilters(): Flow<List<MessageContentFilter>> = filters.observeAll()

    override fun isBlocked(messageBody: String, address: String, contactsRepo: ContactRepository): Boolean {
        val isContact = contactsRepo.isContact(address)
        return filters.all().any { filter ->
            ContentFilterMatcher.matches(
                filter.value, filter.caseSensitive, filter.isRegex, filter.includeContacts,
                messageBody, isContact
            )
        }
    }

    override fun removeFilter(id: Long) = filters.delete(id)

}
