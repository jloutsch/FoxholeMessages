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
package dev.octoshrimpy.quik.feature.backup

import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.clicks
import dev.octoshrimpy.quik.common.base.QkController
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.core.view.isVisible
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.QkActivityResultContracts
import dev.octoshrimpy.quik.common.util.extensions.getLabel
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setShowing
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.common.widget.PreferenceView
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.repository.BackupRepository
import dev.octoshrimpy.quik.databinding.BackupControllerBinding
import javax.inject.Inject

class BackupController : QkController<BackupControllerBinding, BackupView, BackupState, BackupPresenter>(), BackupView {

    @Inject override lateinit var presenter: BackupPresenter

    private val selectFolderCancelEvents = events<Unit>()
    private val selectFolderConfirmEvents = events<Unit>()

    private val restoreErrorConfirmEvents = events<Unit>()

    private val confirmRestoreCancelEvents = events<Unit>()
    private val confirmRestoreConfirmEvents = events<Unit>()

    private val stopRestoreConfirmEvents = events<Unit>()
    private val stopRestoreCancelEvents = events<Unit>()

    private val documentTreeSelectedEvents = events<Uri>()
    private val documentSelectedEvents = events<Uri>()

    private val stopRestoreDialog by lazy {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.backup_restore_stop_title)
                .setMessage(R.string.backup_restore_stop_message)
                .setPositiveButton(R.string.button_stop) { _, _ -> stopRestoreConfirmEvents.tryEmit(Unit) }
                .setNegativeButton(R.string.button_cancel) { _, _ -> stopRestoreCancelEvents.tryEmit(Unit) }
                .setCancelable(false)
                .create()
    }

    private val selectLocationRationaleDialog by lazy {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.backup_select_location_rationale_title)
                .setMessage(R.string.backup_select_location_rationale_message)
                .setPositiveButton(R.string.button_continue) { _, _ -> selectFolderConfirmEvents.tryEmit(Unit) }
                .setNegativeButton(R.string.button_cancel) { _, _ -> selectFolderCancelEvents.tryEmit(Unit) }
                .setCancelable(false)
                .create()
    }

    private val selectedBackupErrorDialog by lazy {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.backup_selected_backup_error_title)
                .setMessage(R.string.backup_selected_backup_error_message)
                .setPositiveButton(R.string.button_continue) { _, _ -> restoreErrorConfirmEvents.tryEmit(Unit) }
                .setCancelable(false)
                .create()
    }

    private val selectedBackupDetailsDialog by lazy {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.backup_selected_backup_details_title)
                .setPositiveButton(R.string.backup_restore_title) { _, _ -> confirmRestoreConfirmEvents.tryEmit(Unit) }
                .setNegativeButton(R.string.button_cancel) { _, _ -> confirmRestoreCancelEvents.tryEmit(Unit) }
                .setCancelable(false)
                .create()
    }

    private lateinit var openDirectory: ActivityResultLauncher<Uri?>
    private lateinit var openDocument: ActivityResultLauncher<QkActivityResultContracts.OpenDocumentParams>

    init {
        appComponent.inject(this)
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): BackupControllerBinding =
        BackupControllerBinding.inflate(inflater, container, false)

    override fun onContextAvailable(context: Context) {
        // Init activity result contracts
        openDirectory = themedActivity!!
            .registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let { documentTreeSelectedEvents.tryEmit(it) }
            }

        openDocument = themedActivity!!
            .registerForActivityResult(QkActivityResultContracts.OpenDocument()) { uri ->
                // The contract returns Uri.EMPTY when the picker is cancelled (#132)
                uri?.takeIf { it != Uri.EMPTY }?.let { documentSelectedEvents.tryEmit(it) }
            }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.backup_title)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()

        themedActivity?.colors?.theme()?.let { theme ->
            binding.progressBar.indeterminateTintList = ColorStateList.valueOf(theme.theme)
            binding.progressBar.progressTintList = ColorStateList.valueOf(theme.theme)
            binding.fab.setBackgroundTint(theme.theme)
            binding.fabIcon.setTint(theme.textPrimary)
            binding.fabLabel.setTextColor(theme.textPrimary)
        }

        // Make the list titles bold
        binding.linearLayout.children
            .mapNotNull { it as? PreferenceView }
            .map { it.titleView }
            .forEach { it.setTypeface(it.typeface, Typeface.BOLD) }
    }

    override fun render(state: BackupState) {
        when {
            state.backupProgress.running -> {
                binding.progressIcon.setImageResource(R.drawable.ic_file_upload_black_24dp)
                binding.progressTitle.setText(R.string.backup_backing_up)
                binding.progressSummary.text = state.backupProgress.getLabel(activity!!)
                binding.progressSummary.isVisible = binding.progressSummary.text.isNotEmpty()
                binding.progressCancel.isVisible = false
                val running = (state.backupProgress as? BackupRepository.Progress.Running)
                binding.progressBar.isVisible =
                    state.backupProgress.indeterminate || (running?.max ?: 0) > 0
                binding.progressBar.isIndeterminate = state.backupProgress.indeterminate
                binding.progressBar.max = running?.max ?: 0
                binding.progressBar.progress = running?.count ?: 0
                binding.progress.isVisible = true
                binding.fab.isVisible = false
            }

            state.restoreProgress.running -> {
                binding.progressIcon.setImageResource(R.drawable.ic_file_download_black_24dp)
                binding.progressTitle.setText(R.string.backup_restoring)
                binding.progressSummary.text = state.restoreProgress.getLabel(activity!!)
                binding.progressSummary.isVisible = binding.progressSummary.text.isNotEmpty()
                binding.progressCancel.isVisible = true
                val running = (state.restoreProgress as? BackupRepository.Progress.Running)
                binding.progressBar.isVisible =
                    state.restoreProgress.indeterminate || (running?.max ?: 0) > 0
                binding.progressBar.isIndeterminate = state.restoreProgress.indeterminate
                binding.progressBar.max = running?.max ?: 0
                binding.progressBar.progress = running?.count ?: 0
                binding.progress.isVisible = true
                binding.fab.isVisible = false
            }

            else -> {
                binding.progress.isVisible = false
                binding.fab.isVisible = true
            }
        }

        selectLocationRationaleDialog.setShowing(state.showLocationRationale)

        selectedBackupErrorDialog.setShowing(state.showSelectedBackupError)

        selectedBackupDetailsDialog.setMessage(state.selectedBackupDetails)
        selectedBackupDetailsDialog.setShowing(state.selectedBackupDetails != null)

        stopRestoreDialog.setShowing(state.showStopRestoreDialog)
    }

    override fun setBackupLocationClicks(): Flow<Unit> = binding.location.clicks()

    override fun restoreClicks(): Flow<Unit> = binding.restore.clicks()

    override fun locationRationaleConfirmClicks(): Flow<Unit> = selectFolderConfirmEvents

    override fun locationRationaleCancelClicks(): Flow<Unit> = selectFolderCancelEvents

    override fun selectedBackupErrorClicks(): Flow<Unit> = restoreErrorConfirmEvents

    override fun confirmRestoreBackupConfirmClicks(): Flow<Unit> = confirmRestoreConfirmEvents

    override fun confirmRestoreBackupCancelClicks(): Flow<Unit> = confirmRestoreCancelEvents

    override fun stopRestoreClicks(): Flow<Unit> = binding.progressCancel.clicks()

    override fun stopRestoreConfirmed(): Flow<Unit> = stopRestoreConfirmEvents

    override fun stopRestoreCancel(): Flow<Unit> = stopRestoreCancelEvents

    override fun backupClicks(): Flow<Unit> = binding.fab.clicks()

    override fun documentTreeSelected(): Flow<Uri> = documentTreeSelectedEvents

    override fun documentSelected(): Flow<Uri> = documentSelectedEvents

    override fun selectFolder(initialUri: Uri) {
        openDirectory.launch(initialUri)
    }

    override fun selectFile(initialUri: Uri) {
        openDocument.launch(QkActivityResultContracts.OpenDocumentParams(
                mimeTypes = listOf("application/json", "application/octet-stream"),
                initialUri = initialUri))
    }

}
