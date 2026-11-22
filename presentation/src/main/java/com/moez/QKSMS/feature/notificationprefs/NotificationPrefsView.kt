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
package dev.octoshrimpy.quik.feature.notificationprefs

import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.base.QkView
import android.net.Uri
import dev.octoshrimpy.quik.common.widget.PreferenceView

interface NotificationPrefsView : QkView<NotificationPrefsState> {

    val preferenceClickIntent: Flow<PreferenceView>
    val previewModeSelectedIntent: Flow<Int>
    val ringtoneSelectedIntent: Flow<String>
    val actionsSelectedIntent: Flow<Int>

    fun showPreviewModeDialog()
    fun showRingtonePicker(default: Uri?)
    fun showActionDialog(selected: Int)
}
