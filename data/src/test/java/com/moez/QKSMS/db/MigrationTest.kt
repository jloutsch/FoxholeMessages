package dev.octoshrimpy.quik.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrades from each released Room schema (data/schemas) and checks the data survives and the
 * result matches what this build expects. Add a case for every new database version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FoxholeDatabase::class.java)

    @Test
    fun version1To2KeepsMessagesAndAddsTheProviderIndex() {
        // Version 1 is what 2.8.0 and 2.9.0 shipped
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO Message (id, threadId, contentId, address, boxId, type, date, dateSent, seen, read, " +
                    "locked, subId, body, errorCode, deliveryStatus, attachmentTypeString, mmsDeliveryStatusString, " +
                    "readReportString, errorType, messageSize, messageType, mmsStatus, subject, textContentType, " +
                    "isEmojiReaction, sendAsGroup, category, isOtp, isStarred, deletedAt) VALUES (1, 2, 42, '5551', 1, " +
                    "'sms', 10, 10, 1, 1, 0, -1, 'hello', 0, -1, 'NOT_LOADED', '', '', 0, 0, 0, 0, '', '', 0, 0, " +
                    "'PERSONAL', 0, 1, NULL)"
            )
            close()
        }

        // Also checks the upgraded tables match this build's schema exactly
        val db = helper.runMigrationsAndValidate(NAME, 2, true)

        db.query("SELECT body, isStarred, category FROM Message WHERE type = 'sms' AND contentId = 42").use { row ->
            assertTrue("message lost in the upgrade", row.moveToFirst())
            assertEquals("hello", row.getString(0))
            assertEquals(1, row.getInt(1))
            assertEquals("PERSONAL", row.getString(2))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_Message_type_contentId'").use { row ->
            assertTrue("index for finding a message by its phone-store id is missing", row.moveToFirst())
        }
    }

    private companion object {
        const val NAME = "migration-test"
    }
}
