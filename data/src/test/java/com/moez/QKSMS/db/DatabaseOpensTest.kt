package dev.octoshrimpy.quik.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room migration Task 6: the Room database builds and opens in a plain unit test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseOpensTest {

    @Test
    fun opensAndCloses() {
        val database = inMemoryDatabase()
        assertEquals(1, database.openHelper.readableDatabase.version)
        database.close()
        assertTrue(!database.isOpen)
    }
}
