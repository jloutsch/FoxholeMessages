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
package dev.octoshrimpy.quik.feature.settings.messagesorting

import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.base.QkView
import dev.octoshrimpy.quik.common.widget.PreferenceView

interface MessageSortingView : QkView<MessageSortingState> {

    fun preferenceClicks(): Flow<PreferenceView>
    fun confirmSortExistingIntent(): Flow<Unit>
    fun otpRetentionSelected(): Flow<Int>
    fun otpNotificationSizeSelected(): Flow<Int>
    /** Category names (see InboxTab) the user ticked in the widget-tabs picker. */
    fun widgetTabsSelected(): Flow<Set<String>>

    fun showSenderRules()
    fun showTrustedSenders()
    fun showSortExistingConfirmDialog()
    fun showOtpRetentionPicker()
    fun showOtpNotificationSizePicker()
    fun showWidgetTabsPicker(categories: List<String>, labels: List<String>, selected: Set<String>)

}
