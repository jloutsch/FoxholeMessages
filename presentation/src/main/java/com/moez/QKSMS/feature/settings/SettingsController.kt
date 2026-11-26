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

import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.MainScope
import dev.octoshrimpy.quik.common.util.extensions.longClicks
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.util.extensions.clicks
import dev.octoshrimpy.quik.common.base.QkController
import android.animation.ObjectAnimator
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.RouterTransaction
import dev.octoshrimpy.quik.BuildConfig
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.MenuItem
import dev.octoshrimpy.quik.common.QkChangeHandler
import dev.octoshrimpy.quik.common.QkDialog
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.animateLayoutChanges
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setVisible
import dev.octoshrimpy.quik.common.widget.PreferenceView
import dev.octoshrimpy.quik.common.widget.TextInputDialog
import dev.octoshrimpy.quik.databinding.SettingsControllerBinding
import dev.octoshrimpy.quik.feature.settings.about.AboutController
import dev.octoshrimpy.quik.feature.settings.messagesorting.MessageSortingController
import dev.octoshrimpy.quik.feature.settings.swipe.SwipeActionsController
import dev.octoshrimpy.quik.feature.themepicker.ThemePickerController
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.repository.SyncRepository
import dev.octoshrimpy.quik.util.Preferences
import javax.inject.Inject

