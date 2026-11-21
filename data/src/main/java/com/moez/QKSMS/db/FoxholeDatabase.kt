package dev.octoshrimpy.quik.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.octoshrimpy.quik.db.dao.BlockedNumberDao
import dev.octoshrimpy.quik.db.dao.ContactDao
import dev.octoshrimpy.quik.db.dao.ConversationDao
import dev.octoshrimpy.quik.db.dao.EmojiReactionDao
import dev.octoshrimpy.quik.db.dao.EmojiSyncNeededDao
import dev.octoshrimpy.quik.db.dao.MessageContentFilterDao
import dev.octoshrimpy.quik.db.dao.MessageDao
import dev.octoshrimpy.quik.db.dao.MmsPartDao
import dev.octoshrimpy.quik.db.dao.RecipientDao
import dev.octoshrimpy.quik.db.dao.ScheduledMessageDao
import dev.octoshrimpy.quik.db.dao.SenderCategoryRuleDao
import dev.octoshrimpy.quik.db.dao.SyncLogDao
import dev.octoshrimpy.quik.db.dao.TrustedSenderDao
import dev.octoshrimpy.quik.db.entity.ContactGroupMember
import dev.octoshrimpy.quik.db.entity.ConversationRecipient
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.EmojiSyncNeeded
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.PhoneNumber
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.ScheduledMessage
import dev.octoshrimpy.quik.model.SenderCategoryRule
import dev.octoshrimpy.quik.model.SyncLog
import dev.octoshrimpy.quik.model.TrustedSender

/**
 * The app's Room database, replacing Realm (#44). Every schema change needs a new version, a
 * migration (an AutoMigration where Room can work it out) and a case in MigrationTest; the
 * exported schema for each version is committed in data/schemas.
 */
@Database(
    entities = [
        Message::class, MmsPart::class, EmojiReaction::class,
        Conversation::class, ConversationRecipient::class, Recipient::class,
        Contact::class, PhoneNumber::class, ContactGroup::class, ContactGroupMember::class,
        ScheduledMessage::class, BlockedNumber::class, TrustedSender::class, SenderCategoryRule::class,
        MessageContentFilter::class, SyncLog::class, EmojiSyncNeeded::class,
    ],
    version = 2,
    exportSchema = true,
    // Each released version must upgrade to the current one; MigrationTest checks every step.
    // 1 → 2 (#84): index Message(type, contentId).
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
@TypeConverters(StringListConverter::class)
abstract class FoxholeDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun mmsPartDao(): MmsPartDao
    abstract fun emojiReactionDao(): EmojiReactionDao
    abstract fun conversationDao(): ConversationDao
    abstract fun recipientDao(): RecipientDao
    abstract fun contactDao(): ContactDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun trustedSenderDao(): TrustedSenderDao
    abstract fun senderCategoryRuleDao(): SenderCategoryRuleDao
    abstract fun messageContentFilterDao(): MessageContentFilterDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun emojiSyncNeededDao(): EmojiSyncNeededDao
}
