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
package dev.octoshrimpy.quik.interactor

import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.repository.ConversationRepository
import javax.inject.Inject

class MarkPinned @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val updateBadge: UpdateBadge,
    private val shortcutManager: ShortcutManager
) : Interactor<List<Long>>() {

    override suspend fun perform(params: List<Long>) {
        conversationRepo.markPinned(*params.toLongArray())
        shortcutManager.updateShortcuts() // Update shortcuts
        updateBadge.perform(Unit) // Update the badge and widget
    }

}
