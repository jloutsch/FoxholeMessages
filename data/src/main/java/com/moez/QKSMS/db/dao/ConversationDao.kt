package dev.octoshrimpy.quik.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.octoshrimpy.quik.db.entity.ConversationRecipient
import dev.octoshrimpy.quik.model.Conversation
import kotlinx.coroutines.flow.Flow

/**
 * Conversations. A conversation's last message is the row [Conversation.lastMessageId] points at
 * (joined as `m`), which is how Realm's `lastMessage.*` sorts and filters translate.
 */
@Dao
interface ConversationDao {

    companion object {
        private const val FROM = "FROM Conversation c LEFT JOIN Message m ON m.id = c.lastMessageId"
        private const val HAS_RECIPIENTS =
            "EXISTS (SELECT 1 FROM ConversationRecipient cr WHERE cr.conversationId = c.id)"

        // The inbox: not the placeholder row 0, not blocked, has recipients, and has a message or a draft
        private const val LISTED = "c.id != 0 AND c.blocked = 0 AND $HAS_RECIPIENTS " +
                "AND (c.lastMessageId IS NOT NULL OR c.draft != '')"

        // Pinned first, then (optionally) unread first, then drafts, then newest
        private const val ORDER = "ORDER BY CASE WHEN :unreadAtTop THEN m.read ELSE 0 END ASC, " +
                "c.pinned DESC, c.draft DESC, m.date DESC"

        // Category tabs: a manual override wins; otherwise the last message's category. Personal
        // also takes everything that isn't Transactions or Promotions, including unsorted.
        private const val IN_CATEGORY = "(c.categoryOverride = :category " +
                "OR (c.categoryOverride IS NULL AND m.category = :category) " +
                "OR (:category = 'PERSONAL' AND c.categoryOverride IS NULL AND " +
                "(c.lastMessageId IS NULL OR m.category IS NULL OR m.category NOT IN ('TRANSACTIONAL', 'PROMOTIONAL'))))"
    }

    @Query("SELECT c.* $FROM WHERE $LISTED AND c.archived = :archived $ORDER")
    fun observeListed(archived: Boolean, unreadAtTop: Boolean): Flow<List<Conversation>>

    @Query("SELECT c.* $FROM WHERE $LISTED AND c.archived = :archived $ORDER")
    fun listed(archived: Boolean, unreadAtTop: Boolean): List<Conversation>

    @Query("SELECT c.* $FROM WHERE $LISTED AND c.archived = 0 AND $IN_CATEGORY $ORDER")
    fun observeInCategory(category: String, unreadAtTop: Boolean): Flow<List<Conversation>>

    @Query("SELECT COUNT(*) $FROM WHERE $LISTED AND c.archived = 0 AND $IN_CATEGORY AND m.read = 0")
    fun unreadCountInCategory(category: String): Long

    // Pinned, or a message in the last week; ranked by the caller
    @Query("SELECT c.* $FROM WHERE c.id != 0 AND c.lastMessageId IS NOT NULL AND c.archived = 0 " +
            "AND c.blocked = 0 AND $HAS_RECIPIENTS AND (c.pinned = 1 OR m.date > :since)")
    fun top(since: Long): List<Conversation>

    // Search candidates: everything listed except drafts-only, pinned first then newest
    @Query("SELECT c.* $FROM WHERE c.id != 0 AND c.lastMessageId IS NOT NULL AND c.blocked = 0 " +
            "AND $HAS_RECIPIENTS ORDER BY c.pinned DESC, m.date DESC")
    fun searchable(): List<Conversation>

    @Query("SELECT c.* $FROM WHERE c.blocked = 1 ORDER BY m.date DESC")
    fun blocked(): List<Conversation>

    @Query("SELECT c.* $FROM WHERE c.blocked = 1 ORDER BY m.date DESC")
    fun observeBlocked(): Flow<List<Conversation>>

    @Query("SELECT c.id $FROM WHERE c.id != 0 AND c.archived = :archived AND c.blocked = 0 AND m.seen = 0 ORDER BY m.date DESC")
    fun unseenIds(archived: Boolean): List<Long>

    // For the share sheet: the five newest unarchived conversations
    @Query("SELECT c.* $FROM WHERE c.id != 0 AND c.lastMessageId IS NOT NULL AND c.archived = 0 " +
            "AND c.blocked = 0 AND $HAS_RECIPIENTS ORDER BY m.date DESC LIMIT 5")
    fun observeRecent(): Flow<List<Conversation>>

    @Query("SELECT * FROM Conversation WHERE id = :id") fun get(id: Long): Conversation?
    @Query("SELECT * FROM Conversation WHERE id = :id") fun observe(id: Long): Flow<Conversation?>
    @Query("SELECT * FROM Conversation WHERE id IN (:ids)") fun get(ids: List<Long>): List<Conversation>
    @Query("SELECT * FROM Conversation") fun all(): List<Conversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(conversation: Conversation)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(conversations: List<Conversation>)
    @Update fun update(conversations: List<Conversation>)
    @Query("DELETE FROM Conversation") fun clear()

    // Recipients, in order
    @Query("SELECT * FROM ConversationRecipient WHERE conversationId IN (:conversationIds) ORDER BY position")
    fun recipientLinks(conversationIds: List<Long>): List<ConversationRecipient>
    @Query("DELETE FROM ConversationRecipient WHERE conversationId = :conversationId")
    fun clearRecipientLinks(conversationId: Long)
    @Query("DELETE FROM ConversationRecipient") fun clearAllRecipientLinks()
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertRecipientLinks(links: List<ConversationRecipient>)
}
