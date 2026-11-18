package dev.octoshrimpy.quik.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.Backup
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupMessage
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupMetadata
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupPart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A backup file is only useful if a *later* version of the app can read it. Backups made by
 * 2.2.0-2.3.0 can't be restored because the key names changed between builds without anyone
 * noticing. These tests pin the file format to a saved sample (src/test/resources/backup), so
 * renaming or retyping a field fails here instead of silently breaking every user's old backups.
 *
 * They run against unminified code, so they catch source changes; renames done by R8 in release
 * builds need a release-build check (see the shrunk-build CI step).
 */
class BackupFormatTest {

    // Same configuration as AppModule.provideMoshi.
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val sample = javaClass.getResource("/backup/backup-format-v1.json")!!.readText()

    private val expected = Backup(
        messageCount = 2,
        messages = listOf(
            BackupMessage(
                type = 1, address = "+15550100101", date = 1700000000000, dateSent = 1699999999000,
                read = true, status = -1, body = "Hello from an SMS backup", protocol = 0,
                serviceCenter = "+15550000000", locked = false, subId = 1
            ),
            BackupMessage(
                type = 1, address = "+15550100102", date = 1700000100000, dateSent = 0,
                read = false, status = 0, body = "", protocol = 0, serviceCenter = null,
                locked = true, subId = -1, isMms = true, mmsSubject = "Photos",
                mmsRecipients = listOf("+15550100102", "+15550100103"),
                mmsParts = listOf(
                    BackupPart("text/plain", null, "Look at this", null),
                    BackupPart("image/jpeg", "IMG_0001.jpg", null, "/9j/4AAQSkZJRg==")
                )
            )
        )
    )

    @Test
    fun savedSampleBackupStillRestores() =
        assertEquals(expected, moshi.adapter(Backup::class.java).fromJson(sample))

    @Test
    fun writingUsesExactlyTheSavedKeyNames() {
        val written = moshi.adapter(Backup::class.java).serializeNulls().toJson(expected)
        assertEquals(keyPaths(sample), keyPaths(written))
    }

    @Test
    fun backupRoundTrips() {
        val adapter = moshi.adapter(Backup::class.java)
        assertEquals(expected, adapter.fromJson(adapter.toJson(expected)))
    }

    @Test
    fun smsOnlyBackupFromBeforeMmsSupportStillRestores() {
        // Backups made before MMS support (v1.3.0) have no MMS fields; they must default, not fail.
        val old = """{"messageCount":1,"messages":[{"type":2,"address":"5550100","date":1,"dateSent":2,
            "read":true,"status":-1,"body":"old","protocol":0,"serviceCenter":null,"locked":false,"subId":-1}]}"""
        val message = moshi.adapter(Backup::class.java).fromJson(old)!!.messages.single()
        assertEquals(false, message.isMms)
        assertEquals(null, message.mmsSubject)
        assertEquals(emptyList<String>(), message.mmsRecipients)
        assertEquals(emptyList<BackupPart>(), message.mmsParts)
        assertEquals("old", message.body)
    }

    @Test
    fun restorePreviewReadsTheMessageCount() =
        assertEquals(2, moshi.adapter(BackupMetadata::class.java).fromJson(sample)!!.messageCount)

    /** Every key path in a JSON document, e.g. "messages[].mmsParts[].contentType". */
    @Suppress("UNCHECKED_CAST")
    private fun keyPaths(json: String): Set<String> {
        val out = sortedSetOf<String>()
        fun walk(node: Any?, path: String) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) -> "$path.$k".removePrefix(".").also { out += it; walk(v, it) } }
                is List<*> -> node.forEach { walk(it, "$path[]") }
            }
        }
        walk(moshi.adapter(Any::class.java).fromJson(json), "")
        return out
    }
}
