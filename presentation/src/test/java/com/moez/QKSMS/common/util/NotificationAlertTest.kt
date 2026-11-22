package dev.octoshrimpy.quik.common.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation's notification is rebuilt for many reasons (a new text, a deleted message, a read
 * elsewhere). Only a text newer than the one the shown notification already alerted for may alert.
 */
class NotificationAlertTest {

    @Test
    fun theFirstNotificationAlerts() {
        assertTrue(alertsForNewest(shownNewestId = null, newestId = 10))
    }

    @Test
    fun aNewTextAlertsWhileTheLastOneIsStillShown() {
        assertTrue(alertsForNewest(shownNewestId = 10, newestId = 11))
    }

    @Test
    fun rebuildingForTheSameTextStaysQuiet() {
        assertFalse(alertsForNewest(shownNewestId = 11, newestId = 11))
    }

    @Test
    fun deletingTheNewestTextStaysQuiet() {
        assertFalse(alertsForNewest(shownNewestId = 11, newestId = 10))
    }
}