class SettingsController : QkController<SettingsControllerBinding, SettingsView, SettingsState, SettingsPresenter>(), SettingsView {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): SettingsControllerBinding =
        SettingsControllerBinding.inflate(inflater, container, false)

    @Inject lateinit var context: Context
    @Inject lateinit var colors: Colors
    @Inject lateinit var nightModeDialog: QkDialog
    @Inject lateinit var textSizeDialog: QkDialog
    @Inject lateinit var sendDelayDialog: QkDialog
    @Inject lateinit var mmsSizeDialog: QkDialog
    @Inject lateinit var messageLinkHandlingDialog: QkDialog

    @Inject override lateinit var presenter: SettingsPresenter

    private val signatureDialog: TextInputDialog by lazy {
        TextInputDialog(activity!!, context.getString(R.string.settings_signature_title), { signatureEvents.tryEmit(it) })
    }

    private val startTimeSelectedEvents = events<Pair<Int, Int>>()
    private val endTimeSelectedEvents = events<Pair<Int, Int>>()
    private val signatureEvents = events<String>()

    // Lives as long as this controller (init to onDestroy), like the Rx subscription it replaces
    private val controllerScope = MainScope()

    private val progressAnimator by lazy { ObjectAnimator.ofInt(binding.syncingProgress, "progress", 0, 0) }

    // Holds back drawing until the settings are loaded. Changing the theme, night mode or pure black
    // rebuilds this screen, which otherwise showed the layout's placeholder values for a frame.
    private var holdDrawing: ViewTreeObserver.OnPreDrawListener? = null

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH

        // Redraw everything in the new colors when the theme changes. drop(1): the first value is
        // the current theme (under RxJava it arrived during init, when activity was still null)
        colors.themeChanges()
                .drop(1)
                .collectIn(controllerScope) { activity?.recreate() }
    }

    override fun onViewCreated() {
        holdDrawing = ViewTreeObserver.OnPreDrawListener { false }
            .also { binding.root.viewTreeObserver.addOnPreDrawListener(it) }
        binding.root.postDelayed(::showSettings, 500) // in case loading never finishes

        when (Build.VERSION.SDK_INT >= 29) {
            true -> nightModeDialog.adapter.setData(R.array.night_modes)
            false -> nightModeDialog.adapter.data = context.resources.getStringArray(R.array.night_modes)
                    .mapIndexed { index, title -> MenuItem(title, index) }
                    .drop(1)
        }
        textSizeDialog.adapter.setData(R.array.text_sizes)
        sendDelayDialog.adapter.setData(R.array.delayed_sending_labels)
        mmsSizeDialog.adapter.setData(R.array.mms_sizes, R.array.mms_sizes_ids)
        messageLinkHandlingDialog.adapter.setData(R.array.messageLinkHandlings, R.array.messageLinkHandling_ids)

        binding.about.summary = context.getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.title_settings)
        showBackButton(true)
    }

    override fun preferenceClicks(): Flow<PreferenceView> = (0 until binding.preferences.childCount)
            .map { index -> binding.preferences.getChildAt(index) }
            .mapNotNull { view -> view as? PreferenceView }
            .map { preference -> preference.clicks().map { preference } }
            .merge()

    override fun aboutLongClicks(): Flow<Unit> = binding.about.longClicks()


    override fun nightModeSelected(): Flow<Int> = nightModeDialog.adapter.menuItemClicks

    override fun nightStartSelected(): Flow<Pair<Int, Int>> = startTimeSelectedEvents

    override fun nightEndSelected(): Flow<Pair<Int, Int>> = endTimeSelectedEvents

    override fun textSizeSelected(): Flow<Int> = textSizeDialog.adapter.menuItemClicks

    override fun sendDelaySelected(): Flow<Int> = sendDelayDialog.adapter.menuItemClicks

    override fun signatureChanged(): Flow<String> = signatureEvents

    override fun mmsSizeSelected(): Flow<Int> = mmsSizeDialog.adapter.menuItemClicks

    override fun messageLinkHandlingSelected(): Flow<Int> = messageLinkHandlingDialog.adapter.menuItemClicks

    override fun render(state: SettingsState) {
        binding.theme.findViewById<View>(R.id.themePreview)?.setBackgroundTint(state.theme)
        binding.night.summary = state.nightModeSummary
        nightModeDialog.adapter.selectedItem = state.nightModeId
        binding.nightStart.setVisible(state.nightModeId == Preferences.NIGHT_MODE_AUTO)
        binding.nightStart.summary = state.nightStart
        binding.nightEnd.setVisible(state.nightModeId == Preferences.NIGHT_MODE_AUTO)
        binding.nightEnd.summary = state.nightEnd

        binding.black.setVisible(state.nightModeId != Preferences.NIGHT_MODE_OFF)
        binding.black.checkbox?.isChecked = state.black

        binding.autoEmoji.checkbox?.isChecked = state.autoEmojiEnabled

        binding.delayed.summary = state.sendDelaySummary
        sendDelayDialog.adapter.selectedItem = state.sendDelayId

        binding.delivery.checkbox?.isChecked = state.deliveryEnabled

        binding.unreadAtTop.checkbox?.isChecked = state.unreadAtTopEnabled

        binding.signature.summary = state.signature.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.settings_signature_summary)

        binding.textSize.summary = state.textSizeSummary
        textSizeDialog.adapter.selectedItem = state.textSizeId

        binding.pinchToZoom.checkbox?.isChecked = state.pinchToZoomEnabled
        binding.confirmArchive.checkbox?.isChecked = state.confirmArchiveEnabled
        binding.autoColor.checkbox?.isChecked = state.autoColor
        binding.systemColors.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        binding.systemColors.checkbox?.isChecked = state.systemColors

        binding.systemFont.checkbox?.isChecked = state.systemFontEnabled

        binding.showStt.checkbox?.isChecked = state.showStt

        binding.longAsMms.checkbox?.isChecked = state.longAsMms

        binding.mmsSize.summary = state.maxMmsSizeSummary
        mmsSizeDialog.adapter.selectedItem = state.maxMmsSizeId

        binding.messsageLinkHandling.summary = state.messageLinkHandlingSummary
        messageLinkHandlingDialog.adapter.selectedItem = state.messageLinkHandlingId

        when (state.syncProgress) {
            is SyncRepository.SyncProgress.Idle -> binding.syncingProgress.isVisible = false

            is SyncRepository.SyncProgress.Running -> {
                binding.syncingProgress.isVisible = true
                binding.syncingProgress.max = state.syncProgress.max
                progressAnimator.apply { setIntValues(binding.syncingProgress.progress, state.syncProgress.progress) }.start()
                binding.syncingProgress.isIndeterminate = state.syncProgress.indeterminate
            }

            is SyncRepository.SyncProgress.ParsingEmojis -> {
                binding.syncingProgress.isVisible = true
                binding.syncingProgress.max = state.syncProgress.max
                progressAnimator.apply { setIntValues(binding.syncingProgress.progress, state.syncProgress.progress) }.start()
                binding.syncingProgress.isIndeterminate = state.syncProgress.indeterminate
            }
        }

        if (state.loaded) showSettings()
    }

    private fun showSettings() {
        val listener = holdDrawing ?: return
        holdDrawing = null
        val root = view ?: return
        root.viewTreeObserver.removeOnPreDrawListener(listener)
        root.jumpDrawablesToCurrentState() // switches take their saved positions without sliding
        binding.preferences.animateLayoutChanges = true
        root.invalidate()
    }


    override fun onDestroy() {
        controllerScope.cancel()
        super.onDestroy()
    }

    // TODO change this to a PopupWindow
    override fun showNightModeDialog() = nightModeDialog.show(activity!!)

    override fun showStartTimePicker(hour: Int, minute: Int) {
        TimePickerDialog(activity, { _, newHour, newMinute ->
            startTimeSelectedEvents.tryEmit(Pair(newHour, newMinute))
        }, hour, minute, DateFormat.is24HourFormat(activity)).show()
    }

    override fun showEndTimePicker(hour: Int, minute: Int) {
        TimePickerDialog(activity, { _, newHour, newMinute ->
            endTimeSelectedEvents.tryEmit(Pair(newHour, newMinute))
        }, hour, minute, DateFormat.is24HourFormat(activity)).show()
    }

    override fun showTextSizePicker() = textSizeDialog.show(activity!!)

    override fun showDelayDurationDialog() = sendDelayDialog.show(activity!!)

    override fun showSignatureDialog(signature: String) = signatureDialog.setText(signature).show()

    override fun showMmsSizePicker() = mmsSizeDialog.show(activity!!)

    override fun showMessageLinkHandlingDialogPicker() = messageLinkHandlingDialog.show(activity!!)

    override fun showSwipeActions() {
        router.pushController(RouterTransaction.with(SwipeActionsController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showThemePicker() {
        router.pushController(RouterTransaction.with(ThemePickerController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showAbout() {
        router.pushController(RouterTransaction.with(AboutController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun showMessageSorting() {
        router.pushController(RouterTransaction.with(MessageSortingController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

}
