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
package dev.octoshrimpy.quik.feature.themepicker

import dev.octoshrimpy.quik.util.Preference
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import kotlinx.coroutines.flow.combine
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.manager.WidgetManager
import dev.octoshrimpy.quik.util.Preferences
import javax.inject.Inject
import javax.inject.Named

class ThemePickerPresenter @Inject constructor(
    prefs: Preferences,
    @Named("recipientId") private val recipientId: Long,
    private val colors: Colors,
    private val widgetManager: WidgetManager
) : QkPresenter<ThemePickerView, ThemePickerState>(ThemePickerState(recipientId = recipientId)) {

    private val theme: Preference<Int> = prefs.theme(recipientId)

    override fun bindIntents(view: ThemePickerView) {
        super.bindIntents(view)
        val scope = view.bindScope

        theme.asFlow().collectIn(scope) { color -> view.setCurrentTheme(color) }

        // Update the theme when a material theme is clicked
        view.themeSelected().collectIn(scope) { color -> applyTheme(color) }

        // Update the color of the apply button; remember the latest colour for Apply
        var latestHsvColor: Int? = null
        view.hsvThemeSelected().collectIn(scope) { color ->
            latestHsvColor = color
            newState { copy(newColor = color) }
            val textColor = colors.textPrimaryOnThemeForColor(color)
            newState { copy(newTextColor = textColor) }
        }

        // Toggle the visibility of the apply group
        combine(theme.asFlow(), view.hsvThemeSelected()) { old, new -> old != new }
            .collectIn(scope) { themeChanged -> newState { copy(applyThemeVisible = themeChanged) } }

        // Update the theme, when apply is clicked (ignored until a colour has been picked, as before)
        view.applyHsvThemeClicks().collectIn(scope) { latestHsvColor?.let(::applyTheme) }

        // Reset the theme
        view.clearHsvThemeClicks().collectIn(scope) { view.setCurrentTheme(theme.get()) }
    }

    private fun applyTheme(color: Int) {
        theme.set(color)
        if (recipientId == 0L) {
            widgetManager.updateTheme()
        }
    }

}