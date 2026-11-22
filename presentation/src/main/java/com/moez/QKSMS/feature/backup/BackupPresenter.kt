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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkPresenter
import android.net.Uri
import android.content.Context
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.common.util.extensions.makeToast
import dev.octoshrimpy.quik.interactor.PerformBackup
import dev.octoshrimpy.quik.repository.BackupRepository
import timber.log.Timber
import javax.inject.Inject

class BackupPresenter @Inject constructor(
    private val backupRepo: BackupRepository,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val performBackup: PerformBackup
) : QkPresenter<BackupView, BackupState>(BackupState()) {

    init {
        @OptIn(FlowPreview::class) // sample() is stable in practice; still marked preview
        backupRepo.getBackupProgress()
                .sample(16)
                .distinctUntilChanged()
                .collectIn(presenterScope) { progress -> newState { copy(backupProgress = progress) } }

        @OptIn(FlowPreview::class)
        backupRepo.getRestoreProgress()
                .sample(16)
                .distinctUntilChanged()
                .collectIn(presenterScope) { progress -> newState { copy(restoreProgress = progress) } }
    }

    override fun bindIntents(view: BackupView) {
        super.bindIntents(view)

        view.setBackupLocationClicks().collectIn(view.bindScope) {
            view.selectFolder(backupRepo.getBackupPathUriForPicker())
        }

        // The latest progress is already in the state (what withLatestFrom read)
        view.restoreClicks().collectIn(view.bindScope) {
            when {
                state.value.backupProgress.running -> context.makeToast(R.string.backup_restore_error_backup)
                state.value.restoreProgress.running -> context.makeToast(R.string.backup_restore_error_restore)
                else -> view.selectFile(backupRepo.getBackupPathUriForPicker())
            }
        }

        view.backupClicks().collectIn(view.bindScope) {
            when (backupRepo.getBackupDocumentTree()) {
                null -> newState { copy(showLocationRationale = true) }
                else -> performBackup.execute(Unit)
            }
        }

        view.locationRationaleConfirmClicks().collectIn(view.bindScope) {
            newState { copy(showLocationRationale = false) }
            view.selectFolder(backupRepo.getBackupPathUriForPicker())
        }

        view.locationRationaleCancelClicks().collectIn(view.bindScope) { newState { copy(showLocationRationale = false) } }

        view.selectedBackupErrorClicks().collectIn(view.bindScope) { newState { copy(showSelectedBackupError = false) } }

        // The backup file picked last, for the restore confirmation (what withLatestFrom read)
        var selectedBackup: Uri? = null

        view.confirmRestoreBackupConfirmClicks().collectIn(view.bindScope) {
            newState { copy(selectedBackupDetails = null) }
            selectedBackup?.let { backup -> RestoreBackupService.start(context, backup) }
        }

        view.confirmRestoreBackupCancelClicks().collectIn(view.bindScope) { newState { copy(selectedBackupDetails = null) } }

        view.stopRestoreClicks().collectIn(view.bindScope) { newState { copy(showStopRestoreDialog = true) } }

        view.stopRestoreConfirmed().collectIn(view.bindScope) {
            newState { copy(showStopRestoreDialog = false) }
            backupRepo.stopRestore()
        }

        view.stopRestoreCancel().collectIn(view.bindScope) { newState { copy(showStopRestoreDialog = false) } }

        view.documentTreeSelected().collectIn(view.bindScope) { uri -> backupRepo.persistBackupDirectory(uri) }

        view.documentSelected().collectIn(view.bindScope) { uri ->
            selectedBackup = uri
            try {
                val backupFile = withContext(Dispatchers.IO) { backupRepo.parseBackup(uri) }
                val date = dateFormatter.getDetailedTimestamp(backupFile.date)
                val details = context.getString(R.string.backup_details, date, backupFile.messages)
                newState { copy(selectedBackupDetails = details) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Couldn't read the selected backup file")
                newState { copy(showSelectedBackupError = true) }
            }
        }
    }

}