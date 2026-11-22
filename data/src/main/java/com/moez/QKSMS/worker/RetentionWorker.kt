package dev.octoshrimpy.quik.worker

import kotlinx.coroutines.runBlocking
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.octoshrimpy.quik.common.util.extensions.jobScheduler
import dev.octoshrimpy.quik.interactor.DeleteOldMessages
import dev.octoshrimpy.quik.interactor.DeleteOldOtps
import dev.octoshrimpy.quik.interactor.Interactor
import dev.octoshrimpy.quik.interactor.PurgeTrash
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** The daily clean-ups: old messages (Auto-delete), old codes (OTP retention) and Trash older than 30 days. */
class RetentionWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {

    companion object {
        // Unique-work names WorkManager persists, so literals: never derive them from class names R8 renames
        const val AUTO_DELETE = "AutoDeleteWork"
        const val OTP_RETENTION = "OtpRetentionWork"
        const val PURGE_TRASH = "PurgeTrashWork"

        private const val INPUT_JOB = "job"

        // The JobScheduler ids used by the JobServices this replaced (2.6.2 and earlier)
        private val LEGACY_JOB_IDS = listOf(8120235, 8120236, 8120237)

        /** Runs [job] about once a day. Calling it again keeps the existing schedule. */
        // KEEP, not UPDATE: re-enqueueing with UPDATE on WorkManager 2.8.0 ran the work again on every
        // app start (checked on a phone). The request never changes, so there's nothing to update.
        fun schedule(context: Context, job: String) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setInputData(workDataOf(INPUT_JOB to job))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(job, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, job: String) {
            WorkManager.getInstance(context).cancelUniqueWork(job)
        }

        /** Removes the old JobScheduler jobs left from before the upgrade, so they don't run as well. */
        fun cancelLegacyJobs(context: Context) {
            val scheduler = context.jobScheduler
            LEGACY_JOB_IDS.forEach { id ->
                // WorkManager schedules through JobScheduler too, under its own ids: only cancel ours
                val service = scheduler.getPendingJob(id)?.service?.className ?: return@forEach
                if (service.startsWith("dev.octoshrimpy.quik.service.")) {
                    Timber.i("Cancelling legacy job $id ($service)")
                    scheduler.cancel(id)
                }
            }
        }
    }

    @Inject lateinit var deleteOldMessages: DeleteOldMessages
    @Inject lateinit var deleteOldOtps: DeleteOldOtps
    @Inject lateinit var purgeTrash: PurgeTrash

    // WorkManager only logs a doWork() exception to its own logcat output, which the in-app log never
    // sees, so failures are caught and logged here
    override fun doWork(): Result = try {
        val job = inputData.getString(INPUT_JOB)
        val interactor: Interactor<Unit> = when (job) {
            AUTO_DELETE -> deleteOldMessages
            OTP_RETENTION -> deleteOldOtps
            PURGE_TRASH -> purgeTrash
            else -> throw IllegalArgumentException("Unknown job: $job")
        }
        Timber.i("Running $job")
        // Waits here: execute() runs asynchronously and would be cut off when doWork() returns.
        // An error is rethrown, so the job reports failure.
        runBlocking { interactor.perform(Unit) }
        Result.success()
    } catch (e: Throwable) {
        Timber.e(e, "failed")
        Result.failure()
    }
}
