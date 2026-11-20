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
import dev.octoshrimpy.quik.model.SenderCategoryRule
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SenderCategoryRuleRepositoryImpl @Inject constructor(
    private val database: FoxholeDatabase,
    private val phoneNumberUtils: PhoneNumberUtils
) : SenderCategoryRuleRepository {

    private val rules get() = database.senderCategoryRuleDao()

    override fun getRules(): Flow<List<SenderCategoryRule>> = rules.observeAll()

    override fun getRule(address: String): SenderCategoryRule? =
        rules.all().firstOrNull { phoneNumberUtils.compare(it.address, address) }

    override fun setRule(address: String, category: String) {
        // One rule per sender: replaces any rule whose address matches this one
        database.runInTransaction {
            val existing = rules.all().filter { phoneNumberUtils.compare(it.address, address) }
            val nextId = (rules.maxId() ?: -1) + 1
            rules.delete(existing.map { it.id })
            rules.insert(SenderCategoryRule(nextId, address, category))
        }
    }

    override fun removeRule(id: Long) = rules.delete(listOf(id))

}
