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

import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkFlowViewModel
import dev.octoshrimpy.quik.common.util.extensions.makeToast
import dev.octoshrimpy.quik.common.util.OverlayPermission
import android.widget.Toast
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.ExternalNavigator
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.util.Preferences
import javax.inject.Inject
import javax.inject.Named

class NotificationPrefsViewModel @Inject constructor(
    @Named("threadId") private val threadId: Long,
    private val context: Context,
    private val conversationRepo: ConversationRepository,
    private val externalNavigator: ExternalNavigator,
    private val prefs: Preferences
) : QkFlowViewModel<NotificationPrefsView, NotificationPrefsState>(NotificationPrefsState(threadId = threadId)) {

    private val notifications = prefs.notifications(threadId)
    private val previews = prefs.notificationPreviews(threadId)
    private val wake = prefs.wakeScreen(threadId)
    private val vibration = prefs.vibration(threadId)
    private val ringtone = prefs.ringtone(threadId)

    init {
        viewModelScope.launch {
            val title = withContext(Dispatchers.IO) { conversationRepo.getConversation(threadId)?.getTitle() }
            if (title != null) newState { copy(conversationTitle = title) }
        }

        notifications.asFlow().collectIn(viewModelScope) { enabled -> newState { copy(notificationsEnabled = enabled) } }

        val previewLabels = context.resources.getStringArray(R.array.notification_preview_options)
        previews.asFlow().collectIn(viewModelScope) { previewId ->
                    newState { copy(previewSummary = previewLabels[previewId], previewId = previewId) }
                }

        val actionLabels = context.resources.getStringArray(R.array.notification_actions)
        prefs.notifAction1.asFlow().collectIn(viewModelScope) { previewId -> newState { copy(action1Summary = actionLabels[previewId]) } }

        prefs.notifAction2.asFlow().collectIn(viewModelScope) { previewId -> newState { copy(action2Summary = actionLabels[previewId]) } }

        prefs.notifAction3.asFlow().collectIn(viewModelScope) { previewId -> newState { copy(action3Summary = actionLabels[previewId]) } }

        wake.asFlow().collectIn(viewModelScope) { enabled -> newState { copy(wakeEnabled = enabled) } }

        prefs.silentNotContact.asFlow().collectIn(viewModelScope) { enabled -> newState { copy(silentNotContact = enabled) } }

        vibration.asFlow().collectIn(viewModelScope) { enabled -> newState { copy(vibrationEnabled = enabled) } }

        ringtone.asFlow()
                .map { uriString ->
                    uriString.takeIf { it.isNotEmpty() }
                            ?.let(Uri::parse)
                            ?.let { uri -> RingtoneManager.getRingtone(context, uri) }?.getTitle(context)
                            ?: context.getString(R.string.settings_ringtone_none)
                }
                .collectIn(viewModelScope) { title -> newState { copy(ringtoneName = title) } }

        prefs.qkreply.asFlow().collectIn(viewModelScope) { enabled -> newState { copy(qkReplyEnabled = enabled) } }

        prefs.qkreplyTapDismiss.asFlow().collectIn(viewModelScope) { enabled -> newState { copy(qkReplyTapDismiss = enabled) } }
    }

    override fun bindView(view: NotificationPrefsView) {
        super.bindView(view)

        // The last row tapped, so a chosen action goes to the slot that was tapped (what withLatestFrom read)
        var lastPreferenceId = 0

        view.preferenceClickIntent.collectIn(view.bindScope) { preferenceView ->
                    lastPreferenceId = preferenceView.id
                    when (preferenceView.id) {
                        R.id.notificationsO -> externalNavigator.showNotificationChannel(threadId)

                        R.id.notifications -> notifications.set(!notifications.get())

                        R.id.previews -> view.showPreviewModeDialog()

                        R.id.wake -> wake.set(!wake.get())

                        R.id.silentNotContact -> prefs.silentNotContact.set(!prefs.silentNotContact.get())

                        R.id.vibration -> vibration.set(!vibration.get())

                        R.id.ringtone -> view.showRingtonePicker(
                            ringtone.get().takeIf { it.isNotEmpty() }?.let(Uri::parse)
                        )

                        R.id.action1 -> view.showActionDialog(prefs.notifAction1.get())

                        R.id.action2 -> view.showActionDialog(prefs.notifAction2.get())

                        R.id.action3 -> view.showActionDialog(prefs.notifAction3.get())

                        R.id.qkreply -> {
                            val enable = !prefs.qkreply.get()
                            prefs.qkreply.set(enable)
                            // Android 10+ only lets an app open a popup from the background if it may
                            // "display over other apps"; without that the popup never appears.
                            if (enable && OverlayPermission.needed(context)) {
                                context.makeToast(R.string.settings_qkreply_overlay_needed, Toast.LENGTH_LONG)
                                externalNavigator.showOverlayPermissionSettings()
                            }
                        }

                        R.id.qkreplyTapDismiss -> prefs.qkreplyTapDismiss.set(!prefs.qkreplyTapDismiss.get())
                    }
        }

        view.previewModeSelectedIntent.collectIn(view.bindScope) { previews.set(it) }

        view.ringtoneSelectedIntent.collectIn(view.bindScope) { ringtone -> this.ringtone.set(ringtone) }

        view.actionsSelectedIntent.collectIn(view.bindScope) { action ->
            when (lastPreferenceId) {
                R.id.action1 -> prefs.notifAction1.set(action)
                R.id.action2 -> prefs.notifAction2.set(action)
                R.id.action3 -> prefs.notifAction3.set(action)
            }
        }
    }
}