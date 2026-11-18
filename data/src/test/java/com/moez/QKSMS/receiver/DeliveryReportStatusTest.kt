package dev.octoshrimpy.quik.receiver

import dev.octoshrimpy.quik.receiver.DeliveryReportStatus.DELIVERED
import dev.octoshrimpy.quik.receiver.DeliveryReportStatus.FAILED
import dev.octoshrimpy.quik.receiver.DeliveryReportStatus.PENDING
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every delivery report used to count as "Delivered"; the outcome is in the report's status. */
class DeliveryReportStatusTest {

    @Test
    fun gsmStatus() {
        assertEquals(DELIVERED, DeliveryReportStatus.of("3gpp", 0x00))  // received by recipient
        assertEquals(DELIVERED, DeliveryReportStatus.of("3gpp", 0x01))  // forwarded, unconfirmed
        assertEquals(PENDING, DeliveryReportStatus.of("3gpp", 0x20))    // congestion, still trying
        assertEquals(PENDING, DeliveryReportStatus.of("3gpp", 0x3F))
        assertEquals(FAILED, DeliveryReportStatus.of("3gpp", 0x40))     // permanent error
        assertEquals(FAILED, DeliveryReportStatus.of("3gpp", 0x65))     // gave up after temporary errors
        assertEquals(DELIVERED, DeliveryReportStatus.of(null, 0x00))     // format missing: GSM
    }

    @Test
    fun cdmaStatusUsesErrorClass() {
        assertEquals(DELIVERED, DeliveryReportStatus.of("3gpp2", 0x00 shl 24))
        assertEquals(PENDING, DeliveryReportStatus.of("3gpp2", (0x02 shl 24) or (0x05 shl 16)))
        assertEquals(FAILED, DeliveryReportStatus.of("3gpp2", 0x03 shl 24))
    }
}
