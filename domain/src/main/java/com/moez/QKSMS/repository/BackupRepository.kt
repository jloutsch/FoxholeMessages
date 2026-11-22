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
package dev.octoshrimpy.quik.repository

import kotlinx.coroutines.flow.StateFlow
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.octoshrimpy.quik.model.BackupFile

interface BackupRepository {

    sealed class Progress(val running: Boolean = false, val indeterminate: Boolean = true) {
        class Idle : Progress()
        class Parsing : Progress(true)
        class Running(val max: Int, val count: Int) : Progress(true, false)
        class Saving : Progress(true)
        class Syncing : Progress(true)
        class Finished : Progress(true, false)
        class Failed : Progress(true, false)
    }

    fun getBackupDocumentTree(): DocumentFile?

    fun getBackupPathUriForPicker(): Uri

    fun persistBackupDirectory(directory: Uri)

    fun performBackup()

    fun getBackupProgress(): StateFlow<Progress>

    fun parseBackup(uri: Uri): BackupFile

    fun performRestore(uri: Uri)

    fun getRestoreProgress(): StateFlow<Progress>

    fun stopRestore()

}
