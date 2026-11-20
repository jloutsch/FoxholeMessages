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
package dev.octoshrimpy.quik.feature.main

import dev.octoshrimpy.quik.feature.conversations.Tab
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.SearchResult
import dev.octoshrimpy.quik.repository.SyncRepository

data class MainState(
    val hasError: Boolean = false,
    val page: MainPage = Inbox(),
    val drawerOpen: Boolean = false,
    val showRating: Boolean = false,
    val syncing: SyncRepository.SyncProgress = SyncRepository.SyncProgress.Idle,
    // The one-time move of this app's own data into the new database is running
    val movingData: Boolean = false,
    val defaultSms: Boolean = true,
    val smsPermission: Boolean = true,
    val contactPermission: Boolean = true,
    val notificationPermission: Boolean = true,
    val scheduledConversationIds: Set<Long> = emptySet(),
    val tabData: Map<Tab, List<Conversation>?> = emptyMap(),
    // Starred is the one tab that lists messages rather than conversations, so it can't ride
    // along in tabData with the category tabs.
    val starredMessages: List<Message>? = null,
    val tabUnreadCounts: Map<Tab, Long> = emptyMap()
)

sealed class MainPage

data class Inbox(
    val addContact: Boolean = false,
    val markPinned: Boolean = true,
    val markRead: Boolean = false,
    val selected: Int = 0
) : MainPage()

data class Searching(
    val data: List<SearchResult>? = null
) : MainPage()

data class Archived(
    val addContact: Boolean = false,
    val markPinned: Boolean = true,
    val markRead: Boolean = false,
    val data: List<Conversation>? = null,
    val selected: Int = 0
) : MainPage()
