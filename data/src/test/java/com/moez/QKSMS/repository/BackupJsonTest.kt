package dev.octoshrimpy.quik.repository

import com.squareup.moshi.Moshi
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.Backup
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupMessage
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupPart
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Backups are streamed one message at a time (a whole backup in memory ran the app out of
 * memory). The streamed file must be exactly what the old whole-file Moshi adapter wrote, and the
 * streaming reader must accept what that adapter accepted, or old and new backups stop restoring.
 */
class BackupJsonTest {

    // Same configuration as AppModule.provideMoshi.
    private val moshi = Moshi.Builder().build() // as AppModule: generated adapters only, no reflection
    private val backupJson = BackupJson(moshi)
    private val sample = javaClass.getResource("/backup/backup-format-v1.json")!!.readText()

    private val sms = BackupMessage(
        type = 2, address = "+15550100101", date = 1700000000000, dateSent = 1699999999000,
        read = true, status = -1, body = "Sent \"quoted\" text\nwith a newline", protocol = 0,
        serviceCenter = null, locked = false, subId = 1
    )
    private val mms = BackupMessage(
        type = 1, address = "+15550100102", date = 1700000100000, dateSent = 0,
        read = false, status = 0, body = "", protocol = 0, serviceCenter = null,
        locked = true, subId = -1, isMms = true, mmsSubject = "Photos",
        mmsRecipients = listOf("+15550100102", "+15550100103"),
        mmsParts = listOf(
            BackupPart("text/plain", null, "Look at this", null),
            BackupPart("image/jpeg", "IMG_0001.jpg", null, "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgH")
        )
    )

    private fun write(messages: List<BackupMessage>): String {
        val sink = Buffer()
        backupJson.write(sink, messages.size, messages.asSequence())
        return sink.readUtf8()
    }

    private fun read(json: String): Pair<Int, List<BackupMessage>> {
        var count = -1
        val messages = mutableListOf<BackupMessage>()
        backupJson.read(Buffer().writeUtf8(json)) { message, _, messageCount ->
            count = messageCount
            messages += message
            true
        }
        return count to messages
    }

    @Test
    fun streamedFileIsByteForByteWhatTheWholeFileAdapterWrote() {
        val messages = listOf(sms, mms)
        val wholeFile = moshi.adapter(Backup::class.java).indent("\t").toJson(Backup(messages.size, messages))
        assertEquals(wholeFile, write(messages))
    }

    @Test
    fun streamedFileParsesBackWithTheExistingAdapter() {
        val messages = listOf(sms, mms)
        assertEquals(Backup(2, messages), moshi.adapter(Backup::class.java).fromJson(write(messages)))
    }

    @Test
    fun emptyBackupMatchesToo() =
        assertEquals(moshi.adapter(Backup::class.java).indent("\t").toJson(Backup(0, listOf())), write(listOf()))

    @Test
    fun writerPullsOneMessageAtATime() {
        // Each message must already be in the sink before the next one is asked for; that's what
        // keeps memory bounded to one message instead of the whole backup.
        val sink = Buffer()
        val sizesWhenAsked = mutableListOf<Long>()
        val messages = sequenceOf(sms, mms, sms).map { message -> sizesWhenAsked += sink.size; message }
        backupJson.write(sink, 3, messages)
        assertTrue("sizes $sizesWhenAsked", sizesWhenAsked[1] > sizesWhenAsked[0])
        assertTrue("sizes $sizesWhenAsked", sizesWhenAsked[2] > sizesWhenAsked[1])
    }

    @Test
    fun readerHandlesTheSavedSample() {
        val (count, messages) = read(sample)
        assertEquals(2, count)
        assertEquals(moshi.adapter(Backup::class.java).fromJson(sample)!!.messages, messages)
        assertEquals("/9j/4AAQSkZJRg==", messages[1].mmsParts[1].data)
    }

    @Test
    fun readerRoundTripsWhatTheWriterWrote() =
        assertEquals(2 to listOf(sms, mms), read(write(listOf(sms, mms))))

    @Test
    fun readerDefaultsMissingMmsFieldsAndSkipsUnknownKeys() {
        // Backups from before MMS support (v1.3.0) have no MMS fields. Keys in a different order
        // or unknown to this version must not fail either; the whole-file adapter allowed both.
        val old = """{"extra":{"a":[1,2]},"messages":[{"type":2,"address":"5550100","date":1,"dateSent":2,
            "read":true,"status":-1,"body":"old","protocol":0,"serviceCenter":null,"locked":false,
            "subId":-1,"futureField":"x"}],"messageCount":1}"""
        val (count, messages) = read(old)
        assertEquals(0, count) // the count came after the messages, so it wasn't known yet
        val message = messages.single()
        assertEquals(false, message.isMms)
        assertEquals(null, message.mmsSubject)
        assertEquals(emptyList<String>(), message.mmsRecipients)
        assertEquals(emptyList<BackupPart>(), message.mmsParts)
        assertEquals("old", message.body)
    }

    @Test
    fun readerStopsWhenAsked() {
        val seen = mutableListOf<Int>()
        backupJson.read(Buffer().writeUtf8(write(listOf(sms, mms, sms)))) { _, index, _ ->
            seen += index
            index < 1
        }
        assertEquals(listOf(0, 1), seen)
    }

    @Test
    fun truncatedFileHandsOverTheMessagesBeforeTheBreakThenThrows() {
        val full = write(listOf(sms, mms))
        val truncated = full.substring(0, full.indexOf("IMG_0001.jpg"))
        val messages = mutableListOf<BackupMessage>()
        try {
            backupJson.read(Buffer().writeUtf8(truncated)) { message, _, _ -> messages += message; true }
            fail("expected a parse error")
        } catch (expected: Exception) {
        }
        assertEquals(listOf(sms), messages)
    }
}
