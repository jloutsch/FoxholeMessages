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
package dev.octoshrimpy.quik.feature.blocking.filters

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import javax.inject.Inject

class MessageContentFiltersPresenter @Inject constructor(
    private val filterRepo: MessageContentFilterRepository,
) : QkPresenter<MessageContentFiltersView, MessageContentFiltersState>(MessageContentFiltersState()) {

    init {
        filterRepo.getMessageContentFilters().collectIn(presenterScope) { list -> newState { copy(filters = list) } }
    }

    override fun bindIntents(view: MessageContentFiltersView) {
        super.bindIntents(view)

        view.removeFilter().collectIn(view.bindScope) { id -> withContext(Dispatchers.IO) { filterRepo.removeFilter(id) } }

        view.addFilter().collectIn(view.bindScope) { view.showAddDialog() }

        view.saveFilter().collectIn(view.bindScope) { filterData ->
            withContext(Dispatchers.IO) { filterRepo.createFilter(filterData) }
        }
    }

}
