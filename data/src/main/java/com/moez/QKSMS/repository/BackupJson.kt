package dev.octoshrimpy.quik.repository

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.Backup
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupMessage
import okio.BufferedSink
import okio.BufferedSource

/**
 * Reads and writes a backup file one message at a time, so a backup holding hundreds of MB of
 * photos never has to fit in memory at once (building it whole ran the app out of memory).
 *
 * The file is the same [Backup] JSON as before: writing produces exactly what
 * `moshi.adapter(Backup::class.java).indent("\t")` produced for the whole file, and reading
 * accepts anything that adapter accepted. Each message still goes through Moshi's own
 * [BackupMessage] adapter, so its keys and its defaults for missing fields are unchanged.
 * BackupJsonTest pins both directions.
 */
internal class BackupJson(moshi: Moshi) {

    private val messageAdapter = moshi.adapter(BackupMessage::class.java)

    /** Writes the whole file, pulling each message from [messages] only when it's written. */
    fun write(sink: BufferedSink, messageCount: Int, messages: Sequence<BackupMessage>) {
        val writer = JsonWriter.of(sink)
        writer.indent = "\t"
        writer.beginObject()
        writer.name(KEY_MESSAGE_COUNT).value(messageCount.toLong())
        writer.name(KEY_MESSAGES).beginArray()
        messages.forEach { message -> messageAdapter.toJson(writer, message) }
        writer.endArray()
        writer.endObject()
        writer.flush()
    }

    /**
     * Calls [onMessage] for each message as soon as it's parsed, with its position and the
     * file's stated message count (0 if the file doesn't state one before its messages). Return
     * false from [onMessage] to stop reading. Unknown top-level keys are skipped, as before.
     * Throws on malformed JSON, after the messages before the bad spot have been handed over.
     */
    fun read(source: BufferedSource, onMessage: (message: BackupMessage, index: Int, messageCount: Int) -> Boolean) {
        val reader = JsonReader.of(source)
        var messageCount = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                KEY_MESSAGE_COUNT -> messageCount = reader.nextInt()
                KEY_MESSAGES -> {
                    reader.beginArray()
                    var index = 0
                    while (reader.hasNext()) {
                        val message = messageAdapter.fromJson(reader)
                                ?: throw JsonDataException("Null message at ${reader.path}")
                        if (!onMessage(message, index++, messageCount)) return
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private companion object {
        // Property names of [Backup], which the whole-file adapter used as its keys.
        const val KEY_MESSAGE_COUNT = "messageCount"
        const val KEY_MESSAGES = "messages"
    }
}
