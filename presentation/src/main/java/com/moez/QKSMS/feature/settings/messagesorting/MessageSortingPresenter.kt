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

import dev.octoshrimpy.quik.common.util.extensions.makeToast
import dev.octoshrimpy.quik.manager.WidgetManager
import dev.octoshrimpy.quik.feature.conversations.Tab
import dev.octoshrimpy.quik.classifier.InboxTab
import android.content.Context
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.classifier.Category
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.interactor.DeleteOldOtps
import dev.octoshrimpy.quik.service.OtpRetentionService
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.worker.ClassifyExistingMessagesWorker
import io.reactivex.rxkotlin.plusAssign
import javax.inject.Inject

class MessageSortingPresenter @Inject constructor(
    private val context: Context,
    private val navigator: Navigator,
    private val prefs: Preferences,
    private val deleteOldOtps: DeleteOldOtps,
    private val widgetManager: WidgetManager
) : QkPresenter<MessageSortingView, MessageSortingState>(
    MessageSortingState(prefs.autoSortEnabled.get())
) {

    init {
        disposables += prefs.autoSortEnabled.asObservable()
            .subscribe { enabled -> newState { copy(autoSortEnabled = enabled) } }

        val otpRetentionLabels = context.resources.getStringArray(R.array.otp_retention_labels)
        val otpRetentionIds = context.resources.getIntArray(R.array.otp_retention_ids)
        disposables += prefs.otpRetentionDays.asObservable()
            .subscribe { days ->
                val index = otpRetentionIds.indexOf(days)
                newState { copy(otpRetentionSummary = otpRetentionLabels[index], otpRetentionId = days) }
            }

        val otpSizeLabels = context.resources.getStringArray(R.array.otp_notification_size_labels)
        val otpSizeIds = context.resources.getIntArray(R.array.otp_notification_size_ids)
        disposables += prefs.otpNotificationSize.asObservable()
            .subscribe { size ->
                val label = otpSizeLabels.getOrNull(otpSizeIds.indexOf(size)) ?: ""
                newState { copy(otpNotificationSizeSummary = label, otpNotificationSizeId = size) }
            }

        disposables += prefs.widgetTabs.asObservable()
            .subscribe { tabs ->
                val summary = InboxTab.ALL.filter { it in tabs }.joinToString(", ", transform = ::tabLabel)
                newState { copy(widgetTabsSummary = summary) }
            }
    }

    /** The tab's name as shown in the inbox, including any name the user gave it. */
    private fun tabLabel(category: String): String {
        val tab = Tab.values().first { it.category == category }
        return prefs.tabTitle(tab.name).get().takeIf { it.isNotBlank() } ?: context.getString(tab.titleRes)
    }

    override fun bindIntents(view: MessageSortingView) {
        super.bindIntents(view)

        view.preferenceClicks()
            .autoDisposable(view.scope())
            .subscribe { preference ->
                when (preference.id) {
                    R.id.autoSort -> {
                        val enabled = !prefs.autoSortEnabled.get()
                        prefs.autoSortEnabled.set(enabled)
                        // Messages that arrived while auto-sort was off have no category; sort them
                        // now (the backfill only touches uncategorized messages).
                        if (enabled) ClassifyExistingMessagesWorker.trigger(context)
                    }
                    R.id.notificationsPersonal -> navigator.showCategoryNotificationSettings(Category.PERSONAL.name)
                    R.id.notificationsPromotional -> navigator.showCategoryNotificationSettings(Category.PROMOTIONAL.name)
                    R.id.notificationsTransactional -> navigator.showCategoryNotificationSettings(Category.TRANSACTIONAL.name)
                    R.id.otpRetention -> view.showOtpRetentionPicker()
                    R.id.otpNotificationSize -> view.showOtpNotificationSizePicker()
                    R.id.widgetTabs -> view.showWidgetTabsPicker(
                        InboxTab.ALL, InboxTab.ALL.map(::tabLabel), prefs.widgetTabs.get()
                    )
                    R.id.senderRules -> view.showSenderRules()
                    R.id.sortExisting -> view.showSortExistingConfirmDialog()
                    R.id.trustedSenders -> view.showTrustedSenders()
                }
            }

        view.confirmSortExistingIntent()
            .autoDisposable(view.scope())
            .subscribe { ClassifyExistingMessagesWorker.trigger(context, fullResort = true) }

        view.widgetTabsSelected()
            .autoDisposable(view.scope())
            .subscribe { tabs ->
                if (tabs.isEmpty()) {
                    context.makeToast(R.string.message_sorting_widget_tabs_pick_one)
                } else {
                    prefs.widgetTabs.set(tabs)
                    widgetManager.sendDatasetChanged()
                }
            }

        view.otpNotificationSizeSelected()
            .autoDisposable(view.scope())
            .subscribe(prefs.otpNotificationSize::set)

        view.otpRetentionSelected()
            .autoDisposable(view.scope())
            .subscribe { days ->
                prefs.otpRetentionDays.set(days)
                when (days) {
                    0 -> OtpRetentionService.cancelJob(context)
                    else -> {
                        OtpRetentionService.scheduleJob(context)
                        deleteOldOtps.execute(Unit)
                    }
                }
            }
    }

}
