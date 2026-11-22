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

import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkPresenter
import android.content.Context
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.ExternalNavigator
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.common.util.extensions.makeToast
import dev.octoshrimpy.quik.interactor.SyncMessages
import dev.octoshrimpy.quik.repository.SyncRepository
import dev.octoshrimpy.quik.util.NightModeManager
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

class SettingsPresenter @Inject constructor(
    colors: Colors,
    syncRepo: SyncRepository,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val externalNavigator: ExternalNavigator,
    private val nightModeManager: NightModeManager,
    private val prefs: Preferences,
    private val syncMessages: SyncMessages
) : QkPresenter<SettingsView, SettingsState>(SettingsState(
        nightModeId = prefs.nightMode.get()
)) {

    init {
        // Colors is still RxJava (other screens read it): bridged until Task 29
        colors.themeChanges()
                .collectIn(presenterScope) { theme -> newState { copy(theme = theme.theme) } }

        val nightModeLabels = context.resources.getStringArray(R.array.night_modes)
        prefs.nightMode.asFlow().collectIn(presenterScope) { nightMode ->
                    newState { copy(nightModeSummary = nightModeLabels[nightMode], nightModeId = nightMode) }
                }

        prefs.nightStart.asFlow()
                .map { time -> nightModeManager.parseTime(time) }
                .map { calendar -> calendar.timeInMillis }
                .map { millis -> dateFormatter.getTimestamp(millis) }
                .collectIn(presenterScope) { nightStart -> newState { copy(nightStart = nightStart) } }

        prefs.nightEnd.asFlow()
                .map { time -> nightModeManager.parseTime(time) }
                .map { calendar -> calendar.timeInMillis }
                .map { millis -> dateFormatter.getTimestamp(millis) }
                .collectIn(presenterScope) { nightEnd -> newState { copy(nightEnd = nightEnd) } }

        prefs.black.asFlow().collectIn(presenterScope) { black -> newState { copy(black = black) } }

        prefs.notifications().asFlow().collectIn(presenterScope) { enabled -> newState { copy(notificationsEnabled = enabled) } }

        prefs.autoEmoji.asFlow().collectIn(presenterScope) { enabled -> newState { copy(autoEmojiEnabled = enabled) } }

        val delayedSendingLabels = context.resources.getStringArray(R.array.delayed_sending_labels)
        prefs.sendDelay.asFlow().collectIn(presenterScope) { id -> newState { copy(sendDelaySummary = delayedSendingLabels[id], sendDelayId = id) } }

        prefs.delivery.asFlow().collectIn(presenterScope) { enabled -> newState { copy(deliveryEnabled = enabled) } }

        prefs.unreadAtTop.asFlow().collectIn(presenterScope) { enabled -> newState { copy(unreadAtTopEnabled = enabled) } }

        prefs.signature.asFlow().collectIn(presenterScope) { signature -> newState { copy(signature = signature) } }

        val textSizeLabels = context.resources.getStringArray(R.array.text_sizes)
        prefs.textSize.asFlow().collectIn(presenterScope) { textSize ->
                    newState { copy(textSizeSummary = textSizeLabels[textSize], textSizeId = textSize) }
                }

        prefs.autoColor.asFlow().collectIn(presenterScope) { autoColor -> newState { copy(autoColor = autoColor) } }

        prefs.systemColors.asFlow().collectIn(presenterScope) { systemColors -> newState { copy(systemColors = systemColors) } }

        prefs.pinchToZoom.asFlow().collectIn(presenterScope) { enabled -> newState { copy(pinchToZoomEnabled = enabled) } }

        prefs.confirmArchive.asFlow().collectIn(presenterScope) { enabled -> newState { copy(confirmArchiveEnabled = enabled) } }

        prefs.systemFont.asFlow().collectIn(presenterScope) { enabled -> newState { copy(systemFontEnabled = enabled) } }

        prefs.showStt.asFlow().collectIn(presenterScope) { enabled -> newState { copy(showStt = enabled) } }

        prefs.longAsMms.asFlow().collectIn(presenterScope) { enabled -> newState { copy(longAsMms = enabled) } }

        val mmsSizeLabels = context.resources.getStringArray(R.array.mms_sizes)
        val mmsSizeIds = context.resources.getIntArray(R.array.mms_sizes_ids)
        prefs.mmsSize.asFlow().collectIn(presenterScope) { maxMmsSize ->
                    val index = mmsSizeIds.indexOf(maxMmsSize)
                    newState { copy(maxMmsSizeSummary = mmsSizeLabels[index], maxMmsSizeId = maxMmsSize) }
                }

        val messageLinkHandlingLabels = context.resources.getStringArray(R.array.messageLinkHandlings)
        val messageLinkHandlingIds = context.resources.getIntArray(R.array.messageLinkHandling_ids)
        prefs.messageLinkHandling.asFlow().collectIn(presenterScope) { messageLinkHandlingId ->
                val index = messageLinkHandlingIds.indexOf(messageLinkHandlingId)
                newState {
                    copy(
                        messageLinkHandlingSummary = messageLinkHandlingLabels[index],
                        messageLinkHandlingId = messageLinkHandlingId
                    )
                }
            }

        // The repository is still RxJava (the sync worker and Main read it too): bridged
        @OptIn(FlowPreview::class) // sample() is stable in practice; still marked preview
        syncRepo.syncProgress
                .sample(16)
                .distinctUntilChanged()
                .collectIn(presenterScope) { syncProgress -> newState { copy(syncProgress = syncProgress) } }
    }

    override fun onCleared() {
        syncMessages.dispose() // as under RxJava: leaving Settings cancels a sync it started
        super.onCleared()
    }

    override fun bindIntents(view: SettingsView) {
        super.bindIntents(view)

        view.preferenceClicks().collectIn(view.bindScope) {
                    Timber.v("Preference click: ${context.resources.getResourceName(it.id)}")

                    when (it.id) {
                        R.id.theme -> view.showThemePicker()

                        R.id.systemColors -> prefs.systemColors.set(!prefs.systemColors.get())

                        R.id.night -> view.showNightModeDialog()

                        R.id.nightStart -> {
                            val date = nightModeManager.parseTime(prefs.nightStart.get())
                            view.showStartTimePicker(date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE))
                        }

                        R.id.nightEnd -> {
                            val date = nightModeManager.parseTime(prefs.nightEnd.get())
                            view.showEndTimePicker(date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE))
                        }

                        R.id.black -> prefs.black.set(!prefs.black.get())

                        R.id.autoEmoji -> prefs.autoEmoji.set(!prefs.autoEmoji.get())

                        R.id.notifications -> externalNavigator.showNotificationSettings()

                        R.id.swipeActions -> view.showSwipeActions()

                        R.id.messageSorting -> view.showMessageSorting()

                        R.id.delayed -> view.showDelayDurationDialog()

                        R.id.delivery -> prefs.delivery.set(!prefs.delivery.get())

                        R.id.unreadAtTop -> prefs.unreadAtTop.set(!prefs.unreadAtTop.get())

                        R.id.signature -> view.showSignatureDialog(prefs.signature.get())

                        R.id.textSize -> view.showTextSizePicker()

                        R.id.pinchToZoom -> prefs.pinchToZoom.set(!prefs.pinchToZoom.get())

                        R.id.confirmArchive -> prefs.confirmArchive.set(!prefs.confirmArchive.get())

                        R.id.autoColor -> {
                            prefs.autoColor.set(!prefs.autoColor.get())
                        }

                        R.id.systemFont -> prefs.systemFont.set(!prefs.systemFont.get())

                        R.id.showStt -> {
                            prefs.showStt.set(!prefs.showStt.get())
                            prefs.showSttOffsetX.set(Float.MIN_VALUE)
                            prefs.showSttOffsetY.set(Float.MIN_VALUE)
                        }

                        R.id.longAsMms -> prefs.longAsMms.set(!prefs.longAsMms.get())

                        R.id.mmsSize -> view.showMmsSizePicker()

                        R.id.messsageLinkHandling -> view.showMessageLinkHandlingDialogPicker()

                        R.id.sync -> syncMessages.execute(Unit)

                        R.id.about -> view.showAbout()

                        R.id.support -> externalNavigator.showVenmoDonation()
                    }
        }

        view.aboutLongClicks().collectIn(view.bindScope) {
            val enabled = !prefs.logging.get()
            prefs.logging.set(enabled)
            context.makeToast(when (enabled) {
                true -> R.string.settings_logging_enabled
                false -> R.string.settings_logging_disabled
            })
        }

        view.nightModeSelected().collectIn(view.bindScope, nightModeManager::updateNightMode)

        view.nightStartSelected().collectIn(view.bindScope) { nightModeManager.setNightStart(it.first, it.second) }

        view.nightEndSelected().collectIn(view.bindScope) { nightModeManager.setNightEnd(it.first, it.second) }

        view.textSizeSelected().collectIn(view.bindScope, prefs.textSize::set)

        view.sendDelaySelected().collectIn(view.bindScope, prefs.sendDelay::set)

        view.signatureChanged().collectIn(view.bindScope, prefs.signature::set)

        view.mmsSizeSelected().collectIn(view.bindScope, prefs.mmsSize::set)

        view.messageLinkHandlingSelected().collectIn(view.bindScope, prefs.messageLinkHandling::set)
    }

}