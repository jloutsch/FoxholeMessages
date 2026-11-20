package dev.octoshrimpy.quik.migration

import dev.octoshrimpy.quik.db.inMemoryDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The importer empties Room before copying; a table it doesn't know about would keep stale rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImporterTablesTest {

    @Test
    fun importerClearsEveryTable() {
        val db = inMemoryDatabase()
        val tables = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList() }
            .filter { it !in setOf("android_metadata", "room_master_table", "sqlite_sequence") }
        db.close()
        assertEquals(tables.sorted(), RealmImporter.ALL_TABLES.sorted())
    }
}
