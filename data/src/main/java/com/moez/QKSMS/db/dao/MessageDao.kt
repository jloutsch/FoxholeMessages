package dev.octoshrimpy.quik.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import kotlinx.coroutines.flow.Flow

/**
 * Messages. An MMS message's parts are the MmsPart rows whose messageId is its contentId.
 * Box ids 0 and 1 are "all" and "inbox" for both SMS and MMS (Telephony constants).
 */
@Dao
interface MessageDao {

    companion object {
        private const val IN_THREAD = "threadId = :threadId AND isEmojiReaction = 0 AND deletedAt IS NULL"

        // Case-insensitive substring match on the text, or on any text part of an MMS (SQLite's
        // LIKE folds ASCII letters only, as Realm's Case.INSENSITIVE did for most text)
        private const val MATCHES = "(body LIKE '%' || :query || '%' OR (type = 'mms' AND EXISTS " +
                "(SELECT 1 FROM MmsPart p WHERE p.messageId = Message.contentId AND p.text LIKE '%' || :query || '%')))"
    }

    @Query("SELECT * FROM Message WHERE $IN_THREAD ORDER BY date") fun observeThread(threadId: Long): Flow<List<Message>>
    @Query("SELECT * FROM Message WHERE $IN_THREAD ORDER BY date") fun thread(threadId: Long): List<Message>
    @Query("SELECT * FROM Message WHERE $IN_THREAD AND $MATCHES ORDER BY date")
    fun observeThreadSearch(threadId: Long, query: String): Flow<List<Message>>
    @Query("SELECT * FROM Message WHERE $IN_THREAD AND $MATCHES ORDER BY date")
    fun threadSearch(threadId: Long, query: String): List<Message>
    @Query("SELECT * FROM Message WHERE deletedAt IS NULL AND $MATCHES") fun search(query: String): List<Message>

    @Query("SELECT * FROM Message WHERE id = :id") fun get(id: Long): Message?
    @Query("SELECT * FROM Message WHERE id IN (:ids)") fun get(ids: List<Long>): List<Message>
    @Query("SELECT * FROM Message WHERE type = :type AND contentId = :contentId LIMIT 1")
    fun getByProviderId(type: String, contentId: Long): Message?
    @Query("SELECT m.* FROM Message m JOIN MmsPart p ON p.messageId = m.contentId AND m.type = 'mms' WHERE p.id = :partId LIMIT 1")
    fun forPart(partId: Long): Message?
    @Query("SELECT MAX(id) FROM Message") fun maxId(): Long?

    @Query("SELECT * FROM Message WHERE threadId = :threadId AND deletedAt IS NULL AND boxId IN (0, 1) ORDER BY date DESC")
    fun observeLastIncoming(threadId: Long): Flow<List<Message>>

    // The newest message not in Trash: a conversation's last message
    @Query("SELECT * FROM Message WHERE threadId = :threadId AND deletedAt IS NULL ORDER BY date DESC LIMIT 1")
    fun latestVisible(threadId: Long): Message?
    @Query("SELECT * FROM Message WHERE threadId = :threadId AND deletedAt IS NULL ORDER BY date DESC")
    fun visibleNewestFirst(threadId: Long): List<Message>
    @Query("SELECT * FROM Message WHERE threadId = :threadId ORDER BY date DESC LIMIT 1")
    fun latest(threadId: Long): Message?
    @Query("SELECT COUNT(*) FROM Message WHERE threadId = :threadId AND deletedAt IS NULL AND date > :since")
    fun countSince(threadId: Long, since: Long): Long

    // Unread
    @Query("SELECT COUNT(*) FROM Conversation c JOIN Message m ON m.id = c.lastMessageId " +
            "WHERE c.archived = 0 AND c.blocked = 0 AND m.read = 0")
    fun unreadConversationCount(): Long
    @Query("SELECT COUNT(*) FROM Message WHERE threadId = :threadId AND read = 0 AND deletedAt IS NULL")
    fun unreadCount(threadId: Long): Long
    @Query("SELECT * FROM Message WHERE threadId = :threadId AND read = 0 AND deletedAt IS NULL ORDER BY date")
    fun unread(threadId: Long): List<Message>
    @Query("SELECT * FROM Message WHERE threadId = :threadId AND read = 0 AND deletedAt IS NULL ORDER BY date")
    fun observeUnread(threadId: Long): Flow<List<Message>>
    @Query("SELECT * FROM Message WHERE threadId = :threadId AND seen = 0 AND read = 0 AND deletedAt IS NULL ORDER BY date")
    fun unreadUnseen(threadId: Long): List<Message>

    // Seen and read flags
    @Query("SELECT DISTINCT threadId FROM Message WHERE seen = 0") fun threadsWithUnseen(): List<Long>
    @Query("UPDATE Message SET seen = 1 WHERE seen = 0") fun markAllSeen(): Int
    @Query("UPDATE Message SET seen = 1 WHERE seen = 0 AND threadId IN (:threadIds)") fun markSeen(threadIds: List<Long>): Int
    @Query("UPDATE Message SET seen = 1, read = 1 WHERE threadId IN (:threadIds) AND (read = 0 OR seen = 0)")
    fun markRead(threadIds: List<Long>): Int
    @Query("UPDATE Message SET read = 0 WHERE read = 1 AND id IN (SELECT lastMessageId FROM Conversation WHERE id IN (:threadIds))")
    fun markLastUnread(threadIds: List<Long>): Int

