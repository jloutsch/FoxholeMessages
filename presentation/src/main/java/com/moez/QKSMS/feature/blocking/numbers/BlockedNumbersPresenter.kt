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
package dev.octoshrimpy.quik.feature.blocking.numbers

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkFlowPresenter
import dev.octoshrimpy.quik.interactor.MarkUnblocked
import dev.octoshrimpy.quik.repository.BlockingRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import javax.inject.Inject

class BlockedNumbersPresenter @Inject constructor(
    private val blockingRepo: BlockingRepository,
    private val conversationRepo: ConversationRepository,
    private val markUnblocked: MarkUnblocked
) : QkFlowPresenter<BlockedNumbersView, BlockedNumbersState>(BlockedNumbersState()) {

    init {
        blockingRepo.getBlockedNumbers().collectIn(presenterScope) { list -> newState { copy(numbers = list) } }
    }

    override fun bindIntents(view: BlockedNumbersView) {
        super.bindIntents(view)

        view.unblockAddress().collectIn(view.bindScope) { id ->
            withContext(Dispatchers.IO) {
                blockingRepo.getBlockedNumber(id)?.address
                    ?.let { address -> conversationRepo.getConversation(listOf(address)) }
                    ?.let { conversation -> markUnblocked.execute(listOf(conversation.id)) }
                blockingRepo.unblockNumber(id)
            }
        }

        view.addAddress().collectIn(view.bindScope) { view.showAddDialog() }

        view.saveAddress().collectIn(view.bindScope) { address ->
            withContext(Dispatchers.IO) { blockingRepo.blockNumber(address) }
        }
    }

}
