/*
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.worker

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.android.mms.transaction.DownloadManager
import com.android.mms.transaction.PushReceiver
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Retries a picture-message (MMS) download that failed.
 *
 * A text arrives first as a small notice; the picture itself is then downloaded from the carrier.
 * If that download failed (no signal, mobile data off for a moment), nothing ever tried again: the
 * failure was only logged, and the download stayed marked as "in progress" for as long as the app
 * was running, so even the carrier re-sending the notice was ignored. The picture was lost with no
 * sign to the user.
 */
class MmsDownloadRetryWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    companion object {
        private const val INPUT_LOCATION = "location"
        private const val INPUT_URI = "uri"
        private const val INPUT_SUBSCRIPTION_ID = "subscriptionId"

        // String literals, not class names: R8 renames classes and WorkManager persists these.
        private const val UNIQUE_WORK_PREFIX = "mms-download-retry:"
        private const val PREFS = "mms_download_retries"

        /**
         * Delay before retry number [attempt] (1-based), or null once retries are used up. Spread out
         * so a short outage is covered quickly and a longer one still gets a later try.
         */
        fun delayMinutesFor(attempt: Int): Long? = when (attempt) {
            1 -> 1
            2 -> 10
            3 -> 60
            else -> null
        }

        /** Forget the in-progress markers so this download can be started again. */
        fun releaseDownload(location: String) {
            DownloadManager.finishDownload(location)
            PushReceiver.forgetDownload(location)
        }

        /** Called when a download failed: schedules the next retry, if any are left. */
        fun scheduleRetry(context: Context, location: String, notificationUri: String, subscriptionId: Int) {
            releaseDownload(location)

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val attempt = prefs.getInt(location, 0) + 1
            val delay = delayMinutesFor(attempt)
            if (delay == null) {
                Timber.w("MMS download still failing after ${attempt - 1} retries; giving up")
                prefs.edit().remove(location).apply()
                return
            }
            prefs.edit().putInt(location, attempt).apply()

            Timber.i("MMS download failed; retry $attempt in $delay min")
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_PREFIX + location,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MmsDownloadRetryWorker>()
                    .setInitialDelay(delay, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(
                        workDataOf(
                            INPUT_LOCATION to location,
                            INPUT_URI to notificationUri,
                            INPUT_SUBSCRIPTION_ID to subscriptionId
                        )
                    )
                    .build()
            )
        }

        /** Called when a download succeeded, so a later failure of the same message starts afresh. */
        fun clearRetries(context: Context, location: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(location).apply()
        }
    }

    override fun doWork(): Result {
        // doWork exceptions are otherwise invisible in the in-app log (CLAUDE.md, WorkManager traps).
        return try {
            val location = inputData.getString(INPUT_LOCATION) ?: return Result.failure()
            val uri = inputData.getString(INPUT_URI)?.let(Uri::parse) ?: return Result.failure()
            val subscriptionId = inputData.getInt(INPUT_SUBSCRIPTION_ID, -1)

            Timber.i("retrying MMS download")
            // The result arrives through the normal path (MmsReceivedReceiver -> ReceiveMmsWorker),
            // which schedules the next retry if this one fails too.
            DownloadManager.getInstance().downloadMultimediaMessage(applicationContext, location, uri, false, subscriptionId)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "MMS download retry failed to start")
            Result.failure()
        }
    }
}