    // Trash
    @Query("UPDATE Message SET deletedAt = :at WHERE id IN (:ids)") fun setDeletedAt(ids: List<Long>, at: Long?)
    @Query("UPDATE Message SET deletedAt = :at WHERE deletedAt IS NULL AND threadId IN (:threadIds)")
    fun trashThreads(threadIds: List<Long>, at: Long)
    @Query("SELECT * FROM Message WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun observeTrash(): Flow<List<Message>>
    @Query("SELECT * FROM Message WHERE deletedAt IS NOT NULL AND deletedAt < :before") fun trashedBefore(before: Long): List<Message>

    // Retention and maintenance
    @Query("SELECT * FROM Message WHERE date < :before") fun olderThan(before: Long): List<Message>
    @Query("SELECT * FROM Message WHERE isOtp = 1 AND isStarred = 0 AND locked = 0 AND date < :before")
    fun otpOlderThan(before: Long): List<Message>
    @Query("SELECT * FROM Message WHERE deletedAt IS NULL ORDER BY id") fun allVisibleById(): List<Message>
    @Query("SELECT * FROM Message") fun all(): List<Message>
    @Query("SELECT * FROM Message WHERE category IS NULL") fun uncategorized(): List<Message>
    @Query("SELECT * FROM Message WHERE isOtp = 0") fun notOtp(): List<Message>
    @Query("SELECT * FROM Message ORDER BY date") fun allOldestFirst(): List<Message>
    @Query("SELECT * FROM Message WHERE threadId IN (:threadIds) ORDER BY date") fun inThreadsOldestFirst(threadIds: List<Long>): List<Message>
    @Query("SELECT DISTINCT threadId FROM Message WHERE boxId IN (2, 4, 5, 6)") fun threadsWithSent(): List<Long>
    @Query("SELECT DISTINCT threadId FROM Message WHERE boxId IN (2, 4, 5, 6) AND threadId IN (:threadIds)")
    fun threadsWithSent(threadIds: List<Long>): List<Long>

    // Starred
    @Query("SELECT * FROM Message WHERE isStarred = 1 AND deletedAt IS NULL ORDER BY date DESC") fun observeStarred(): Flow<List<Message>>
    @Query("SELECT COUNT(*) FROM Message WHERE isStarred = 1 AND read = 0 AND deletedAt IS NULL") fun unreadStarredCount(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(message: Message)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(messages: List<Message>)
    @Update fun update(message: Message)
    @Update fun update(messages: List<Message>)
    @Query("DELETE FROM Message WHERE id IN (:ids)") fun delete(ids: List<Long>)
    @Query("DELETE FROM Message") fun clear()
}

@Dao
interface MmsPartDao {
    @Query("SELECT * FROM MmsPart WHERE id = :id") fun get(id: Long): MmsPart?
    @Query("SELECT * FROM MmsPart WHERE messageId IN (:contentIds) ORDER BY seq") fun forMessages(contentIds: List<Long>): List<MmsPart>
    @Query("SELECT * FROM MmsPart WHERE messageId = :contentId ORDER BY seq") fun forMessage(contentId: Long): List<MmsPart>
    // A thread's parts in one query (no IN list, which old SQLite caps at 999 values)
    @Query("SELECT p.* FROM MmsPart p JOIN Message m ON m.contentId = p.messageId AND m.type = 'mms' WHERE m.threadId = :threadId")
    fun forThread(threadId: Long): List<MmsPart>
    // The conversation's gallery: pictures and videos of messages not in Trash, newest first
    @Query("SELECT p.* FROM MmsPart p JOIN Message m ON m.contentId = p.messageId AND m.type = 'mms' " +
            "WHERE m.threadId = :threadId AND m.deletedAt IS NULL AND (p.type LIKE '%image/%' OR p.type LIKE '%video/%') ORDER BY p.id DESC")
    fun observeMediaForThread(threadId: Long): Flow<List<MmsPart>>
    // One message's pictures and videos, in order, for paging in the gallery
    @Query("SELECT * FROM MmsPart WHERE messageId = :contentId AND (type LIKE '%image/%' OR type LIKE '%video/%') ORDER BY id")
    fun mediaForMessage(contentId: Long): List<MmsPart>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(parts: List<MmsPart>)
    @Query("DELETE FROM MmsPart WHERE messageId IN (:contentIds)") fun deleteForMessages(contentIds: List<Long>)
    @Query("DELETE FROM MmsPart") fun clear()
}

@Dao
interface EmojiReactionDao {
    @Query("SELECT * FROM EmojiReaction WHERE targetMessageId IN (:messageIds)") fun forTargets(messageIds: List<Long>): List<EmojiReaction>
    @Query("SELECT * FROM EmojiReaction WHERE threadId = :threadId") fun forThread(threadId: Long): List<EmojiReaction>
    @Query("SELECT * FROM EmojiReaction WHERE reactionMessageId = :messageId LIMIT 1") fun forReactionMessage(messageId: Long): EmojiReaction?
    @Query("SELECT * FROM EmojiReaction WHERE reactionMessageId IN (:messageIds)") fun forReactionMessages(messageIds: List<Long>): List<EmojiReaction>
    @Query("SELECT MAX(id) FROM EmojiReaction") fun maxId(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(reaction: EmojiReaction)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(reactions: List<EmojiReaction>)
    @Query("DELETE FROM EmojiReaction WHERE id IN (:ids)") fun delete(ids: List<Long>)
    @Query("DELETE FROM EmojiReaction") fun clear()
}
