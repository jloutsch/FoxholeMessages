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
package dev.octoshrimpy.quik.feature.settings.messagesorting.notifications

import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.clicks
import androidx.lifecycle.lifecycleScope
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.widget.PreferenceView
import dev.octoshrimpy.quik.databinding.CategoryNotificationActivityBinding
import javax.inject.Inject
import androidx.core.content.IntentCompat

class CategoryNotificationActivity : QkThemedActivity(), CategoryNotificationView {

    companion object {
        const val EXTRA_CATEGORY = "category"
    }

    private lateinit var binding: CategoryNotificationActivityBinding

    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    override val bindScope get() = lifecycleScope

    // Built from this activity's own views; a rotation makes a new activity, so it never goes stale
    override val preferenceClickIntent: Flow<PreferenceView>
        get() = (0 until binding.preferences.childCount)
            .map { index -> binding.preferences.getChildAt(index) }
            .mapNotNull { view -> view as? PreferenceView }
            .map { preference -> preference.clicks().map { preference } }
            .merge()
    override val ringtoneSelectedIntent = events<String>()

    private val viewModel by lazy {
        ViewModelProvider(this, viewModelFactory)[CategoryNotificationViewModel::class.java]
    }

    private val ringtoneLauncher = launcherForResult { data ->
        val uri = data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
        ringtoneSelectedIntent.tryEmit(uri?.toString() ?: "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = CategoryNotificationActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showBackButton(true)
        viewModel.bindView(this)
    }

    override fun render(state: CategoryNotificationState) {
        title = state.categoryTitle
        binding.notifications.checkbox?.isChecked = state.notificationsEnabled
        binding.previews.checkbox?.isChecked = state.previewEnabled
        binding.previews.isEnabled = state.notificationsEnabled
        binding.vibration.checkbox?.isChecked = state.vibrationEnabled
        binding.vibration.isEnabled = state.notificationsEnabled
        binding.ringtone.summary = state.ringtoneName
        binding.ringtone.isEnabled = state.notificationsEnabled
    }

    override fun showRingtonePicker(default: Uri?) {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, default)
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
        ringtoneLauncher.launch(intent)
    }

}
