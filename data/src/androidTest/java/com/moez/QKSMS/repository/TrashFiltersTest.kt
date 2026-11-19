package dev.octoshrimpy.quik.repository

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.f2prateek.rx.preferences2.RxSharedPreferences
import dev.octoshrimpy.quik.filter.ContactFilter
import dev.octoshrimpy.quik.filter.ConversationFilter
import dev.octoshrimpy.quik.filter.PhoneNumberFilter
import dev.octoshrimpy.quik.filter.RecipientFilter
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

/**
 * Messages moved to Trash keep their rows (with deletedAt set) until purged, so every query that
 * counts or lists messages has to leave them out. Several didn't: unread badges, search, the media
 * gallery and the launcher-shortcut ranking. These run the real repository queries against an
 * in-memory database on a phone (the database library doesn't run in plain JVM tests).
 *
 * Run: ./gradlew :data:assembleDebugAndroidTest, then install and `am instrument` (see CLAUDE.md
 * "Tests"; don't use connectedAndroidTest, which uninstalls the app).
 */
@RunWith(AndroidJUnit4::class)
class TrashFiltersTest {

    private lateinit var context: Context
    private lateinit var holder: Realm  // an in-memory database lives only while an instance is open
    private lateinit var messages: MessageRepositoryImpl
    private lateinit var conversations: ConversationRepositoryImpl

    private val now = System.currentTimeMillis()
    private val trashed = now - 1000

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Realm.init(context)
        Realm.setDefaultConfiguration(RealmConfiguration.Builder().inMemory().name("trash-filters-test").build())
        holder = Realm.getDefaultInstance()

        val phoneNumberUtils = PhoneNumberUtils(context)
        val sharedPrefs = context.getSharedPreferences("trash-filters-test", Context.MODE_PRIVATE)
        messages = MessageRepositoryImpl(
            mock(dev.octoshrimpy.quik.manager.ActiveConversationManager::class.java), context, mock(dev.octoshrimpy.quik.manager.KeyManager::class.java),
            phoneNumberUtils, Preferences(context, RxSharedPreferences.create(sharedPrefs), sharedPrefs),
            mock(SyncRepository::class.java), mock(EmojiReactionRepository::class.java),
            mock(dev.octoshrimpy.quik.mapper.CursorToMessage::class.java), mock(dev.octoshrimpy.quik.mapper.CursorToPart::class.java),
            mock(ContactRepository::class.java)
        )
        val phoneNumberFilter = PhoneNumberFilter(phoneNumberUtils)
        conversations = ConversationRepositoryImpl(
            context, ConversationFilter(RecipientFilter(ContactFilter(phoneNumberFilter), phoneNumberFilter)),
            mock(dev.octoshrimpy.quik.mapper.CursorToConversation::class.java),
            mock(dev.octoshrimpy.quik.mapper.CursorToRecipient::class.java), phoneNumberUtils
        )

        holder.executeTransaction { realm ->
            // conversation 1: one unread message, plus an unread one, a "needle" and a picture in Trash
            // conversation 2: one "needle" message, not trashed
            // conversation 3: three recent messages, all in Trash (counting them would rank it above
            // conversation 2's two, so the ranking test can tell whether Trash is left out)
            val m = listOf(
                message(9, 3, "z", deletedAt = trashed),
                message(1, 1, "hello", read = false),
                message(2, 1, "gone", read = false, deletedAt = trashed),
                message(3, 1, "a needle in Trash", deletedAt = trashed),
                message(4, 2, "a needle"),
                message(5, 3, "x", deletedAt = trashed),
                message(6, 3, "y", deletedAt = trashed),
                message(7, 1, "", deletedAt = trashed).apply { type = "mms"; parts.add(MmsPart().apply { id = 70; type = "image/jpeg" }) },
                message(8, 2, "").apply { type = "mms"; parts.add(MmsPart().apply { id = 80; type = "image/jpeg" }) }
            )
            realm.insertOrUpdate(m)
            listOf(1L to 1L, 2L to 4L, 3L to 6L).forEach { (id, last) ->
                val conversation = Conversation(id = id).apply {
                    recipients = RealmList(Recipient(id = id, address = "+1555010010$id"))
                    lastMessage = realm.where(Message::class.java).equalTo("id", last).findFirst()
                }
                realm.insertOrUpdate(conversation)
            }
        }
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private fun message(id: Long, threadId: Long, body: String, read: Boolean = true, deletedAt: Long? = null) = Message().apply {
        this.id = id
        this.threadId = threadId
        this.body = body
        this.read = read
        this.date = now - id * 1000
        this.type = "sms"
        this.boxId = 1
        this.deletedAt = deletedAt
    }

    @Test
    fun unreadBadgeIgnoresTrash() =
        assertEquals(1L, messages.getUnreadCountForConversation(1))

    @Test
    fun searchIgnoresTrash() {
        val hits = conversations.searchConversations("needle").filter { it.messages > 0 }
        assertEquals(listOf(2L), hits.map { it.conversation.id })
        assertEquals(1, hits.single().messages)
    }

    @Test
    fun galleryIgnoresTrash() {
        var ids: List<Long> = emptyList()
        // the gallery query is asynchronous, which needs a looper thread
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ids = listOf(1L, 2L).flatMap { thread -> messages.getPartsForConversation(thread).apply { load() }.map { it.id } }
        }
        assertEquals(listOf(80L), ids)
    }

    @Test
    fun shortcutRankingIgnoresTrash() {
        // conversation 3 has more recent messages, but all in Trash, so it must not outrank the others
        // recent messages outside Trash: conversation 2 has two, 1 has one, 3 has none
        assertEquals(listOf(2L, 1L, 3L), conversations.getTopConversations().map { it.id })
    }
}
