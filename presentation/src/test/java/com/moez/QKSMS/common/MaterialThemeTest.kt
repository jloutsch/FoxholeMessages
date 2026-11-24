package dev.octoshrimpy.quik.common

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fork issue #152: every screen colour comes from the Material 3 palette, in light, night and black. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaterialThemeTest {

    private fun themed(style: Int, black: Boolean = false): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), style).apply {
            if (black) theme.applyStyle(R.style.ThemeOverlay_Foxhole_Black, true)
        }

    private fun checkPalette(context: Context) {
        val surface = context.resolveThemeColor(R.attr.colorSurface)

        // Edge-to-edge pads the bars with the window background, so it must match the toolbar
        assertEquals(surface, context.resolveThemeColor(android.R.attr.windowBackground))

        for (background in listOf(surface, context.resolveThemeColor(R.attr.bubbleColor))) {
            for (text in listOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary)) {
                val contrast = ColorUtils.calculateContrast(context.resolveThemeColor(text), background)
                assertTrue("contrast $contrast", contrast >= 4.5)
            }
        }
    }

    @Test
    fun lightScreensUseThePalette() {
        checkPalette(themed(R.style.AppTheme))
        checkPalette(themed(R.style.AppThemeDialog))
    }

    @Test
    @Config(qualifiers = "night")
    fun nightScreensUseThePalette() {
        checkPalette(themed(R.style.AppTheme))
        checkPalette(themed(R.style.AppThemeDialog))
    }

    @Test
    @Config(qualifiers = "night")
    fun blackModeIsPureBlackAtNight() {
        val context = themed(R.style.AppTheme, black = true)
        checkPalette(context)
        assertEquals(Color.BLACK, context.resolveThemeColor(R.attr.colorSurface))
    }

    /** The QRF Reply popup must still be a floating, dimmed window, as AppCompat's dialog theme made it. */
    @Test
    fun theReplyPopupIsStillADialogWindow() {
        val attributes = listOf(
            android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent, android.R.attr.windowFrame,
            android.R.attr.windowCloseOnTouchOutside, android.R.attr.backgroundDimEnabled,
            android.R.attr.windowAnimationStyle, android.R.attr.windowContentOverlay,
            android.R.attr.windowSoftInputMode, android.R.attr.windowFullscreen,
            R.attr.windowActionBar, R.attr.windowNoTitle, R.attr.windowActionModeOverlay,
            R.attr.windowMinWidthMajor, R.attr.windowMinWidthMinor
        )
        fun values(style: Int) = themed(style).let { context ->
            attributes.associate { attribute ->
                val value = TypedValue()
                context.theme.resolveAttribute(attribute, value, true)
                context.resources.getResourceEntryName(attribute) to (value.type to value.data)
            }
        }
        val dialog = values(androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog) +
            // The app's old popup theme turned this off, and has no title bar of its own
            mapOf("windowActionBar" to values(R.style.AppThemeDialog).getValue("windowActionBar"))
        assertEquals(dialog, values(R.style.AppThemeDialog))
    }

    @Test
    fun blackModeChangesNothingByDay() = assertEquals(
        themed(R.style.AppTheme).resolveThemeColor(R.attr.colorSurface),
        themed(R.style.AppTheme, black = true).resolveThemeColor(R.attr.colorSurface)
    )
}
