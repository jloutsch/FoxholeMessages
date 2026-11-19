package dev.octoshrimpy.quik.worker

import dev.octoshrimpy.quik.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Fork issue #50: the clean-up deleted everything in the cache folder older than two hours. */
class HousekeepingCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 10_000_000L
    private val twoHoursAgo = now - 2 * 60 * 60 * 1000

    private fun dir(name: String, modified: Long) = File(tmp.root, name).apply { mkdirs(); setLastModified(modified) }
    private fun file(name: String, modified: Long) = File(tmp.root, name).apply { writeText("x"); setLastModified(modified) }

    @Test
    fun onlyOldDelayCancelledFoldersAreDeleted() {
        val prefix = Constants.DELAY_CANCELLED_CACHED_ATTACHMENTS_FILE_PREFIX
        dir("${prefix}old", twoHoursAgo - 1)
        dir("${prefix}new", now)
        dir("image_manager_disk_cache", twoHoursAgo - 1)   // another library's cache
        file("${prefix}file", twoHoursAgo - 1)             // a file, not a folder
        file("photo.jpg", twoHoursAgo - 1)

        removeOrphanedComposeDelayCancelledAttachments(tmp.root, twoHoursAgo)

        assertEquals(
            setOf("${prefix}new", "image_manager_disk_cache", "${prefix}file", "photo.jpg"),
            tmp.root.list()!!.toSet()
        )
    }
}
