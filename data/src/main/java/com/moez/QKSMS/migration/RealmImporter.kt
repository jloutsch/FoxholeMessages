package dev.octoshrimpy.quik.migration

import android.content.Context
import com.f2prateek.rx.preferences2.Preference
import dev.octoshrimpy.quik.db.FoxholeDatabase
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.PhoneNumber
import dev.octoshrimpy.quik.model.ScheduledMessage
import dev.octoshrimpy.quik.model.SenderCategoryRule
import dev.octoshrimpy.quik.model.TrustedSender
import dev.octoshrimpy.quik.repository.SyncRepositoryImpl
import dev.octoshrimpy.quik.util.Preferences
import io.realm.DynamicRealm
import io.realm.DynamicRealmObject
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies what only this app knows from the old Realm file into Room, once (#44): stars, Trash,
 * sorting, code tags, pins, names, drafts, tab moves, blocking, filters, sender rules, scheduled
 * messages and chosen default numbers. Messages, conversations and contacts themselves are rebuilt
 * from the phone's own stores by the full sync, which re-applies the copied fields.
 *
 * The Realm file is never opened (a copy is read) and never changed or deleted, so a failed import
 * loses nothing: nothing is marked done, and the next launch tries again.
 */
@Singleton
class RealmImporter internal constructor(
    private val context: Context,
    private val database: FoxholeDatabase,
    private val realmDirectory: File,
    private val importedVersion: Preference<Int>,
    private val hasSmsPermission: () -> Boolean,
    private val rebuildFromProvider: () -> Unit,
) {

    @Inject constructor(
        context: Context,
        database: FoxholeDatabase,
        sync: SyncRepositoryImpl,
        prefs: Preferences,
        permissions: PermissionManager,
    ) : this(context, database, context.filesDir, prefs.roomImportVersion, permissions::hasReadSms, sync::syncMessagesNow)

    companion object {
        /** Raise to import again on every phone, e.g. after fixing a field that was missed. */
        const val IMPORTER_VERSION = 1

        /** The schema version the last Realm build left the file at. */
        private const val REALM_SCHEMA_VERSION = 18L

        // Every table in FoxholeDatabase; the import starts from empty
        internal val ALL_TABLES = listOf(
            "Message", "MmsPart", "EmojiReaction", "Conversation", "ConversationRecipient", "Recipient",
            "Contact", "PhoneNumber", "ContactGroup", "ContactGroupMember", "ScheduledMessage", "BlockedNumber",
            "TrustedSender", "SenderCategoryRule", "MessageContentFilter", "SyncLog", "EmojiSyncNeeded",
        )
    }

    private val _state = MutableStateFlow<ImportState>(ImportState.NotNeeded)
    val state: StateFlow<ImportState> = _state

    /** Runs the import if it's needed, on the calling thread (not the main thread), and returns the outcome. */
    @Synchronized
    fun importIfNeeded(): ImportState {
        val file = File(realmDirectory, "default.realm")
        if (!file.exists() || importedVersion.get() >= IMPORTER_VERSION) {
            return ImportState.NotNeeded.also { _state.value = it }
        }
        // The full sync rebuilds from the phone's message store and would carry nothing across
        // without read access, so wait for it rather than mark the import done.
        if (!hasSmsPermission()) {
            Timber.i("Room import waiting for SMS permission")
            return ImportState.Failed(SecurityException("No permission to read SMS yet")).also { _state.value = it }
        }

        _state.value = ImportState.Running
        val result = try {
            val old = readRealm(file)
            database.runInTransaction { writeSnapshot(old) }
            rebuildFromProvider()
            val counts = old.counts().mapValues { (table, realmCount) -> realmCount to roomCount(table) }
            counts.forEach { (table, pair) -> Timber.i("Room import $table: Realm ${pair.first}, Room ${pair.second}") }
            importedVersion.set(IMPORTER_VERSION)
            ImportState.Done(counts)
        } catch (e: Throwable) {
            Timber.e(e, "Room import failed; will retry at next launch")
            ImportState.Failed(e)
        }
        _state.value = result
        return result
    }

    /** Everything carried across, read from the old file. */
    private class OldData(
        val scheduled: List<ScheduledMessage>,
        val blockedNumbers: List<BlockedNumber>,
        val filters: List<MessageContentFilter>,
        val trustedSenders: List<TrustedSender>,
        val senderRules: List<SenderCategoryRule>,
        val messages: List<Message>,
        val conversations: List<Conversation>,
        val defaultNumbers: List<PhoneNumber>,
    ) {
        fun counts(): Map<String, Long> = mapOf(
            "ScheduledMessage" to scheduled.size.toLong(),
            "BlockedNumber" to blockedNumbers.size.toLong(),
            "MessageContentFilter" to filters.size.toLong(),
            "TrustedSender" to trustedSenders.size.toLong(),
            "SenderCategoryRule" to senderRules.size.toLong(),
            "trashed" to messages.count { it.deletedAt != null }.toLong(),
            "starred" to messages.count { it.isStarred }.toLong(),
            "sorted" to messages.count { it.category != null }.toLong(),
            "codes" to messages.count { it.isOtp }.toLong(),
            "archived" to conversations.count { it.archived }.toLong(),
            "pinned" to conversations.count { it.pinned }.toLong(),
            "blockedConversations" to conversations.count { it.blocked }.toLong(),
            "named" to conversations.count { it.name.isNotEmpty() }.toLong(),
            "tabMoves" to conversations.count { it.categoryOverride != null }.toLong(),
            "drafts" to conversations.count { it.draft.isNotEmpty() }.toLong(),
            "defaultNumbers" to defaultNumbers.size.toLong(),
        )
    }

    /** The same counts in Room, after the rebuild. */
    private fun roomCount(name: String): Long {
        val where = when (name) {
            "trashed" -> "Message WHERE deletedAt IS NOT NULL"
            "starred" -> "Message WHERE isStarred = 1"
            "sorted" -> "Message WHERE category IS NOT NULL"
            "codes" -> "Message WHERE isOtp = 1"
            "archived" -> "Conversation WHERE archived = 1"
            "pinned" -> "Conversation WHERE pinned = 1"
            "blockedConversations" -> "Conversation WHERE blocked = 1"
            "named" -> "Conversation WHERE name != ''"
            "tabMoves" -> "Conversation WHERE categoryOverride IS NOT NULL"
            "drafts" -> "Conversation WHERE draft != ''"
            "defaultNumbers" -> "PhoneNumber WHERE isDefault = 1"
            else -> name
        }
        return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $where").use { it.moveToFirst(); it.getLong(0) }
    }

    /**
     * Reads a copy of [file], so the original is never opened: Realm only opens bundled files
     * read-only, and opening the original writable could change it.
     */
    private fun readRealm(file: File): OldData {
        val copyDir = File(context.cacheDir, "realm-import").apply { deleteRecursively(); mkdirs() }
        try {
            file.copyTo(File(copyDir, file.name))
            return readRealmCopy(File(copyDir, file.name))
        } finally {
            copyDir.deleteRecursively()
        }
    }

    private fun readRealmCopy(file: File): OldData {
        Realm.init(context)
        val config = RealmConfiguration.Builder()
            .directory(file.parentFile!!)
            .name(file.name)
            .schemaVersion(REALM_SCHEMA_VERSION)
            .build()
        return DynamicRealm.getInstance(config).use { realm ->
            check(realm.version == REALM_SCHEMA_VERSION) { "Realm file is at schema ${realm.version}, expected $REALM_SCHEMA_VERSION" }
            OldData(
                scheduled = realm.all("ScheduledMessage").map { o ->
                    ScheduledMessage(
                        id = o.getLong("id"), date = o.getLong("date"), subId = o.getLong("subId").toInt(),
                        recipients = o.getList("recipients", String::class.java).toList(),
                        sendAsGroup = o.getBoolean("sendAsGroup"), body = o.getString("body"),
                        attachments = o.getList("attachments", String::class.java).toList(),
                        conversationId = o.getLong("conversationId"),
                    )
                },
                blockedNumbers = realm.all("BlockedNumber").map { o -> BlockedNumber(o.getLong("id"), o.getString("address")) },
                filters = realm.all("MessageContentFilter").map { o ->
                    MessageContentFilter(o.getLong("id"), o.getString("value"), o.getBoolean("caseSensitive"),
                        o.getBoolean("isRegex"), o.getBoolean("includeContacts"))
                },
                trustedSenders = realm.all("TrustedSender").map { o ->
                    TrustedSender(o.getLong("id"), o.getString("address"), o.getBoolean("locked"))
                },
                senderRules = realm.all("SenderCategoryRule").map { o ->
                    SenderCategoryRule(o.getLong("id"), o.getString("address"), o.getString("category"))
                },
                // Only messages carrying something app-only, and only ones the phone's store knows
                // (contentId), since that's how the full sync matches them up again
                messages = realm.all("Message").filter { o ->
                    o.getLong("contentId") != 0L && (!o.isNull("deletedAt") || o.getBoolean("isStarred") ||
                        !o.isNull("category") || o.getBoolean("isOtp"))
                }.map { o ->
                    Message().apply {
                        id = o.getLong("id"); type = o.getString("type"); contentId = o.getLong("contentId")
                        deletedAt = if (o.isNull("deletedAt")) null else o.getLong("deletedAt")
                        isStarred = o.getBoolean("isStarred"); category = o.getString("category"); isOtp = o.getBoolean("isOtp")
                    }
                },
                conversations = realm.all("Conversation").map { o ->
                    Conversation(
                        id = o.getLong("id"), archived = o.getBoolean("archived"), blocked = o.getBoolean("blocked"),
                        pinned = o.getBoolean("pinned"), draft = o.getString("draft"), draftDate = o.getLong("draftDate"),
                        blockingClient = if (o.isNull("blockingClient")) null else o.getInt("blockingClient"),
                        blockReason = o.getString("blockReason"), name = o.getString("name"),
                        sendAsGroup = o.getBoolean("sendAsGroup"), categoryOverride = o.getString("categoryOverride"),
                    )
                },
                defaultNumbers = realm.all("PhoneNumber").filter { o -> o.getBoolean("isDefault") }
                    .map { o -> PhoneNumber(id = o.getLong("id"), isDefault = true) },
            )
        }
    }

    private fun DynamicRealm.all(table: String): List<DynamicRealmObject> = where(table).findAll().toList()

    /**
     * Replaces Room's contents with the old data. Messages and conversations go in as bare rows
     * carrying only their app-only fields: the full sync reads those back by provider identity and
     * re-applies them to the rows it rebuilds, the same way it keeps them across every sync.
     */
    private fun writeSnapshot(old: OldData) {
        // Table by table: Room's clearAllTables() can't run inside a transaction
        val sql = database.openHelper.writableDatabase
        ALL_TABLES.forEach { table -> sql.execSQL("DELETE FROM `$table`") }
        database.scheduledMessageDao().upsert(old.scheduled)
        database.blockedNumberDao().insert(old.blockedNumbers)
        database.messageContentFilterDao().insert(old.filters)
        database.trustedSenderDao().insert(old.trustedSenders)
        database.senderCategoryRuleDao().insert(old.senderRules)
        database.messageDao().upsert(old.messages)
        database.conversationDao().upsert(old.conversations)
        database.contactDao().upsertNumbers(old.defaultNumbers)
    }
}
