package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Two rules that keep the inbox tabs and the user's own data stable:
 *  - replies take the conversation's category, so replying doesn't move a conversation between tabs;
 *  - a sync rebuilds every message from Android's storage, so data only Foxhole keeps (stars, Trash,
 *    tabs, code tags) has to be carried across or it's wiped.
 */
class SortingAndSyncDataTest {

    // --- Replies inherit the conversation's category ---

    private data class Msg(val sent: Boolean, val category: String?)

    /** Positions (in the conversation) of the replies that change, with their new category. */
    private fun updates(vararg conversation: Msg) =
        ReplyCategories.updates(conversation.withIndex().toList(), isSent = { it.value.sent }, category = { it.value.category })
            .map { (msg, category) -> msg.index to category }

    @Test
    fun replyInBankConversationStaysTransactional() =
        assertEquals(listOf(1 to "TRANSACTIONAL"), updates(Msg(false, "TRANSACTIONAL"), Msg(true, null)))

    @Test
    fun replyIsNotSortedByItsOwnWords() =
        // "I paid you $20" would sort as Transactional by its wording; in a personal conversation it
        // must take the conversation's category instead.
        assertEquals(listOf(1 to "UNCLASSIFIED"), updates(Msg(false, "UNCLASSIFIED"), Msg(true, "TRANSACTIONAL")))

    @Test
    fun eachReplyFollowsTheLatestReceivedBeforeIt() =
        assertEquals(
            listOf(1 to "PROMOTIONAL", 3 to "TRANSACTIONAL"),
            updates(Msg(false, "PROMOTIONAL"), Msg(true, null), Msg(false, "TRANSACTIONAL"), Msg(true, null))
        )

    @Test
    fun replyBeforeAnythingReceivedHasNoCategory() =
        assertEquals(listOf(0 to null), updates(Msg(true, "TRANSACTIONAL"), Msg(false, "PROMOTIONAL")))

    @Test
    fun receivedMessageNotYetSortedDoesNotResetTheConversation() =
        assertEquals(listOf(2 to "TRANSACTIONAL"), updates(Msg(false, "TRANSACTIONAL"), Msg(false, null), Msg(true, null)))

    @Test
    fun repliesAlreadyRightAreLeftAlone() =
        assertEquals(emptyList<Pair<Int, String?>>(), updates(Msg(false, "PROMOTIONAL"), Msg(true, "PROMOTIONAL")))

    // --- Sync keeps what only Foxhole stores ---

    private fun message(type: String = "sms", contentId: Long = 42) = Message().apply {
        this.type = type
        this.contentId = contentId
    }

    @Test
    fun appOnlyFieldsSurviveARebuild() {
        val before = message().apply { deletedAt = 1_700_000_000_000; isStarred = true; category = "PROMOTIONAL"; isOtp = true }
        val rebuilt = message()  // what a sync creates from Android's storage: none of these set
        AppOnlyMessageFields.of(before).applyTo(rebuilt)
        assertEquals(1_700_000_000_000, rebuilt.deletedAt)
        assertTrue(rebuilt.isStarred)
        assertEquals("PROMOTIONAL", rebuilt.category)
        assertTrue(rebuilt.isOtp)
    }

    @Test
    fun clearedValuesStayCleared() {
        val before = message()  // restored from Trash, unstarred, uncategorized
        val rebuilt = message().apply { deletedAt = 5; isStarred = true; category = "TRANSACTIONAL"; isOtp = true }
        AppOnlyMessageFields.of(before).applyTo(rebuilt)
        assertNull(rebuilt.deletedAt)
        assertEquals(false, rebuilt.isStarred)
        assertNull(rebuilt.category)
        assertEquals(false, rebuilt.isOtp)
    }

    @Test
    fun matchedByTypeAndStorageIdNotByFoxholeId() {
        // A full sync gives every message a new Foxhole id, so ids can't link before and after.
        assertEquals(AppOnlyMessageFields.key(message("sms", 42)), AppOnlyMessageFields.key(message("sms", 42).apply { id = 999 }))
        // SMS and MMS ids come from separate tables and can collide.
        assertTrue(AppOnlyMessageFields.key(message("sms", 42)) != AppOnlyMessageFields.key(message("mms", 42)))
    }

    /**
     * Guard for the rule in CLAUDE.md: a new field on Message that only Foxhole stores must be
     * carried across syncs in AppOnlyMessageFields, or every sync silently wipes it. A new field that
     * isn't in one of these lists fails this test, so whoever adds it has to decide which it is.
     */
    @Test
    fun everyMessageFieldIsAccountedForAcrossSyncs() {
        val rebuiltFromAndroid = setOf(
            "id", "threadId", "contentId", "address", "boxId", "type", "date", "dateSent", "seen", "read",
            "locked", "subId", "body", "errorCode", "deliveryStatus", "attachmentTypeString",
            "mmsDeliveryStatusString", "readReportString", "errorType", "messageSize", "messageType",
            "mmsStatus", "subject", "textContentType", "parts"
        )
        val recomputedAfterSync = setOf("isEmojiReaction", "emojiReactions")  // reactions are re-parsed
        val onlyWhileSending = setOf("sendAsGroup")                            // delayed-send state
        val keptByAppOnlyMessageFields = setOf("category", "isOtp", "isStarred", "deletedAt")

        val known = rebuiltFromAndroid + recomputedAfterSync + onlyWhileSending + keptByAppOnlyMessageFields
        val fields = Message::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        val unaccounted = fields - known
        assertTrue(
            "New Message field(s) $unaccounted: if only Foxhole stores them, add them to AppOnlyMessageFields " +
                "(SyncRepositoryImpl) or every sync wipes them; then add them to the right list here.",
            unaccounted.isEmpty()
        )
    }
}
