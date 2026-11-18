package dev.octoshrimpy.quik.classifier

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tab a conversation sits under, shared by the inbox, notifications and the widget. */
class InboxTabTest {

    @Test
    fun lastMessageDecidesTheTab() {
        assertEquals("TRANSACTIONAL", InboxTab.of(null, "TRANSACTIONAL"))
        assertEquals("PROMOTIONAL", InboxTab.of(null, "PROMOTIONAL"))
    }

    @Test
    fun anythingElseIsPersonal() {
        assertEquals("PERSONAL", InboxTab.of(null, "UNCLASSIFIED"))
        assertEquals("PERSONAL", InboxTab.of(null, "PERSONAL"))
        assertEquals("PERSONAL", InboxTab.of(null, null))
    }

    @Test
    fun moveToOverrideWins() {
        assertEquals("PERSONAL", InboxTab.of("PERSONAL", "PROMOTIONAL"))
        assertEquals("PROMOTIONAL", InboxTab.of("PROMOTIONAL", null))
        assertEquals("PROMOTIONAL", InboxTab.of("", "PROMOTIONAL"))  // empty override = none
    }
}
