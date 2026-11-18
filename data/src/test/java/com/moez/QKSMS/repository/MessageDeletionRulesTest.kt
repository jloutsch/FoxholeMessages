package dev.octoshrimpy.quik.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Rules that remove messages automatically. A mistake in either deletes a user's real messages. */
class MessageDeletionRulesTest {

    // --- Duplicate removal: what counts as "the same message" ---

    private fun sms(
        threadId: Long = 10, address: String = "+15550100101", boxId: Int = 1,
        dateSent: Long = 1_700_000_000_000, date: Long = 1_700_000_002_000, body: String = "hello"
    ) = DuplicateSignature.of("sms", threadId, address, boxId, dateSent, date, body, "", false, emptyList())

    private fun mms(
        date: Long = 1_700_000_000_000, dateSent: Long = 0,
        parts: List<DuplicateSignature.Part> = listOf(DuplicateSignature.Part(0, "image/jpeg", "IMG_0001.jpg", null)),
        subject: String = ""
    ) = DuplicateSignature.of("mms", 20, "+15550100102", 1, dateSent, date, "", subject, true, parts)

    @Test
    fun carrierDoubleDeliveryIsADuplicate() =
        // Same text, same sent time from the carrier; the phone received it twice, seconds apart.
        assertEquals(sms(date = 1_700_000_002_000), sms(date = 1_700_000_005_000))

    @Test
    fun repeatedOutgoingRepliesAreNotDuplicates() =
        // Outgoing SMS have no sent time (0); two "ok" replies minutes apart are different messages.
        assertNotEquals(
            sms(boxId = 2, dateSent = 0, date = 1_700_000_000_000, body = "ok"),
            sms(boxId = 2, dateSent = 0, date = 1_700_000_300_000, body = "ok")
        )

    @Test
    fun sameTextInAnotherConversationIsNotADuplicate() =
        assertNotEquals(sms(threadId = 10), sms(threadId = 11))

    @Test
    fun sentAndReceivedAreNotDuplicates() =
        assertNotEquals(sms(boxId = 1), sms(boxId = 2))

    @Test
    fun differentPhotosInTheSameSecondAreNotDuplicates() =
        assertNotEquals(
            mms(parts = listOf(DuplicateSignature.Part(0, "image/jpeg", "IMG_0001.jpg", null))),
            mms(parts = listOf(DuplicateSignature.Part(0, "image/jpeg", "IMG_0002.jpg", null)))
        )

    @Test
    fun mmsRestoredTwiceIsADuplicate() = assertEquals(mms(), mms())

    @Test
    fun mmsReceivedAtDifferentTimesIsNotADuplicate() =
        assertNotEquals(mms(date = 1_700_000_000_000), mms(date = 1_700_000_060_000))

    @Test
    fun mmsPartOrderInStorageDoesNotMatter() {
        val a = DuplicateSignature.Part(0, "text/plain", null, "look")
        val b = DuplicateSignature.Part(1, "image/jpeg", "IMG.jpg", null)
        assertEquals(mms(parts = listOf(a, b)), mms(parts = listOf(b, a)))
    }

    @Test
    fun differentSubjectsAreNotDuplicates() =
        assertNotEquals(mms(subject = "Trip"), mms(subject = "Party"))

    // --- OTP retention: what may be deleted ---

    @Test
    fun onlyReceivedUnprotectedNonContactCodesAreDeletable() {
        assertTrue(OtpRetentionRule.isDeletable(isIncoming = true, isStarred = false, isLocked = false) { false })
        assertFalse("sent by the user", OtpRetentionRule.isDeletable(isIncoming = false, isStarred = false, isLocked = false) { false })
        assertFalse("starred", OtpRetentionRule.isDeletable(isIncoming = true, isStarred = true, isLocked = false) { false })
        assertFalse("locked", OtpRetentionRule.isDeletable(isIncoming = true, isStarred = false, isLocked = true) { false })
        assertFalse("from a contact", OtpRetentionRule.isDeletable(isIncoming = true, isStarred = false, isLocked = false) { true })
    }

    @Test
    fun contactsLookupIsSkippedWhenAlreadyProtected() {
        // The contacts lookup is a cross-process query; it shouldn't run for messages already excluded.
        OtpRetentionRule.isDeletable(isIncoming = false, isStarred = false, isLocked = false) { fail("looked up"); false }
        OtpRetentionRule.isDeletable(isIncoming = true, isStarred = true, isLocked = false) { fail("looked up"); false }
    }
}
