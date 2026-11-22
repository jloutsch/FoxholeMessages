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
package dev.octoshrimpy.quik.feature.compose

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.base.QkView
import android.net.Uri
import android.view.MenuItem
import android.view.View
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.moez.QKSMS.common.QkMediaPlayer
import dev.octoshrimpy.quik.common.widget.MicInputCloudView
import dev.octoshrimpy.quik.model.Attachment
import dev.octoshrimpy.quik.model.Recipient

interface ComposeView : QkView<ComposeState> {

    companion object {
        const val CAMERA_DESTINATION_KEY = "camera_destination"
    }

    val activityVisibleIntent: Flow<Boolean>
    val chipsSelectedIntent: MutableSharedFlow<HashMap<String, String?>>
    val chipDeletedIntent: MutableSharedFlow<Recipient>
    val menuReadyIntent: Flow<Unit>
    val optionsItemIntent: Flow<Int>
    val contextItemIntent: Flow<MenuItem>
    val sendAsGroupIntent: Flow<Unit>
    val messagePartClickIntent: MutableSharedFlow<Long>
    val messagePartSaveIntent: MutableSharedFlow<Long>
    val messagePartContextMenuRegistrar: MutableSharedFlow<View>
    val messagesSelectedIntent: Flow<List<Long>>
    val cancelDelayedIntent: MutableSharedFlow<Long>
    val sendDelayedNowIntent: MutableSharedFlow<Long>
    val resendIntent: MutableSharedFlow<Long>
    val attachmentDeletedIntent: MutableSharedFlow<Attachment>
    val textChangedIntent: Flow<CharSequence>
    val queryChangedIntent: Flow<CharSequence>
    val attachIntent: Flow<Unit>
    val cameraIntent: Flow<Unit>
    val attachAnyFileIntent: Flow<Unit>
    val attachImageFileIntent: Flow<Unit>
    val scheduleIntent: Flow<Unit>
    val scheduleAction: Flow<Boolean>
    val attachContactIntent: Flow<Unit>
    val attachAnyFileSelectedIntent: Flow<Uri>
    val contactSelectedIntent: Flow<Uri>
    val inputContentIntent: Flow<InputContentInfoCompat>
    val scheduleSelectedIntent: Flow<Long>
    val scheduleCancelIntent: Flow<Unit>
    val changeSimIntent: Flow<Unit>
    val sendIntent: Flow<Unit>
    val backPressedIntent: Flow<Unit>
    val confirmDeleteIntent: Flow<List<Long>>
    val clearCurrentMessageIntent: MutableSharedFlow<Boolean>
    val messageLinkAskIntent: Flow<Uri>
    val reactionClickIntent: MutableSharedFlow<Long>
    val speechRecogniserIntent: Flow<Unit>
    val shadeIntent: Flow<Unit>
    val recordAudioStartStopRecording: MutableSharedFlow<Boolean>
    val recordAnAudioMessage: Flow<Unit>
    val recordAudioAbort: Flow<Unit>
    val recordAudioAttach: Flow<Unit>
    val recordAudioPlayerPlayPause: Flow<QkMediaPlayer.PlayingState>
    val recordAudioPlayerConfigUI: MutableSharedFlow<QkMediaPlayer.PlayingState>
    val recordAudioPlayerVisible: MutableSharedFlow<Boolean>
    val recordAudioMsgRecordVisible: MutableSharedFlow<Boolean>
    val recordAudioRecord: MutableSharedFlow<MicInputCloudView.ViewState>
    val recordAudioChronometer: MutableSharedFlow<Boolean>

    fun clearSelection()
    fun toggleSelectAll()
    fun expandMessages(messageIds: List<Long>, expand: Boolean)
    fun showDetails(details: String)
    fun showMessageLinkAskDialog(uri: Uri)
    fun requestDefaultSms()
    fun requestStoragePermission()
    fun requestRecordAudioPermission()
    fun requestSmsPermission()
    fun showContacts(sharing: Boolean, chips: List<Recipient>)
    fun themeChanged()
    fun showKeyboard()
    fun requestCamera()
    fun requestGallery(mimeType: String)
    fun requestDatePicker()
    fun requestContact()
    fun setDraft(draft: String)
    fun scrollToMessage(id: Long)
    fun showDeleteDialog( messages: List<Long>)
    fun showClearCurrentMessageDialog()
    fun showReactionsDialog(reactions: List<String>)
    fun startSpeechRecognition()
    fun focusMessage()
}