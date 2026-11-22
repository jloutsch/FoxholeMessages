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
package dev.octoshrimpy.quik.common.base

import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.model.Recipient
import androidx.lifecycle.lifecycleScope
import dev.octoshrimpy.quik.model.Conversation
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.core.view.iterator
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeBoolean
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import javax.inject.Inject
import androidx.core.view.WindowCompat

/**
 * Base activity that automatically applies any necessary theme theme settings and colors
 *
 * In most cases, this should be used instead of the base QkActivity, except for when
 * an activity does not depend on the theme
 */
abstract class QkThemedActivity : QkActivity() {

    @Inject lateinit var colors: Colors
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var phoneNumberUtils: PhoneNumberUtils

    /**
     * In case the activity should be themed for a specific conversation, the selected conversation
     * can be changed by pushing the threadId to this subject
     */
    val threadId = MutableStateFlow(0L)

    /**
     * Switch the theme if the threadId changes
     * Set it based on the latest message in the conversation
     */
    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest is stable in practice; still marked experimental
    val theme: Flow<Colors.Theme> = threadId
            .flatMapLatest { threadId ->
                // Looked up in the background (#71). 0 means no conversation, so there's nothing to read.
                val conversation = if (threadId == 0L) null
                    else withContext(Dispatchers.IO) { conversationRepo.getConversation(threadId) }
                when {
                    conversation == null -> flowOf(null)

                    conversation.recipients.size == 1 -> flowOf(conversation.recipients.first())

                    else -> messageRepo.getLastIncomingMessage(conversation.id)
                            .mapNotNull { messages -> messages.firstOrNull() }
                            .distinctUntilChangedBy { message -> message.address }
                            .mapNotNull { message ->
                                conversation.recipients.find { recipient ->
                                    phoneNumberUtils.compare(recipient.address, message.address)
                                }
                            }
                            .map<Recipient, Recipient?> { it }
                            .onStart { emit(conversation.recipients.firstOrNull()) }
                            .distinctUntilChanged()
                }
            }
            .flatMapLatest { recipient -> colors.themeChanges(recipient) }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getActivityThemeRes(prefs.black.get()))
        super.onCreate(savedInstanceState)

        // When certain preferences change, we need to recreate the activity
        val triggers = listOf(prefs.nightMode, prefs.night, prefs.black, prefs.textSize, prefs.systemFont)
        @OptIn(FlowPreview::class) // debounce is stable in practice; still marked preview
        triggers.map { it.asFlow().drop(1).map { } }.merge()
                .debounce(400)
                .onEach { recreate() }
                .launchIn(lifecycleScope)

        // We can only set light nav bar on API 27 in attrs, but we can do it in API 26 here
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            val night = !resolveThemeBoolean(R.attr.isLightTheme)
            WindowCompat.getInsetsController(window, window.decorView).run {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
        }

        // Some devices don't let you modify android.R.attr.navigationBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION") // ignored once the app draws behind the bars; still used below that
            window.navigationBarColor = resolveThemeColor(android.R.attr.windowBackground)
        }

        // Set the color for the recent apps title
        val toolbarColor = resolveThemeColor(R.attr.colorPrimary)
        val taskDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityManager.TaskDescription.Builder()
                .setLabel(getString(R.string.app_name))
                .setPrimaryColor(toolbarColor)
                .build()
        } else {
            val icon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            @Suppress("DEPRECATION") ActivityManager.TaskDescription(getString(R.string.app_name), icon, toolbarColor)
        }
        setTaskDescription(taskDesc)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // Set the color for the overflow and navigation icon
        val textSecondary = resolveThemeColor(android.R.attr.textColorSecondary)
        toolbar?.overflowIcon = toolbar?.overflowIcon?.apply { setTint(textSecondary) }

        // Update the colours of the menu items
        combine(menu, theme) { menu, theme ->
            menu.iterator().forEach { menuItem ->
                val tint = when (menuItem.itemId) {
                    in getColoredMenuItems() -> theme.theme
                    else -> textSecondary
                }

                menuItem.icon = menuItem.icon?.apply { setTint(tint) }
            }
        }.launchIn(lifecycleScope)
    }

    open fun getColoredMenuItems(): List<Int> {
        return listOf()
    }

    /**
     * This can be overridden in case an activity does not want to use the default themes
     */
    open fun getActivityThemeRes(black: Boolean) = when {
        black -> R.style.AppTheme_Black
        else -> R.style.AppTheme
    }

}