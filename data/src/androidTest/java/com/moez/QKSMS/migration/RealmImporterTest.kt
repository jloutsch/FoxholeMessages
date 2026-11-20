package dev.octoshrimpy.quik.migration

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.f2prateek.rx.preferences2.RxSharedPreferences
import dev.octoshrimpy.quik.db.FoxholeDatabase
import io.realm.DynamicRealm
import io.realm.FieldAttribute
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Room migration Task 11. Builds an old-format (schema 18) Realm file holding one of every kind of
 * data only this app keeps, imports it into an in-memory Room database, and checks it all arrived.
 * The provider rebuild is replaced by a stand-in, so what's checked is what the importer hands it.
 */
@RunWith(AndroidJUnit4::class)
class RealmImporterTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var db: FoxholeDatabase
    private val marker = RxSharedPreferences.create(context.getSharedPreferences("importer-test", 0))
        .getInteger("roomImportVersion", 0)
    private var rebuilds = 0

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "realm-import-test").apply { deleteRecursively(); mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, FoxholeDatabase::class.java).allowMainThreadQueries().build()
        marker.delete()
        writeOldRealmFile()
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
        marker.delete()
    }

    private fun importer() = RealmImporter(context, db, dir, marker, hasSmsPermission = { true }, rebuildFromProvider = { rebuilds++ })

    private fun count(sql: String) = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun importsEveryAppOnlyField() {
        val result = importer().importIfNeeded()
        assertTrue("import should succeed, was $result", result is ImportState.Done)
        assertEquals(1, rebuilds)
        assertEquals(RealmImporter.IMPORTER_VERSION, marker.get())

        val scheduled = db.scheduledMessageDao().get(5)!!
        assertEquals(listOf("5551", "5552"), scheduled.recipients)
        assertEquals(listOf("content://a"), scheduled.attachments)
        assertEquals(3, scheduled.subId)
        assertEquals(9L, scheduled.conversationId)
        assertEquals("5553", db.blockedNumberDao().get(6)!!.address)
        assertEquals(true, db.messageContentFilterDao().all().single().isRegex)
        assertEquals(true, db.trustedSenderDao().get(8)!!.locked)
        assertEquals("PROMOTIONAL", db.senderCategoryRuleDao().all().single().category)

        // What the full sync re-applies by provider identity
        val message = db.messageDao().getByProviderId("sms", 42)!!
        assertEquals(1234L, message.deletedAt)
        assertEquals(true, message.isStarred)
        assertEquals("TRANSACTIONAL", message.category)
        assertEquals(true, message.isOtp)

        val conversation = db.conversationDao().get(9)!!
        assertEquals(true, conversation.archived)
        assertEquals(true, conversation.pinned)
        assertEquals("Book club", conversation.name)
        assertEquals("PERSONAL", conversation.categoryOverride)
        assertEquals(false, conversation.sendAsGroup)
        assertEquals("half-written", conversation.draft)
        assertEquals(77L, conversation.draftDate)
        assertEquals(2, conversation.blockingClient)
        assertEquals("spam", conversation.blockReason)

        assertEquals(true, db.contactDao().allNumbers().single { it.id == 31L }.isDefault)
    }

    @Test
    fun importIsRepeatable() {
        importer().importIfNeeded()
        val first = listOf("Message", "Conversation", "ScheduledMessage", "BlockedNumber", "TrustedSender",
            "SenderCategoryRule", "MessageContentFilter", "PhoneNumber").map { count("SELECT COUNT(*) FROM $it") }
        marker.delete()
        importer().importIfNeeded()
        val second = listOf("Message", "Conversation", "ScheduledMessage", "BlockedNumber", "TrustedSender",
            "SenderCategoryRule", "MessageContentFilter", "PhoneNumber").map { count("SELECT COUNT(*) FROM $it") }
        assertEquals(first, second)
    }

    @Test
    fun skippedOnceImported() {
        importer().importIfNeeded()
        assertEquals(ImportState.NotNeeded, importer().importIfNeeded())
        assertEquals(1, rebuilds)
    }

    @Test
    fun waitsForSmsPermissionWithoutMarkingDone() {
        val result = RealmImporter(context, db, dir, marker, hasSmsPermission = { false }, rebuildFromProvider = { rebuilds++ })
            .importIfNeeded()
        assertTrue(result is ImportState.Failed)
        assertEquals(0, rebuilds)
        assertEquals(0, marker.get())
    }

    @Test
    fun untouchedRealmFile() {
        val file = File(dir, "default.realm")
        val bytes = file.readBytes()
        val modified = file.lastModified()
        importer().importIfNeeded()
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
    }

    /** Writes the old app's file layout: schema version 18, the fields the importer reads. */
    private fun writeOldRealmFile() {
        Realm.init(context)
        val config = RealmConfiguration.Builder().directory(dir).name("default.realm").schemaVersion(18).build()
        DynamicRealm.getInstance(config).use { realm ->
            realm.executeTransaction {
                // A new dynamic Realm is unversioned (-1) until set; the setter is package-private
                DynamicRealm::class.java.getDeclaredMethod("setVersion", Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(realm, 18L)
                val schema = realm.schema
                schema.create("ScheduledMessage")
                    .addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("date", Long::class.java).addField("subId", Long::class.java)
                    .addRealmListField("recipients", String::class.java)
                    .addField("sendAsGroup", Boolean::class.java).addField("body", String::class.java, FieldAttribute.REQUIRED)
                    .addRealmListField("attachments", String::class.java)
                    .addField("conversationId", Long::class.java)
                schema.create("BlockedNumber").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("address", String::class.java, FieldAttribute.REQUIRED)
                schema.create("MessageContentFilter").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("value", String::class.java, FieldAttribute.REQUIRED)
                    .addField("caseSensitive", Boolean::class.java).addField("isRegex", Boolean::class.java)
                    .addField("includeContacts", Boolean::class.java)
                schema.create("TrustedSender").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("address", String::class.java, FieldAttribute.REQUIRED).addField("locked", Boolean::class.java)
                schema.create("SenderCategoryRule").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("address", String::class.java, FieldAttribute.REQUIRED)
                    .addField("category", String::class.java, FieldAttribute.REQUIRED)
                schema.create("Message").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("type", String::class.java, FieldAttribute.REQUIRED).addField("contentId", Long::class.java)
                    .addField("deletedAt", java.lang.Long::class.java).addField("isStarred", Boolean::class.java)
                    .addField("category", String::class.java).addField("isOtp", Boolean::class.java)
                schema.create("Conversation").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("archived", Boolean::class.java).addField("blocked", Boolean::class.java)
                    .addField("pinned", Boolean::class.java).addField("name", String::class.java, FieldAttribute.REQUIRED)
                    .addField("blockingClient", Integer::class.java).addField("blockReason", String::class.java)
                    .addField("categoryOverride", String::class.java).addField("sendAsGroup", Boolean::class.java)
                    .addField("draft", String::class.java, FieldAttribute.REQUIRED).addField("draftDate", Long::class.java)
                schema.create("PhoneNumber").addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY)
                    .addField("isDefault", Boolean::class.java)

                realm.createObject("ScheduledMessage", 5L).apply {
                    setLong("date", 100); setLong("subId", 3); setBoolean("sendAsGroup", true); setString("body", "later")
                    setList("recipients", RealmList("5551", "5552")); setList("attachments", RealmList("content://a"))
                    setLong("conversationId", 9)
                }
                realm.createObject("BlockedNumber", 6L).setString("address", "5553")
                realm.createObject("MessageContentFilter", 7L).apply { setString("value", "win.*prize"); setBoolean("isRegex", true) }
                realm.createObject("TrustedSender", 8L).apply { setString("address", "BANK"); setBoolean("locked", true) }
                realm.createObject("SenderCategoryRule", 10L).apply { setString("address", "5554"); setString("category", "PROMOTIONAL") }
                realm.createObject("Message", 20L).apply {
                    setString("type", "sms"); setLong("contentId", 42); setLong("deletedAt", 1234)
                    setBoolean("isStarred", true); setString("category", "TRANSACTIONAL"); setBoolean("isOtp", true)
                }
                // Nothing app-only on it: not carried across
                realm.createObject("Message", 21L).apply { setString("type", "sms"); setLong("contentId", 43) }
                realm.createObject("Conversation", 9L).apply {
                    setBoolean("archived", true); setBoolean("pinned", true); setString("name", "Book club")
                    setInt("blockingClient", 2); setString("blockReason", "spam"); setString("categoryOverride", "PERSONAL")
                    setBoolean("sendAsGroup", false); setString("draft", "half-written"); setLong("draftDate", 77)
                }
                realm.createObject("PhoneNumber", 31L).setBoolean("isDefault", true)
                realm.createObject("PhoneNumber", 32L).setBoolean("isDefault", false)
            }
        }
    }
}
