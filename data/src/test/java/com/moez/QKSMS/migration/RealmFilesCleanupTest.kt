package dev.octoshrimpy.quik.migration

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Room migration Task 13: the old Realm files go only once their data was imported. */
class RealmFilesCleanupTest {

    @get:Rule val temp = TemporaryFolder()

    private fun oldRealmFiles(dir: File) {
        File(dir, "default.realm").writeText("x")
        File(dir, "default.realm.lock").writeText("x")
        // Realm's note file is a named pipe, which Kotlin's deleteRecursively() skips
        ProcessBuilder("mkfifo", File(dir, "default.realm.note").path).start().waitFor()
        File(dir, "default.realm.management").apply { mkdirs(); File(this, "inner").writeText("x") }
        File(dir, ".realm.temp").mkdirs()
        File(dir, "keep-me.txt").writeText("x")
    }

    @Test
    fun deletedOnceImported() {
        val dir = temp.newFolder()
        oldRealmFiles(dir)
        RealmFilesCleanup.deleteIfImported(dir, imported = true)
        assertEquals(listOf("keep-me.txt"), dir.list()!!.sorted())
    }

    @Test
    fun keptUntilImported() {
        val dir = temp.newFolder()
        oldRealmFiles(dir)
        RealmFilesCleanup.deleteIfImported(dir, imported = false)
        assertEquals(6, dir.list()!!.size)
    }
}
