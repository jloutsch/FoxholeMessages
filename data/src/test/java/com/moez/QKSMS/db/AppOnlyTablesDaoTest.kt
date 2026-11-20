package dev.octoshrimpy.quik.db

import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.ScheduledMessage
import dev.octoshrimpy.quik.model.SenderCategoryRule
import dev.octoshrimpy.quik.model.SyncLog
import dev.octoshrimpy.quik.model.TrustedSender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tables only this app has (no system store copy): round trip and live updates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppOnlyTablesDaoTest {

    private val db = inMemoryDatabase()

    @After fun close() = db.close()

    @Test
    fun scheduledMessageKeepsItsLists() {
        val dao = db.scheduledMessageDao()
        dao.upsert(ScheduledMessage(id = 1, date = 50, recipients = listOf("5551", "5552"),
            attachments = listOf("content://a", "file://b"), body = "hi"))
        val saved = runBlocking { dao.observeAll().first() }.single()
        assertEquals(listOf("5551", "5552"), saved.recipients)
        assertEquals(listOf("content://a", "file://b"), saved.attachments)
        assertEquals(2L, dao.maxId()?.plus(1))
    }

    @Test
    fun blockedNumbersRoundTrip() {
        val dao = db.blockedNumberDao()
        dao.insert(listOf(BlockedNumber(id = 1, address = "5551")))
        assertEquals(listOf("5551"), runBlocking { dao.observeAll().first() }.map { it.address })
        dao.delete(listOf(1))
        assertEquals(emptyList<BlockedNumber>(), dao.all())
    }

    @Test
    fun trustedSendersRoundTrip() {
        val dao = db.trustedSenderDao()
        dao.insert(TrustedSender(id = 3, address = "BANK", locked = true))
        assertEquals(true, runBlocking { dao.observeAll().first() }.single().locked)
    }

    @Test
    fun senderRulesRoundTrip() {
        val dao = db.senderCategoryRuleDao()
        dao.insert(SenderCategoryRule(id = 4, address = "5551", category = "PROMOTIONAL"))
        assertEquals("PROMOTIONAL", runBlocking { dao.observeAll().first() }.single().category)
    }

    @Test
    fun contentFiltersRoundTrip() {
        val dao = db.messageContentFilterDao()
        dao.insert(listOf(MessageContentFilter(id = 5, value = "win.*prize", isRegex = true)))
        assertEquals(true, runBlocking { dao.observeAll().first() }.single().isRegex)
    }

    @Test
    fun lastSyncDateIsTheNewest() {
        val dao = db.syncLogDao()
        assertEquals(null, dao.lastSyncDate())
        dao.insert(SyncLog().apply { date = 10 })
        dao.insert(SyncLog().apply { date = 30 })
        assertEquals(30L, dao.lastSyncDate())
    }
}
