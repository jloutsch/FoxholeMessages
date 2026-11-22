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
package dev.octoshrimpy.quik.feature.settings.messagesorting.senders

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkFlowPresenter
import dev.octoshrimpy.quik.repository.TrustedSenderRepository
import javax.inject.Inject

class TrustedSendersPresenter @Inject constructor(
    private val trustedSenderRepo: TrustedSenderRepository
) : QkFlowPresenter<TrustedSendersView, TrustedSendersState>(TrustedSendersState()) {

    init {
        trustedSenderRepo.getTrustedSenders().collectIn(presenterScope) { list -> newState { copy(senders = list) } }
    }

    override fun bindIntents(view: TrustedSendersView) {
        super.bindIntents(view)

        // A locked sender (added by Message Sorting) asks first; others are removed straight away
        view.removeSender().collectIn(view.bindScope) { sender ->
            when (sender.locked) {
                true -> view.showRemoveLockedConfirmDialog(sender)
                false -> withContext(Dispatchers.IO) { trustedSenderRepo.removeTrustedSender(sender.id, false) }
            }
        }

        view.confirmRemoveLocked().collectIn(view.bindScope) { sender ->
            withContext(Dispatchers.IO) { trustedSenderRepo.removeTrustedSender(sender.id, true) }
        }

        view.addAddress().collectIn(view.bindScope) { view.showAddDialog() }

        view.saveAddress().collectIn(view.bindScope) { address ->
            if (address.isNotBlank()) withContext(Dispatchers.IO) { trustedSenderRepo.addTrustedSender(address, false) }
        }
    }

}
