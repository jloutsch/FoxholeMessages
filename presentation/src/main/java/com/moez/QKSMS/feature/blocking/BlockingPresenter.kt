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
package dev.octoshrimpy.quik.feature.blocking

import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.util.Preferences
import javax.inject.Inject

class BlockingPresenter @Inject constructor(
    private val prefs: Preferences
) : QkPresenter<BlockingView, BlockingState>(BlockingState()) {

    init {
        prefs.drop.asFlow().collectIn(presenterScope) { enabled -> newState { copy(dropEnabled = enabled) } }
    }

    override fun bindIntents(view: BlockingView) {
        super.bindIntents(view)

        view.blockedNumbersIntent.collectIn(view.bindScope) { view.openBlockedNumbers() }

        view.messageContentFiltersIntent.collectIn(view.bindScope) { view.openMessageContentFilters() }

        view.blockedMessagesIntent.collectIn(view.bindScope) { view.openBlockedMessages() }

        view.dropClickedIntent.collectIn(view.bindScope) { prefs.drop.set(!prefs.drop.get()) }
    }

}
