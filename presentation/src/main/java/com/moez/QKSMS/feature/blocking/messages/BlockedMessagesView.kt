/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
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
package dev.octoshrimpy.quik.feature.blocking.messages

import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.base.QkView

interface BlockedMessagesView : QkView<BlockedMessagesState> {

    val menuReadyIntent: Flow<Unit>
    val optionsItemIntent: Flow<Int>
    val conversationClicks: Flow<Long>
    val selectionChanges: Flow<List<Long>>
    val confirmDeleteIntent: Flow<List<Long>>
    val backClicked: Flow<Unit>

    fun clearSelection()
    fun showBlockingDialog(conversations: List<Long>, block: Boolean)
    fun showDeleteDialog(conversations: List<Long>)
    fun goBack()

}
