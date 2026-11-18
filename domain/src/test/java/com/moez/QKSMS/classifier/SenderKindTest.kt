package dev.octoshrimpy.quik.classifier

import org.junit.Assert.assertEquals
import org.junit.Test

class SenderKindTest {

    @Test
    fun shortCodesAreRecognised() {
        listOf("72166", "86583", "899000", "411", "  42892 ").forEach { address ->
            assertEquals(address, SenderKind.SHORT_CODE, SenderKind.of(address))
        }
    }

    @Test
    fun namedSendersAreRecognised() {
        listOf("VZWNOTICE", "Vi-CARE-S", "AMAZON", "Chase Bank").forEach { address ->
            assertEquals(address, SenderKind.NAMED_SENDER, SenderKind.of(address))
        }
    }

    @Test
    fun tollFreeNumbersAreRecognised() {
        listOf("+18775755319", "(844) 839-8816", "8005551212", "1-888-555-0100", "+1 833 555 0100")
            .forEach { address -> assertEquals(address, SenderKind.TOLL_FREE, SenderKind.of(address)) }
    }

    @Test
    fun ordinaryNumbersArePhoneNumbers() {
        listOf("+1 (555) 010-0101", "5550100101", "(212) 555-0102", "+447700900123", "0412345678")
            .forEach { address -> assertEquals(address, SenderKind.PHONE_NUMBER, SenderKind.of(address)) }
    }

    @Test
    fun emailAndBlankAreTreatedAsPossiblyPersonal() {
        assertEquals(SenderKind.PHONE_NUMBER, SenderKind.of("friend@example.com"))
        assertEquals(SenderKind.PHONE_NUMBER, SenderKind.of(""))
    }
}
