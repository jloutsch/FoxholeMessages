package dev.octoshrimpy.quik.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Message queries that replaced Realm's (Room migration #44, Task 7). The first four are the
 * on-device TrashFiltersTest cases: messages in Trash stay out of badges, search, the gallery and
 * shortcut ranking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageDaoTest {

    private val db = inMemoryDatabase()
    private val dao = db.messageDao()

    @After fun close() = db.close()

    @Test
    fun unreadCountIgnoresTrash() {
        dao.upsert(listOf(message(1, read = false), message(2, read = false, deletedAt = 5)))
        assertEquals(1L, dao.unreadCount(1))
    }

    @Test
    fun searchIgnoresTrash() {
        dao.upsert(listOf(message(1, body = "pizza tonight"), message(2, body = "pizza again", deletedAt = 5)))
        assertEquals(listOf(1L), dao.search("pizza").map { it.id })
    }

    @Test
    fun galleryIgnoresTrash() {
        dao.upsert(listOf(message(1, type = "mms", contentId = 11), message(2, type = "mms", contentId = 12, deletedAt = 5)))
        db.mmsPartDao().upsert(listOf(part(101, 11, "image/jpeg"), part(102, 12, "image/png"), part(103, 11, "text/plain")))
        val gallery = runBlocking { db.mmsPartDao().observeMediaForThread(1).first() }
        assertEquals(listOf(101L), gallery.map { it.id })
    }

    @Test
    fun topConversationsIgnoreTrash() {
        dao.upsert(listOf(message(1, date = 100), message(2, date = 200, deletedAt = 5), message(3, date = 10)))
        assertEquals(1L, dao.countSince(1, since = 50))
    }

    @Test
    fun searchMatchesBodyOrPartText() {
        dao.upsert(listOf(message(1, body = "see you at noon"), message(2, type = "mms", contentId = 22), message(3, body = "other")))
        db.mmsPartDao().upsert(listOf(part(201, 22, "text/plain", text = "noon works")))
        assertEquals(listOf(1L, 2L), dao.search("noon").map { it.id }.sorted())
    }

    @Test
    fun searchIsCaseInsensitiveForAscii() {
        dao.upsert(listOf(message(1, body = "hello world")))
        assertEquals(listOf(1L), dao.search("HeLLo").map { it.id })
    }

    @Test
    fun searchAccentedBehaviourRecorded() {
        // The SQLite under these unit tests matches "CAFÉ" to "café", as Realm's Case.INSENSITIVE did.
        // The phone's own SQLite does too (DeviceSearchTest, on-device, passed on the Note 10, #80).
        dao.upsert(listOf(message(1, body = "café")))
        assertEquals(listOf(1L), dao.search("CAFÉ").map { it.id })
        assertEquals(listOf(1L), dao.search("CAF").map { it.id })
    }

    @Test
    fun threadHidesReactionsAndTrash() {
        dao.upsert(listOf(message(1), message(2, deletedAt = 5), message(3).apply { isEmojiReaction = true }))
        assertEquals(listOf(1L), dao.thread(1).map { it.id })
    }

    @Test
    fun partsHydrateByProviderId() {
        dao.upsert(listOf(message(1, type = "mms", contentId = 77)))
        db.mmsPartDao().upsert(listOf(part(301, 77, "image/jpeg")))
        val hydrated = Relations(db).messages(dao.thread(1))
        assertEquals(listOf(301L), hydrated.single().parts.map { it.id })
        assertEquals(1L, dao.forPart(301)?.id)
    }

    @Test
    fun searchTreatsPercentLiterally() {
        // #79: % is a wildcard in SQL LIKE; Realm matched it as a plain character
        dao.upsert(listOf(message(1, body = "50% off today"), message(2, body = "500 items")))
        assertEquals(listOf(1L), dao.search("50%").map { it.id })
        assertEquals(listOf(1L), dao.threadSearch(1, "50%").map { it.id })
    }

    @Test
    fun searchTreatsUnderscoreLiterally() {
        dao.upsert(listOf(message(1, body = "file_name.txt"), message(2, body = "filexname")))
        assertEquals(listOf(1L), dao.search("file_name").map { it.id })
    }

    @Test
    fun searchTreatsBackslashLiterally() {
        dao.upsert(listOf(message(1, body = "C:\\temp"), message(2, body = "C:temp")))
        assertEquals(listOf(1L), dao.search("C:\\").map { it.id })
    }

    @Test
    fun savingAProviderMessageAgainReusesItsRow() {
        // #82: the provider-changed sync can save a just-sent message before the send path does;
        // the send path must reuse that row instead of adding a second copy
        val relations = Relations(db)
        relations.saveProviderMessage(message(5, contentId = 70))
        relations.saveProviderMessage(message(9, contentId = 70))
        assertEquals(listOf(5L), dao.all().map { it.id })
    }
}
