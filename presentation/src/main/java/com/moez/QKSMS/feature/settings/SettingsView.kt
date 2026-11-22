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
package dev.octoshrimpy.quik.feature.settings

import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.base.QkView
import dev.octoshrimpy.quik.common.widget.PreferenceView

interface SettingsView : QkView<SettingsState> {
    fun preferenceClicks(): Flow<PreferenceView>
    fun aboutLongClicks(): Flow<Unit>
    fun nightModeSelected(): Flow<Int>
    fun nightStartSelected(): Flow<Pair<Int, Int>>
    fun nightEndSelected(): Flow<Pair<Int, Int>>
    fun textSizeSelected(): Flow<Int>
    fun sendDelaySelected(): Flow<Int>
    fun signatureChanged(): Flow<String>
    fun mmsSizeSelected(): Flow<Int>
    fun messageLinkHandlingSelected(): Flow<Int>

    fun showNightModeDialog()
    fun showStartTimePicker(hour: Int, minute: Int)
    fun showEndTimePicker(hour: Int, minute: Int)
    fun showTextSizePicker()
    fun showDelayDurationDialog()
    fun showSignatureDialog(signature: String)
    fun showMmsSizePicker()
    fun showMessageLinkHandlingDialogPicker()
    fun showSwipeActions()
    fun showThemePicker()
    fun showAbout()
    fun showMessageSorting()
}
