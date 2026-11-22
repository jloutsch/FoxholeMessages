package dev.octoshrimpy.quik.repository

import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import dev.octoshrimpy.quik.db.FoxholeDatabase
import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.model.ScheduledMessage
import timber.log.Timber
import javax.inject.Inject

class ScheduledMessageRepositoryImpl @Inject constructor(
    private val database: FoxholeDatabase
) : ScheduledMessageRepository {

    // Lives as long as the app (a singleton); the deletes below run in it
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dao get() = database.scheduledMessageDao()

    override fun saveScheduledMessage(
        date: Long,
        subId: Int,
        recipients: List<String>,
        sendAsGroup: Boolean,
        body: String,
        attachments: List<String>,
        conversationId: Long
    ): ScheduledMessage {
        lateinit var message: ScheduledMessage
        database.runInTransaction {
            val id = (dao.maxId() ?: -1) + 1
            message = ScheduledMessage(id, date, subId, recipients, sendAsGroup, body, attachments, conversationId)
            dao.upsert(message)
        }
        return message
    }

    override fun updateScheduledMessage(scheduledMessage: ScheduledMessage) = dao.upsert(scheduledMessage)

    override fun getScheduledMessages(): List<ScheduledMessage> = dao.all()

    override fun observeScheduledMessages(): Flow<List<ScheduledMessage>> = dao.observeAll()

    override fun getScheduledMessage(id: Long): ScheduledMessage? = dao.get(id)

    override fun getScheduledMessagesForConversation(conversationId: Long): Flow<List<ScheduledMessage>> =
        dao.observeForConversation(conversationId)

    override fun deleteScheduledMessage(id: Long) {
        scope.launch {
            try {
                dao.delete(id)
                Timber.v("Successfully deleted scheduled messages.")
            } catch (e: Exception) {
                Timber.e(e, "Deleting scheduled messages failed.")
            }
        }
    }

    override fun deleteScheduledMessages(ids: List<Long>) {
        ids.forEach { deleteScheduledMessage(it) }
    }

    override fun getAllScheduledMessageIdsSnapshot(): List<Long> = dao.all().map { it.id }
}
