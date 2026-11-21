package dev.octoshrimpy.quik.feature.conversations

import org.junit.Assert.assertEquals
import org.junit.Test

/** #77: Starred always shows its total; the other tabs show unread counts and hide zero. */
class TabBadgeTextTest {

    @Test
    fun starredShowsItsCountEvenAtZero() {
        assertEquals("Starred (0)", Tab.STARRED.badgeText("Starred", 0))
        assertEquals("Starred (12)", Tab.STARRED.badgeText("Starred", 12))
    }

    @Test
    fun otherTabsHideZero() {
        assertEquals("Personal", Tab.PERSONAL.badgeText("Personal", 0))
        assertEquals("Personal (3)", Tab.PERSONAL.badgeText("Personal", 3))
    }
}
