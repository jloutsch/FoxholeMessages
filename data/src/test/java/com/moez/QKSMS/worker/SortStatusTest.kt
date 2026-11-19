package dev.octoshrimpy.quik.worker

import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.octoshrimpy.quik.worker.ClassifyExistingMessagesWorker.Companion.statusOf
import dev.octoshrimpy.quik.worker.ClassifyExistingMessagesWorker.SortStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Fork issue #61: what the Message Sorting screen shows for each state of the sorting work. */
class SortStatusTest {

    private fun info(state: WorkInfo.State, output: Map<String, Int> = emptyMap(), progress: Map<String, Int> = emptyMap()) =
        WorkInfo(
            UUID.randomUUID(), state, emptySet(),
            workDataOf(*output.toList().toTypedArray()),
            workDataOf(*progress.toList().toTypedArray())
        )

    @Test
    fun noWorkIsIdle() = assertEquals(SortStatus.Idle, statusOf(null))

    @Test
    fun queuedShowsAsStarting() = assertEquals(SortStatus.Running(0, 0), statusOf(info(WorkInfo.State.ENQUEUED)))

    @Test
    fun runningReportsItsProgress() = assertEquals(
        SortStatus.Running(500, 7000),
        statusOf(info(WorkInfo.State.RUNNING, progress = mapOf("done" to 500, "total" to 7000)))
    )

    @Test
    fun finishedReportsHowManyWereSorted() = assertEquals(
        SortStatus.Finished(7000),
        statusOf(info(WorkInfo.State.SUCCEEDED, output = mapOf("sorted" to 7000)))
    )

    @Test
    fun failedIsFailed() = assertEquals(SortStatus.Failed, statusOf(info(WorkInfo.State.FAILED)))

    @Test
    fun cancelledIsIdle() = assertEquals(SortStatus.Idle, statusOf(info(WorkInfo.State.CANCELLED)))
}
