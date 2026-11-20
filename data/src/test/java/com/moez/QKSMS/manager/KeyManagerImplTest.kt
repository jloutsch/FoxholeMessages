package dev.octoshrimpy.quik.manager

import dev.octoshrimpy.quik.db.inMemoryDatabase
import dev.octoshrimpy.quik.db.message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyManagerImplTest {

    private val db = inMemoryDatabase()
    private val keys = KeyManagerImpl(db)

    @After fun close() = db.close()

    @Test
    fun startsAboveTheHighestSavedId() {
        db.messageDao().upsert(listOf(message(100)))
        assertEquals(101L, keys.newId())
    }

    @Test
    fun aFailedSyncDoesNotLeaveTheCounterLow() {
        // A full sync resets the counter to 0 because it empties the tables. If it then fails, the
        // rollback brings the old rows back, and ids from 1 would overwrite them.
        db.messageDao().upsert(listOf(message(100)))
        keys.reset()
        keys.newId()
        keys.forget()
        assertEquals(101L, keys.newId())
    }
}
