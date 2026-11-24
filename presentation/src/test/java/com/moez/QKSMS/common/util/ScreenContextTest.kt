package dev.octoshrimpy.quik.common.util

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.util.PreferenceStore
import dev.octoshrimpy.quik.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fork issue #152: the widget's colours follow the app's night setting, not the phone's. Run as
 * Android 11, where the fallback palette is used, so the colours are known.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenContextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val raw = context.getSharedPreferences("screen_context_test", Context.MODE_PRIVATE)
    private val prefs = Preferences(context, PreferenceStore(raw), raw)
    private val colors = Colors(context, prefs)

    private fun surface() = colors.screenContext().resolveThemeColor(R.attr.colorSurface)

    @Before
    fun clear() {
        raw.edit().clear().commit()
    }

    @Test
    @Config(qualifiers = "night")
    fun lightAppOnADarkPhoneIsLight() {
        prefs.nightMode.set(Preferences.NIGHT_MODE_OFF)
        assertEquals(0xFFF5FAFB.toInt(), surface())
    }

    @Test
    fun darkAppOnALightPhoneIsDark() {
        prefs.nightMode.set(Preferences.NIGHT_MODE_ON)
        assertEquals(0xFF0F1415.toInt(), surface())
    }

    @Test
    fun blackModeIsBlackOnlyAtNight() {
        prefs.black.set(true)
        prefs.nightMode.set(Preferences.NIGHT_MODE_ON)
        assertEquals(Color.BLACK, surface())
        prefs.nightMode.set(Preferences.NIGHT_MODE_OFF)
        assertNotEquals(Color.BLACK, surface())
    }

    @Test
    fun textIsReadableOnTheSurface() {
        for (mode in listOf(Preferences.NIGHT_MODE_ON, Preferences.NIGHT_MODE_OFF)) {
            prefs.nightMode.set(mode)
            val screen = colors.screenContext()
            for (text in listOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary)) {
                val contrast = ColorUtils.calculateContrast(screen.resolveThemeColor(text), surface())
                assertTrue("mode $mode contrast $contrast", contrast >= 4.5)
            }
        }
    }
}
