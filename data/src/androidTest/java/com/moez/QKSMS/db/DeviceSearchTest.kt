package dev.octoshrimpy.quik.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.octoshrimpy.quik.model.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search against the phone's own SQLite (#80), which could fold case differently from the one the
 * plain unit tests run on (stock SQLite folds A–Z only). Passed on the Note 10 (Android 12): accented
 * capitals match. Uses its own in-memory database, not the app's data.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSearchTest {

    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, FoxholeDatabase::class.java
    ).allowMainThreadQueries().build()

    @After fun close() = db.close()

    private fun message(id: Long, body: String) = Message().apply {
        this.id = id; threadId = 1; date = id; this.body = body; type = "sms"; contentId = id
    }

    private fun found(query: String) = db.messageDao().search(query).map { it.id }.sorted()

    @Test
    fun accentedLettersMatchWhateverTheirCase() {
        db.messageDao().upsert(listOf(message(1, "café au lait"), message(2, "Éclair recipe"), message(3, "ÉCOLE")))
        assertEquals(listOf(1L), found("CAFÉ"))
        assertEquals(listOf(1L), found("Café"))
        assertEquals(listOf(2L), found("éclair"))
        assertEquals(listOf(2L), found("ÉCLAIR"))
        assertEquals(listOf(3L), found("école"))
    }

    @Test
    fun asciiStillMatchesWhateverTheCase() {
        db.messageDao().upsert(listOf(message(1, "Hello World")))
        assertEquals(listOf(1L), found("hello WORLD"))
    }
}
