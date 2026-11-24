package dev.octoshrimpy.quik.common.util

import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import dev.octoshrimpy.quik.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fork issue #42: "Use system colours" replaces only the global theme colour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeColorTest {

    private val global = 0xFF0097A7.toInt()
    private val system = 0xFF6750A4.toInt()
    private val conversation = 0xFFE91E63.toInt()
    private val auto = 0xFF4CAF50.toInt()

    /** #152: text on bubbles and buttons, and accents on the page, stay readable for any picked colour. */
    @Test
    fun tonesMadeFromAnyColourContrast() {
        val picked = listOf(global, system, conversation, auto, 0xFFFFEB3B.toInt(), 0xFFFFEBEE.toInt(),
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8C9EFF.toInt())
        for (color in picked) for (light in listOf(true, false)) {
            val tones = MaterialColors.getColorRoles(color, light)
            // The page (surface) colours of the fallback palette
            val page = if (light) 0xFFF5FAFB.toInt() else 0xFF0F1415.toInt()
            val contrasts = listOf(
                ColorUtils.calculateContrast(tones.onAccentContainer, tones.accentContainer),
                ColorUtils.calculateContrast(tones.onAccent, tones.accent),
                ColorUtils.calculateContrast(tones.accent, page)
            )
            assertTrue("${Integer.toHexString(color)} light=$light $contrasts", contrasts.all { it >= 4.5 })
        }
    }

    @Test
    fun tonesFollowTheNightModeSetting() {
        assertTrue(isNight(Preferences.NIGHT_MODE_ON, autoNight = false, systemNight = false))
        assertFalse(isNight(Preferences.NIGHT_MODE_OFF, autoNight = true, systemNight = true))
        assertTrue(isNight(Preferences.NIGHT_MODE_SYSTEM, autoNight = false, systemNight = true))
        assertFalse(isNight(Preferences.NIGHT_MODE_SYSTEM, autoNight = true, systemNight = false))
        assertTrue(isNight(Preferences.NIGHT_MODE_AUTO, autoNight = true, systemNight = false))
        assertFalse(isNight(Preferences.NIGHT_MODE_AUTO, autoNight = false, systemNight = true))
    }

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
