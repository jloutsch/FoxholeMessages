package dev.octoshrimpy.quik

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Receiving a text must never wait for, or depend on, the one-time Realm → Room import (#44): a
 * failed or slow import must not cost the user a message or its notification. This checks the
 * receive path's sources don't refer to the importer at all. (An on-device version can't write to
 * the SMS store: the test app isn't the default SMS app.)
 */
class ReceivePathIndependentOfImportTest {

    private val receivePath = listOf(
        "data/src/main/java/com/moez/QKSMS/worker/ReceiveSmsWorker.kt",
        "data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt",
        "presentation/src/main/java/com/moez/QKSMS/common/util/NotificationManagerImpl.kt",
    )

    @Test
    fun receivingNeverWaitsOnTheImport() {
        receivePath.forEach { path ->
            val code = File("..", path).readText()
            assertFalse("$path must not depend on the import", code.contains("RealmImporter") || code.contains("ImportState"))
        }
    }
}
