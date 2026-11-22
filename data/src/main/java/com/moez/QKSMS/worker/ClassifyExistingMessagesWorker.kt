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
package dev.octoshrimpy.quik.worker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import androidx.work.workDataOf
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.map
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.classifier.MessageCategoryBackfill
import dev.octoshrimpy.quik.interactor.DeleteOldOtps
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import javax.inject.Inject

class ClassifyExistingMessagesWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 4000
        // Literal rather than simpleName - see HousekeepingWorker for why.
        private const val WORKER_TAG = "ClassifyExistingMessagesWorker"

        private const val INPUT_FULL_RESORT = "fullResort"
        private const val PROGRESS_DONE = "done"
        private const val PROGRESS_TOTAL = "total"
        private const val RESULT_SORTED = "sorted"

        /** The latest sorting run, as the Message Sorting screen shows it. */
        fun observeStatus(context: Context): Flow<SortStatus> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORKER_TAG)
                .map { infos -> statusOf(infos.lastOrNull()) }

        fun statusOf(info: WorkInfo?): SortStatus = when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> SortStatus.Running(
                info.progress.getInt(PROGRESS_DONE, 0), info.progress.getInt(PROGRESS_TOTAL, 0)
            )
            WorkInfo.State.SUCCEEDED -> SortStatus.Finished(info.outputData.getInt(RESULT_SORTED, 0))
            WorkInfo.State.FAILED -> SortStatus.Failed
            WorkInfo.State.CANCELLED, null -> SortStatus.Idle
        }

        /** [fullResort]: re-sort every message, not only ones never sorted (see MessageCategoryBackfill.run). */
        fun trigger(context: Context, fullResort: Boolean = false) {
            val request = OneTimeWorkRequest.Builder(ClassifyExistingMessagesWorker::class.java)
                .addTag(WORKER_TAG)
                .setInputData(workDataOf(INPUT_FULL_RESORT to fullResort))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            // REPLACE, not KEEP: KEEP silently discards the new request whenever a previous one
            // under this name is still unfinished, and a run that was interrupted (or whose stored
            // worker class no longer resolves after an obfuscated rebuild) stays unfinished
            // indefinitely - which makes the button permanently dead with no feedback at all. This
            // is explicitly user-initiated, so the newest request should always be the one that runs.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORKER_TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }

    sealed class SortStatus {
        object Idle : SortStatus()
        /** [total] is 0 until the messages to sort have been counted. */
        data class Running(val done: Int, val total: Int) : SortStatus()
        data class Finished(val sorted: Int) : SortStatus()
        object Failed : SortStatus()
    }

    @Inject lateinit var backfill: MessageCategoryBackfill
    @Inject lateinit var deleteOldOtps: DeleteOldOtps
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var prefs: Preferences

    // Everything here is wrapped so failures reach the file log. WorkManager swallows exceptions
    // thrown out of doWork() into its own logcat output, which is invisible to anyone diagnosing
    // this from a device - it looks identical to the worker simply never finishing.
    override fun doWork(): Result = try {
        Timber.v("started")

        val notificationManagerCompat = NotificationManagerCompat.from(applicationContext)

        Timber.v("running backfill")
        var sorted = 0
        val total = backfill.run(fullResort = inputData.getBoolean(INPUT_FULL_RESORT, false)) { done, all ->
            sorted = all
            setProgressAsync(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to all))
        }

        if (total > 0) {
            notificationManagerCompat.cancel(NOTIFICATION_ID)
            Timber.v("backfill finished. processed $total messages")
        } else {
            Timber.v("backfill finished. nothing to process")
        }

        // The backfill can newly tag messages as OTPs that were never checked before, but OTP
        // retention otherwise only runs on its own daily schedule - so without this, asking for a
        // re-sort appears to do nothing at all for up to a day. Run synchronously rather than via
        // execute(), which subscribes asynchronously and would be cut off when doWork() returns.
        Timber.v("applying otp retention (retention set to ${prefs.otpRetentionDays.get()} days)")
        try {
            runBlocking { deleteOldOtps.perform(Unit) }
        } catch (error: Exception) {
            Timber.e(error, "otp retention failed")
        }

        Timber.v("finished")
        Result.success(workDataOf(RESULT_SORTED to sorted))
    } catch (e: Throwable) {
        Timber.e(e, "failed")
        Result.failure()
    }

    override fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notificationManager.getNotificationForClassification().build())

}
