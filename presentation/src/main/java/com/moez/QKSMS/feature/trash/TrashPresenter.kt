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
package dev.octoshrimpy.quik.feature.trash

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.interactor.EmptyTrash
import dev.octoshrimpy.quik.interactor.RestoreMessages
import dev.octoshrimpy.quik.repository.MessageRepository
import javax.inject.Inject

class TrashPresenter @Inject constructor(
    private val messageRepo: MessageRepository,
    private val restoreMessages: RestoreMessages,
    private val emptyTrash: EmptyTrash
) : QkPresenter<TrashView, TrashState>(TrashState()) {

    init {
        // Collected on the main thread (presenterScope); the query itself runs in the background
        messageRepo.getDeletedMessages().collectIn(presenterScope) { messages -> newState { copy(data = messages) } }
    }

    override fun bindIntents(view: TrashView) {
        super.bindIntents(view)

        view.restoreClicks.collectIn(view.bindScope) { messageId -> restoreMessages.execute(listOf(messageId)) }

        // Manual permanent-delete, in addition to the automatic 30-day purge - the user
        // shouldn't have to wait if they want a message gone for good right away
        view.deleteForeverClicks.collectIn(view.bindScope) { messageId -> view.showDeleteForeverDialog(messageId) }

        view.confirmDeleteForeverIntent.collectIn(view.bindScope) { messageId ->
            withContext(Dispatchers.IO) { messageRepo.deleteMessages(listOf(messageId)) }
        }

        view.emptyTrashIntent.collectIn(view.bindScope) { view.showEmptyTrashDialog() }

        view.confirmEmptyTrashIntent.collectIn(view.bindScope) { emptyTrash.execute(Unit) }

    }

}
