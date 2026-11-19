package dev.octoshrimpy.quik.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OtpDetector tags one-time-code texts so "OTP message retention" can delete them later. It is a
 * loose keyword + digits match by design; the retention rule (OtpRetentionRule) is what keeps
 * friends' messages safe. These tests pin the current behaviour so any change to it is deliberate.
 */
class OtpDetectorTest {

    private val detector = OtpDetector()

    @Test
    fun realCodesAreDetected() {
        listOf(
            "Your verification code is 123456",
            "Use 4821 as your one-time passcode",
            "Your Uber code is 4821. Don't share it",
            "G-482193 is your Google verification code",
            "Your OTP for login is 90210",
            "Your PIN reset code: 55667788"
        ).forEach { assertTrue(it, detector.isOtp(it)) }
    }

    @Test
    fun textsWithoutBothAKeywordAndADigitCodeAreNot() {
        listOf(
            "Code red at the office tonight",   // keyword, no digits
            "Order 123456 has shipped",          // digits, no keyword
            "Call me at 5550100",                // 7 digits, no keyword
            "Your code is 12",                   // too short to be a code
            "Your code is 123456789",            // too long to be a code
            "decoder ring 1234",                 // "code" only as part of a word
            ""
        ).forEach { assertFalse(it, detector.isOtp(it)) }
    }

    @Test
    fun knownLooseMatchIsStillTagged() =
        // Ordinary messages like this ARE tagged; OtpRetentionRule must keep them safe (contacts,
        // starred, sent) rather than relying on this detector being precise.
        assertTrue(detector.isOtp("The door code is 4821, come on up"))

    @Test
    fun extractsTheCode() {
        assertEquals("123456", detector.extractCode("Your verification code is 123456"))
        assertEquals(null, detector.extractCode("no digits here"))
    }

    @Test
    fun skipsNumbersBeforeTheKeyword() =
        assertEquals("482193", detector.extractCode("On 2026-11-18 your code is 482193"))

    @Test
    fun findsCodeBeforeTheKeyword() =
        assertEquals("482193", detector.extractCode("482193 is your verification code"))

    @Test
    fun findsPrefixedCode() =
        assertEquals("482193", detector.extractCode("G-482193 is your Google verification code"))

}
