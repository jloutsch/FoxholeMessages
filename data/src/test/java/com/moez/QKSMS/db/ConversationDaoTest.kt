package dev.octoshrimpy.quik.db

import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Recipient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The inbox queries that replaced Realm's (Room migration #44, Task 7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationDaoTest {

    private val db = inMemoryDatabase()
    private val dao = db.conversationDao()

    @After fun close() = db.close()

    private fun listedIds(unreadAtTop: Boolean = false, archived: Boolean = false) =
        dao.listed(archived, unreadAtTop).map { it.id }

    @Test
    fun pinnedComeFirst() {
        db.messageDao().upsert(listOf(message(1, threadId = 1, date = 10), message(2, threadId = 2, date = 20)))
        db.addConversation(1, lastMessageId = 1, pinned = true)
        db.addConversation(2, lastMessageId = 2)
        assertEquals(listOf(1L, 2L), listedIds())
    }

    @Test
    fun newestFirstOtherwise() {
        db.messageDao().upsert(listOf(message(1, threadId = 1, date = 10), message(2, threadId = 2, date = 20)))
        db.addConversation(1, lastMessageId = 1)
        db.addConversation(2, lastMessageId = 2)
        assertEquals(listOf(2L, 1L), listedIds())
    }

    @Test
    fun unreadAtTopOrdersUnreadFirst() {
        db.messageDao().upsert(listOf(message(1, threadId = 1, date = 10, read = false), message(2, threadId = 2, date = 20)))
        db.addConversation(1, lastMessageId = 1)
        db.addConversation(2, lastMessageId = 2)
        assertEquals(listOf(1L, 2L), listedIds(unreadAtTop = true))
        assertEquals(listOf(2L, 1L), listedIds(unreadAtTop = false))
    }

    @Test
    fun draftOnlyConversationIsListed() {
        db.addConversation(1, draft = "hi", draftDate = 5)
        db.addConversation(2) // no message, no draft
        assertEquals(listOf(1L), listedIds())
    }

    @Test
    fun blockedAndArchivedFilter() {
        db.messageDao().upsert(listOf(message(1, threadId = 1), message(2, threadId = 2), message(3, threadId = 3)))
        db.addConversation(1, lastMessageId = 1)
        db.addConversation(2, lastMessageId = 2, archived = true)
        db.addConversation(3, lastMessageId = 3, blocked = true)
        assertEquals(listOf(1L), listedIds())
        assertEquals(listOf(2L), listedIds(archived = true))
        assertEquals(listOf(3L), dao.blocked().map { it.id })
    }

    @Test
    fun conversationWithoutRecipientsIsHidden() {
        db.messageDao().upsert(listOf(message(1, threadId = 1)))
        db.addConversation(1, lastMessageId = 1, recipients = false)
        assertEquals(emptyList<Long>(), listedIds())
    }

    @Test
    fun snippetSkipsTrashedLastMessage() {
        db.messageDao().upsert(listOf(message(1, threadId = 1, date = 10), message(2, threadId = 1, date = 20, deletedAt = 99)))
        assertEquals(1L, db.messageDao().latestVisible(1)?.id)
    }

    @Test
    fun categoryTabsFollowOverrideThenLastMessage() {
        db.messageDao().upsert(listOf(
            message(1, threadId = 1).apply { category = "TRANSACTIONAL" },
            message(2, threadId = 2).apply { category = "TRANSACTIONAL" },
            message(3, threadId = 3), // not sorted yet
        ))
        db.addConversation(1, lastMessageId = 1)
        db.addConversation(2, lastMessageId = 2)
        db.addConversation(3, lastMessageId = 3)
        dao.update(listOf(dao.get(2)!!.apply { categoryOverride = "PERSONAL" }))

        fun tab(category: String) = dao.inCategory(category, false).map { it.id }.sorted()
        assertEquals(listOf(1L), tab("TRANSACTIONAL"))
        assertEquals(listOf(2L, 3L), tab("PERSONAL"))
        assertEquals(emptyList<Long>(), tab("PROMOTIONAL"))
    }

    @Test
    fun liveListRefreshesWhenOnlyAContactChanges() = runBlocking {
        db.messageDao().upsert(listOf(message(1)))
        db.addConversation(1, lastMessageId = 1)
        val relations = Relations(db)
        val names = Channel<String?>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.IO) {
            db.observe(Tables.CONVERSATIONS) {
                relations.conversations(dao.listed(false, false)).single().recipients.single().contact?.name
            }.collect { names.send(it) }
        }
        assertEquals(null, withTimeout(5000) { names.receive() })

        // Neither Conversation nor Message changes: only the contact and the recipient's link to it
        db.runInTransaction {
            db.contactDao().upsert(listOf(Contact(lookupKey = "k", name = "Ann")))
            db.recipientDao().upsert(listOf(Recipient(id = 100, address = "5551", contactLookupKey = "k")))
        }
        assertEquals("Ann", withTimeout(5000) { names.receive() })
        watching.cancel()
    }

    @Test
    fun conversationsMadeOnThePhoneKeepTheirOwnRecipients() {
        // #83: recipients of a new empty conversation all got id 0, so each one overwrote the last
        val relations = Relations(db)
        db.recipientDao().upsert(listOf(Recipient(id = 5, address = "5550005")))
        listOf(1L to listOf("5550001", "5550002"), 2L to listOf("5550003")).forEach { (thread, addresses) ->
            val ids = relations.newLocalRecipientIds(addresses.size)
            db.runInTransaction {
                relations.saveConversation(Conversation(id = thread).apply {
                    recipients = addresses.zip(ids) { address, id -> Recipient(id = id, address = address) }
                })
            }
        }
        assertEquals(listOf("5550001", "5550002", "5550003", "5550005"), db.recipientDao().all().map { it.address }.sorted())
    }
}
