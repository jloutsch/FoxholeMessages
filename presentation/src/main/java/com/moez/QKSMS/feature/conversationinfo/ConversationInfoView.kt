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
package dev.octoshrimpy.quik.feature.conversationinfo

import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.base.QkView
import android.net.Uri

interface ConversationInfoView : QkView<ConversationInfoState> {

    fun recipientClicks(): Flow<Long>
    fun recipientLongClicks(): Flow<Long>
    fun themeClicks(): Flow<Long>
    fun nameClicks(): Flow<Unit>
    fun nameChanges(): Flow<String>
    fun notificationClicks(): Flow<Unit>
    fun backgroundImageClicks(): Flow<Unit>
    fun backgroundImageLongClicks(): Flow<Unit>
    fun backgroundImageSelected(): Flow<Uri>
    fun markUnreadClicks(): Flow<Unit>
    fun pinClicks(): Flow<Unit>
    fun archiveClicks(): Flow<Unit>
    fun blockClicks(): Flow<Unit>
    fun deleteClicks(): Flow<Unit>
    fun confirmDelete(): Flow<Unit>
    fun mediaClicks(): Flow<Long>

    fun showNameDialog(name: String)
    fun showThemePicker(recipientId: Long)
    fun showBackgroundImagePicker()
    fun showBlockingDialog(conversations: List<Long>, block: Boolean)
    fun requestDefaultSms()
    fun showDeleteDialog()

}
