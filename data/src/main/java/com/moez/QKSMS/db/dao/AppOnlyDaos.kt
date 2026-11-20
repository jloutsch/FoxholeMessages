package dev.octoshrimpy.quik.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.EmojiSyncNeeded
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.ScheduledMessage
import dev.octoshrimpy.quik.model.SenderCategoryRule
import dev.octoshrimpy.quik.model.SyncLog
import dev.octoshrimpy.quik.model.TrustedSender
import kotlinx.coroutines.flow.Flow

// Tables that exist only in this app (nothing in Android's message or contact stores mirrors them).

@Dao
interface BlockedNumberDao {
    @Query("SELECT * FROM BlockedNumber") fun all(): List<BlockedNumber>
    @Query("SELECT * FROM BlockedNumber") fun observeAll(): Flow<List<BlockedNumber>>
    @Query("SELECT * FROM BlockedNumber WHERE id = :id") fun get(id: Long): BlockedNumber?
    @Query("SELECT MAX(id) FROM BlockedNumber") fun maxId(): Long?
    @Insert fun insert(numbers: List<BlockedNumber>)
    @Query("DELETE FROM BlockedNumber WHERE id IN (:ids)") fun delete(ids: List<Long>)
}

@Dao
interface MessageContentFilterDao {
    @Query("SELECT * FROM MessageContentFilter") fun all(): List<MessageContentFilter>
    @Query("SELECT * FROM MessageContentFilter") fun observeAll(): Flow<List<MessageContentFilter>>
    @Query("SELECT MAX(id) FROM MessageContentFilter") fun maxId(): Long?
    @Insert fun insert(filters: List<MessageContentFilter>)
    @Query("DELETE FROM MessageContentFilter WHERE id = :id") fun delete(id: Long)
}

@Dao
interface TrustedSenderDao {
    @Query("SELECT * FROM TrustedSender") fun all(): List<TrustedSender>
    @Query("SELECT * FROM TrustedSender") fun observeAll(): Flow<List<TrustedSender>>
    @Query("SELECT * FROM TrustedSender WHERE id = :id") fun get(id: Long): TrustedSender?
    @Query("SELECT MAX(id) FROM TrustedSender") fun maxId(): Long?
    @Insert fun insert(sender: TrustedSender)
    @Insert fun insert(senders: List<TrustedSender>)
    @Query("DELETE FROM TrustedSender WHERE id = :id") fun delete(id: Long)
}

@Dao
interface SenderCategoryRuleDao {
    @Query("SELECT * FROM SenderCategoryRule") fun all(): List<SenderCategoryRule>
    @Query("SELECT * FROM SenderCategoryRule") fun observeAll(): Flow<List<SenderCategoryRule>>
    @Query("SELECT MAX(id) FROM SenderCategoryRule") fun maxId(): Long?
    @Insert fun insert(rule: SenderCategoryRule)
    @Insert fun insert(rules: List<SenderCategoryRule>)
    @Query("DELETE FROM SenderCategoryRule WHERE id IN (:ids)") fun delete(ids: List<Long>)
}

@Dao
interface ScheduledMessageDao {
    @Query("SELECT * FROM ScheduledMessage ORDER BY date") fun all(): List<ScheduledMessage>
    @Query("SELECT * FROM ScheduledMessage ORDER BY date") fun observeAll(): Flow<List<ScheduledMessage>>
    @Query("SELECT * FROM ScheduledMessage WHERE conversationId = :conversationId")
    fun observeForConversation(conversationId: Long): Flow<List<ScheduledMessage>>
    @Query("SELECT * FROM ScheduledMessage WHERE id = :id") fun get(id: Long): ScheduledMessage?
    @Query("SELECT MAX(id) FROM ScheduledMessage") fun maxId(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(message: ScheduledMessage)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(messages: List<ScheduledMessage>)
    @Query("DELETE FROM ScheduledMessage WHERE id = :id") fun delete(id: Long)
}

@Dao
interface SyncLogDao {
    @Query("SELECT MAX(date) FROM SyncLog") fun lastSyncDate(): Long?
    @Insert fun insert(log: SyncLog)
}

@Dao
interface EmojiSyncNeededDao {
    @Query("SELECT COUNT(*) FROM EmojiSyncNeeded") fun count(): Int
    @Insert fun insert(row: EmojiSyncNeeded)
    @Query("DELETE FROM EmojiSyncNeeded") fun clear()
}
