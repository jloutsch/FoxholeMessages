package dev.octoshrimpy.quik.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fork issue #42: "Use system colours" replaces only the global theme colour. */
class ThemeColorTest {

    private val global = 0xFF0097A7.toInt()
    private val system = 0xFF6750A4.toInt()
    private val conversation = 0xFFE91E63.toInt()
    private val auto = 0xFF4CAF50.toInt()

    @Test
    fun systemColourReplacesTheGlobalColour() =
        assertEquals(system, pickThemeColor(conversationColor = null, autoColor = null, globalColor = global, systemColor = system))

    @Test
    fun aConversationsOwnColourStillWins() =
        assertEquals(conversation, pickThemeColor(conversationColor = conversation, autoColor = auto, globalColor = global, systemColor = system))

    @Test
    fun automaticContactColoursStillWin() =
        assertEquals(auto, pickThemeColor(conversationColor = null, autoColor = auto, globalColor = global, systemColor = system))

    @Test
    fun switchOffOrBeforeAndroid12UsesTheGlobalColour() =
        assertEquals(global, pickThemeColor(conversationColor = null, autoColor = null, globalColor = global, systemColor = null))
}
