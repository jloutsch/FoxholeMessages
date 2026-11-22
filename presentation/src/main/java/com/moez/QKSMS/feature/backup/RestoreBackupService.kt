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

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.common.util.extensions.getLabel
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.repository.BackupRepository
import timber.log.Timber
import javax.inject.Inject
import androidx.core.app.ServiceCompat


class RestoreBackupService : Service() {

    companion object {
        private const val NOTIFICATION_ID = -1

        private const val ACTION_START = "ACTION_START"
        private const val ACTION_STOP = "ACTION_STOP"
        private const val EXTRA_FILE_URI = "EXTRA_FILE_URI"

        fun start(context: Context, backupFile: Uri) {
            val intent = Intent(context, RestoreBackupService::class.java)
                .setAction("${context.packageName}.$ACTION_START")
                .putExtra("${context.packageName}.$EXTRA_FILE_URI", backupFile.toString())

            ContextCompat.startForegroundService(context, intent)
        }
    }

    @Inject lateinit var backupRepo: BackupRepository
    @Inject lateinit var notificationManager: NotificationManager

    private val notification by lazy { notificationManager.getNotificationForBackup() }

    override fun onCreate() = AndroidInjection.inject(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent.action) {
            "${baseContext.packageName}.${ACTION_START}" -> start(intent)
            "${baseContext.packageName}.${ACTION_STOP}" -> stop()
        }

        return START_STICKY
    }

    private fun start(intent: Intent) {
        val notificationManager = NotificationManagerCompat.from(this)

        startForeground(NOTIFICATION_ID, notification.build())

        @OptIn(FlowPreview::class) // sample is stable in practice; still marked preview
        backupRepo.getRestoreProgress()
            .sample(200)
            .onEach { progress ->
                when (progress) {
                    is BackupRepository.Progress.Idle -> stop()

                    is BackupRepository.Progress.Running -> notification
                        .setProgress(progress.max, progress.count, progress.indeterminate)
                        .setContentText(progress.getLabel(this))
                        .let { notificationManager.notify(NOTIFICATION_ID, it.build()) }

                    else -> notification
                        .setProgress(0, 0, progress.indeterminate)
                        .setContentText(progress.getLabel(this))
                        .let { notificationManager.notify(NOTIFICATION_ID, it.build()) }
                }
            }
            .launchIn(scope)

        // Start the restore
        scope.launch(Dispatchers.IO) {
            try {
                backupRepo.performRestore(Uri.parse(intent.getStringExtra("${baseContext.packageName}.$EXTRA_FILE_URI")))
            } catch (e: Exception) {
                Timber.w(e)
            }
        }
    }

    // Ends with the service
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

}