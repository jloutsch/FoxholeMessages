package dev.octoshrimpy.quik.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restoring onto a phone that still has the messages used to double every one of them. */
class RestoreDuplicatesTest {

    private fun sms(body: String, date: Long = 1_700_000_000_000) = RestoreDuplicates.key(false, 1, "+15550100101", date, body)
    private fun mms(date: Long = 1_700_000_000_000) = RestoreDuplicates.key(true, 1, "+15550100101", date, null)

    @Test
    fun messagesAlreadyOnThePhoneAreSkipped() {
        val present = RestoreDuplicates(listOf(sms("hi")))
        assertTrue(present.isAlreadyPresent(sms("hi")))
    }

    @Test
    fun differentTextOrTimeIsRestored() {
        val present = RestoreDuplicates(listOf(sms("hi")))
        assertFalse(present.isAlreadyPresent(sms("hello")))
        assertFalse(present.isAlreadyPresent(sms("hi", date = 1_700_000_000_001)))
        assertFalse(present.isAlreadyPresent(mms()))
    }

    @Test
    fun eachExistingMessageMatchesOnlyOnce() {
        // three pictures sent as separate MMS in the same second, two of them still on the phone
        val present = RestoreDuplicates(listOf(mms(), mms()))
        assertTrue(present.isAlreadyPresent(mms()))
        assertTrue(present.isAlreadyPresent(mms()))
        assertFalse(present.isAlreadyPresent(mms()))
    }

    @Test
    fun emptyPhoneRestoresEverything() =
        assertFalse(RestoreDuplicates(emptyList()).isAlreadyPresent(sms("hi")))
}
