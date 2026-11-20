package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.db.FoxholeDatabase
import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.model.ScheduledMessage
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

class ScheduledMessageRepositoryImpl @Inject constructor(
    private val database: FoxholeDatabase
) : ScheduledMessageRepository {

    private val disposables = CompositeDisposable()

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
        val subscription = Completable.fromAction { dao.delete(id) }
            .subscribeOn(Schedulers.io()) // Run on a background thread and switch to main if needed
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Timber.v("Successfully deleted scheduled messages.")
            }, {
                Timber.e("Deleting scheduled messages failed.")
            })

        disposables.add(subscription)
    }

    override fun deleteScheduledMessages(ids: List<Long>) {
        ids.forEach { deleteScheduledMessage(it) }
    }

    override fun getAllScheduledMessageIdsSnapshot(): List<Long> = dao.all().map { it.id }
}
