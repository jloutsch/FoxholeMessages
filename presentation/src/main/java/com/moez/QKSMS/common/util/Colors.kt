/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common.util

import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.core.content.res.getColorOrThrow
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.color.MaterialColors
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.extensions.getColorCompat
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.util.Preferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlin.math.pow

@Singleton
class Colors @Inject constructor(
    private val context: Context,
    private val prefs: Preferences
) {

    /**
     * [theme] is the colour as picked. On screens, use the Material 3 tones made from it instead:
     * they always contrast with the page, and with each other.
     */
    data class Theme(val theme: Int, private val light: Boolean, private val colors: Colors) {
        private val roles by lazy { MaterialColors.getColorRoles(theme, light) }

        /** Text and icons on a page background, and small strong fills. */
        val accent get() = roles.accent
        val onAccent get() = roles.onAccent

        /** Fills: bubbles, avatars, buttons. */
        val container get() = roles.accentContainer
        val onContainer get() = roles.onAccentContainer

        val highlight by lazy { colors.highlightColorForTheme(theme) }
        val textPrimary by lazy { colors.textPrimaryOnThemeForColor(theme) }
        val textSecondary by lazy { colors.textSecondaryOnThemeForColor(theme) }
        val textTertiary by lazy { colors.textTertiaryOnThemeForColor(theme) }
    }

    val materialColors: List<List<Int>> = listOf(
        R.array.material_red,
        R.array.material_pink,
        R.array.material_purple,
        R.array.material_deep_purple,
        R.array.material_indigo,
        R.array.material_blue,
        R.array.material_light_blue,
        R.array.material_cyan,
        R.array.material_teal,
        R.array.material_green,
        R.array.material_light_green,
        R.array.material_lime,
        R.array.material_yellow,
        R.array.material_amber,
        R.array.material_orange,
        R.array.material_deep_orange,
        R.array.material_brown,
        R.array.material_gray,
        R.array.material_blue_gray)
            .map { res -> context.resources.obtainTypedArray(res) }
            .map { typedArray -> (0 until typedArray.length()).map(typedArray::getColorOrThrow) }

    private val randomColors: List<Int> = context.resources.obtainTypedArray(R.array.random_colors)
            .let { typedArray -> (0 until typedArray.length()).map(typedArray::getColorOrThrow) }

    private val minimumContrastRatio = 2

    // Cache these values so they don't need to be recalculated
    private val primaryTextLuminance = measureLuminance(context.getColorCompat(R.color.textPrimaryDark))
    private val secondaryTextLuminance = measureLuminance(context.getColorCompat(R.color.textSecondaryDark))
    private val tertiaryTextLuminance = measureLuminance(context.getColorCompat(R.color.textTertiaryDark))

    fun theme(recipient: Recipient? = null): Theme {
        val conversationPref = recipient?.let { prefs.theme(it.id) }
        return Theme(pickThemeColor(
            conversationColor = conversationPref?.takeIf { it.isSet }?.get(),
            autoColor = recipient?.takeIf { prefs.autoColor.get() }?.let(::generateColor),
            globalColor = prefs.theme().get(),
            systemColor = systemColor()
        ), light = !isNight(), colors = this)
    }

    /**
     * How to build the Material 3 palette: from the wallpaper if "Use system colors" is on, otherwise
     * from the chosen theme colour. Only has an effect on Android 12+.
     */
    fun paletteOptions(): DynamicColorsOptions = DynamicColorsOptions.Builder()
        .apply { if (!prefs.systemColors.get()) setContentBasedSource(prefs.theme().get()) }
        .build()

    /** A context with the palette that screens have, for what's drawn outside one: the widget. */
    fun screenContext(): Context {
        val night = if (isNight()) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night

        val themed = ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.AppTheme)
        return DynamicColors.wrapContextIfAvailable(themed, paletteOptions()).also { screen ->
            if (prefs.black.get()) screen.theme.applyStyle(R.style.ThemeOverlay_Foxhole_Black, true)
        }
    }

    // The app's own setting, not context's configuration: the application context doesn't follow it
    private fun isNight(): Boolean = isNight(
        nightMode = prefs.nightMode.get(),
        autoNight = prefs.night.get(),
        systemNight = Resources.getSystem().configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    )

    /** Emits again whenever any setting that decides [theme] changes. */
    fun themeChanges(recipient: Recipient? = null): Flow<Theme> =
        listOfNotNull(
            prefs.theme().asFlow().map { },
            recipient?.let { prefs.theme(it.id).asFlow().map { } },
            prefs.autoColor.asFlow().map { },
            prefs.systemColors.asFlow().map { }
        ).merge()
            .map { theme(recipient) }
            .distinctUntilChanged()

    /** The wallpaper accent when "Use system colors" is on (Android 12+ only), otherwise null. */
    private fun systemColor(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.systemColors.get())
            context.getColor(android.R.color.system_accent1_500)
        else null

    fun highlightColorForTheme(theme: Int): Int = FloatArray(3)
            .apply { Color.colorToHSV(theme, this) }
            .let { hsv -> hsv.apply { set(2, 0.75f) } } // 75% value
            .let { hsv -> Color.HSVToColor(85, hsv) } // 33% alpha

    fun textPrimaryOnThemeForColor(color: Int): Int = color
            .let { theme -> measureLuminance(theme) }
            .let { themeLuminance -> primaryTextLuminance / themeLuminance }
            .let { contrastRatio -> contrastRatio < minimumContrastRatio }
            .let { contrastRatio -> if (contrastRatio) R.color.textPrimary else R.color.textPrimaryDark }
            .let { res -> context.getColorCompat(res) }

    fun textSecondaryOnThemeForColor(color: Int): Int = color
            .let { theme -> measureLuminance(theme) }
            .let { themeLuminance -> secondaryTextLuminance / themeLuminance }
            .let { contrastRatio -> contrastRatio < minimumContrastRatio }
            .let { contrastRatio -> if (contrastRatio) R.color.textSecondary else R.color.textSecondaryDark }
            .let { res -> context.getColorCompat(res) }

    fun textTertiaryOnThemeForColor(color: Int): Int = color
            .let { theme -> measureLuminance(theme) }
            .let { themeLuminance -> tertiaryTextLuminance / themeLuminance }
            .let { contrastRatio -> contrastRatio < minimumContrastRatio }
            .let { contrastRatio -> if (contrastRatio) R.color.textTertiary else R.color.textTertiaryDark }
            .let { res -> context.getColorCompat(res) }

    /**
     * Measures the luminance value of a color to be able to measure the contrast ratio between two materialColors
     * Based on https://stackoverflow.com/a/9733420
     */
    private fun measureLuminance(color: Int): Double {
        val array = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
                .map { if (it < 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

        return 0.2126 * array[0] + 0.7152 * array[1] + 0.0722 * array[2] + 0.05
    }

    private fun generateColor(recipient: Recipient): Int {
        val index = recipient.address.hashCode().absoluteValue % randomColors.size
        return randomColors[index]
    }
}

/**
 * Which colour a screen or conversation uses: its own colour if one was picked, then the automatic
 * contact colour, then the system (wallpaper) colour if that's on, then the chosen theme colour.
 */
internal fun pickThemeColor(conversationColor: Int?, autoColor: Int?, globalColor: Int, systemColor: Int?): Int =
    conversationColor ?: autoColor ?: systemColor ?: globalColor

/** Whether screens are dark now, the same way NightModeManager decides it. */
internal fun isNight(nightMode: Int, autoNight: Boolean, systemNight: Boolean): Boolean = when (nightMode) {
    Preferences.NIGHT_MODE_SYSTEM -> systemNight
    Preferences.NIGHT_MODE_ON -> true
    Preferences.NIGHT_MODE_AUTO -> autoNight
    else -> false
}
