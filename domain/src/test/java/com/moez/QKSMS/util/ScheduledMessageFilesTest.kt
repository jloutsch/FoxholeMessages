package dev.octoshrimpy.quik.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #2: the daily clean-up deleted every scheduled message's attachments. */
class ScheduledMessageFilesTest {

    @Test
    fun savedFolderIsReadBackAsItsOwnId() =
        assertEquals(123L, ScheduledMessageFiles.idFromDirName(ScheduledMessageFiles.dirName(123)))

    @Test
    fun foldersSavedByOlderVersionsAreStillRecognised() =
        // Older builds saved "scheduled--123"; reading that as -123 made the clean-up delete it.
        assertEquals(123L, ScheduledMessageFiles.idFromDirName("scheduled--123"))

    @Test
    fun deletingCoversBothNamingStyles() =
        assertTrue(ScheduledMessageFiles.dirNames(7).containsAll(listOf("scheduled-7", "scheduled--7")))

    @Test
    fun unreadableNamesAreNotTreatedAsLeftovers() {
        assertNull(ScheduledMessageFiles.idFromDirName("scheduled-"))
        assertNull(ScheduledMessageFiles.idFromDirName("scheduled-abc"))
        assertNull(ScheduledMessageFiles.idFromDirName("scheduled---5"))
        assertNull(ScheduledMessageFiles.idFromDirName("other-5"))
    }
}
