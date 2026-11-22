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
import dev.octoshrimpy.quik.common.base.QkView
import android.net.Uri

interface BackupView : QkView<BackupState> {
    fun setBackupLocationClicks(): Flow<Unit>
    fun restoreClicks(): Flow<Unit>

    fun backupClicks(): Flow<Unit>

    fun locationRationaleConfirmClicks(): Flow<Unit>
    fun locationRationaleCancelClicks(): Flow<Unit>

    fun selectedBackupErrorClicks(): Flow<Unit>

    fun confirmRestoreBackupConfirmClicks(): Flow<Unit>
    fun confirmRestoreBackupCancelClicks(): Flow<Unit>

    fun stopRestoreClicks(): Flow<Unit>
    fun stopRestoreConfirmed(): Flow<Unit>
    fun stopRestoreCancel(): Flow<Unit>

    fun documentTreeSelected(): Flow<Uri>
    fun documentSelected(): Flow<Uri>

    fun selectFolder(initialUri: Uri)
    fun selectFile(initialUri: Uri)
}
