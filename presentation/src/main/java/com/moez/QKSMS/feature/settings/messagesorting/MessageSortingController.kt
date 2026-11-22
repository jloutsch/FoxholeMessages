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

import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.clicks
import dev.octoshrimpy.quik.common.base.QkFlowController
import androidx.core.view.isVisible
import java.text.NumberFormat
import dev.octoshrimpy.quik.worker.ClassifyExistingMessagesWorker.SortStatus
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.bluelinelabs.conductor.RouterTransaction
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.QkChangeHandler
import dev.octoshrimpy.quik.common.QkDialog
import dev.octoshrimpy.quik.common.widget.PreferenceView
import dev.octoshrimpy.quik.databinding.MessageSortingControllerBinding
import dev.octoshrimpy.quik.feature.settings.messagesorting.senders.SenderRulesController
import dev.octoshrimpy.quik.feature.settings.messagesorting.senders.TrustedSendersController
import dev.octoshrimpy.quik.injection.appComponent
import javax.inject.Inject

class MessageSortingController :
    QkFlowController<MessageSortingControllerBinding, MessageSortingView, MessageSortingState, MessageSortingPresenter>(),
    MessageSortingView {

    @Inject override lateinit var presenter: MessageSortingPresenter
    @Inject lateinit var otpRetentionDialog: QkDialog
    @Inject lateinit var otpNotificationSizeDialog: QkDialog

    private val confirmSortExistingEvents = events<Unit>()
    private val widgetTabsEvents = events<Set<String>>()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): MessageSortingControllerBinding =
        MessageSortingControllerBinding.inflate(inflater, container, false)

    override fun onViewCreated() {
        otpRetentionDialog.adapter.setData(R.array.otp_retention_labels, R.array.otp_retention_ids)
        otpNotificationSizeDialog.adapter.setData(R.array.otp_notification_size_labels, R.array.otp_notification_size_ids)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.message_sorting_title)
        showBackButton(true)
    }

    override fun render(state: MessageSortingState) {
        binding.autoSort.checkbox?.isChecked = state.autoSortEnabled
        binding.otpRetention.summary = state.otpRetentionSummary
        binding.widgetTabs.summary = state.widgetTabsSummary
        otpRetentionDialog.adapter.selectedItem = state.otpRetentionId
        binding.otpNotificationSize.summary = state.otpNotificationSizeSummary
        otpNotificationSizeDialog.adapter.selectedItem = state.otpNotificationSizeId
        renderSortStatus(state.sortStatus)
    }

    private fun renderSortStatus(status: SortStatus) {
        val res = activity!!.resources
        val number = NumberFormat.getIntegerInstance()
        binding.sortExisting.summary = when (status) {
            SortStatus.Idle -> res.getString(R.string.message_sorting_sort_existing_summary)
            is SortStatus.Running -> if (status.total == 0) res.getString(R.string.message_sorting_sort_starting)
                else res.getString(R.string.message_sorting_sort_progress, number.format(status.done), number.format(status.total))
            is SortStatus.Finished -> res.getQuantityString(R.plurals.message_sorting_sort_done, status.sorted, number.format(status.sorted))
            SortStatus.Failed -> res.getString(R.string.message_sorting_sort_failed)
        }
        binding.sortProgress.isVisible = status is SortStatus.Running
        if (status is SortStatus.Running) {
            binding.sortProgress.isIndeterminate = status.total == 0
            binding.sortProgress.max = status.total
            binding.sortProgress.progress = status.done
        }
    }

    override fun preferenceClicks(): Flow<PreferenceView> =
        (0 until binding.preferences.childCount)
            .map { index -> binding.preferences.getChildAt(index) }
            .mapNotNull { child -> child as? PreferenceView }
            .map { preference -> preference.clicks().map { preference } }
            .merge()

    override fun confirmSortExistingIntent(): Flow<Unit> = confirmSortExistingEvents

    // QkDialog is shared with screens still on RxJava: bridged until its last user converts
    override fun otpRetentionSelected(): Flow<Int> = otpRetentionDialog.adapter.menuItemClicks.asFlow()

    override fun showOtpRetentionPicker() = otpRetentionDialog.show(activity!!)

    override fun otpNotificationSizeSelected(): Flow<Int> = otpNotificationSizeDialog.adapter.menuItemClicks.asFlow()

    override fun showOtpNotificationSizePicker() = otpNotificationSizeDialog.show(activity!!)

    override fun widgetTabsSelected(): Flow<Set<String>> = widgetTabsEvents

    override fun showWidgetTabsPicker(categories: List<String>, labels: List<String>, selected: Set<String>) {
        val checked = BooleanArray(categories.size) { categories[it] in selected }
        AlertDialog.Builder(activity!!)
            .setTitle(R.string.message_sorting_widget_tabs_title)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                widgetTabsEvents.tryEmit(categories.filterIndexed { index, _ -> checked[index] }.toSet())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun showSenderRules() {
        router.pushController(RouterTransaction.with(SenderRulesController())
            .pushChangeHandler(QkChangeHandler())
            .popChangeHandler(QkChangeHandler()))
    }

    override fun showTrustedSenders() {
        router.pushController(RouterTransaction.with(TrustedSendersController())
            .pushChangeHandler(QkChangeHandler())
            .popChangeHandler(QkChangeHandler()))
    }

    override fun showSortExistingConfirmDialog() {
        AlertDialog.Builder(activity!!)
            .setTitle(R.string.message_sorting_sort_existing_title)
            .setMessage(R.string.message_sorting_sort_existing_confirm_message)
            .setPositiveButton(R.string.button_continue) { _, _ -> confirmSortExistingEvents.tryEmit(Unit) }
            .setNegativeButton(R.string.button_cancel) { _, _ -> }
            .show()
    }

}
