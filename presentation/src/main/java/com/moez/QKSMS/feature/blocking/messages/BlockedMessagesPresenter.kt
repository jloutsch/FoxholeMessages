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

import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkFlowPresenter
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.interactor.DeleteConversations
import dev.octoshrimpy.quik.repository.ConversationRepository
import javax.inject.Inject

class BlockedMessagesPresenter @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val blockingClient: BlockingClient,
    private val deleteConversations: DeleteConversations,
    private val navigator: Navigator
) : QkFlowPresenter<BlockedMessagesView, BlockedMessagesState>(BlockedMessagesState()) {

    init {
        conversationRepo.observeBlockedConversations()
            .collectIn(presenterScope) { conversations -> newState { copy(data = conversations) } }
    }

    override fun bindIntents(view: BlockedMessagesView) {
        super.bindIntents(view)

        // Re-render so the menu's buttons match the selection once the menu exists. (An unchanged
        // copy() pushed through newState no longer re-renders: StateFlow skips equal states.)
        view.menuReadyIntent.collectIn(view.bindScope) { view.render(state.value) }

        // The current selection, for the menu actions (what withLatestFrom read)
        var selection = emptyList<Long>()
        view.selectionChanges.collectIn(view.bindScope) { newSelection ->
            selection = newSelection
            newState { copy(selected = newSelection.size) }
        }

        view.optionsItemIntent.collectIn(view.bindScope) { itemId ->
            when (itemId) {
                R.id.block -> {
                    view.showBlockingDialog(selection, false)
                    view.clearSelection()
                }
                R.id.delete -> view.showDeleteDialog(selection)
            }
        }

        view.confirmDeleteIntent.collectIn(view.bindScope) { conversations ->
            deleteConversations.execute(conversations)
            view.clearSelection()
        }

        view.conversationClicks.collectIn(view.bindScope) { threadId -> navigator.showConversation(threadId) }

        view.backClicked.collectIn(view.bindScope) {
            when (state.value.selected) {
                0 -> view.goBack()
                else -> view.clearSelection()
            }
        }
    }

}
