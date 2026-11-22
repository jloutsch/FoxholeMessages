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

import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.common.base.QkFlowView
import android.content.Intent
import dev.octoshrimpy.quik.manager.ChangelogManager

interface MainView : QkFlowView<MainState> {

    val onNewIntentIntent: Flow<Intent>
    val activityResumedIntent: Flow<Boolean>
    val queryChangedIntent: Flow<CharSequence>
    val composeIntent: Flow<Unit>
    val drawerToggledIntent: Flow<Boolean>
    val homeIntent: Flow<Unit>
    val navigationIntent: Flow<NavItem>
    val optionsItemIntent: Flow<Int>
    val dismissRatingIntent: Flow<Unit>
    val rateIntent: Flow<Unit>
    val conversationsSelectedIntent: Flow<List<Long>>
    val confirmDeleteIntent: Flow<List<Long>>
    val confirmArchiveIntent: Flow<List<Long>>
    val moveToIntent: Flow<String>
    val renameConversationIntent: Flow<String>
    val swipeConversationIntent: Flow<Pair<Long, Int>>
    val changelogMoreIntent: Flow<Unit>
    val undoArchiveIntent: Flow<Unit>
    val snackbarButtonIntent: Flow<Unit>

    fun requestDefaultSms()
    fun requestPermissions()
    fun clearSearch()
    fun clearSelection()
    fun toggleSelectAll()
    fun themeChanged()
    fun showBlockingDialog(conversations: List<Long>, block: Boolean)
    fun showDeleteDialog(conversations: List<Long>)
    fun showArchiveDialog(conversations: List<Long>)
    fun showMoveToDialog(conversations: List<Long>)
    fun showRenameDialog(conversationName: String)
    fun showChangelog(changelog: ChangelogManager.CumulativeChangelog)
    fun showArchivedSnackbar(countConversationsArchived: Int, isArchiving: Boolean)
    fun drawerToggled(opened: Boolean)
}

enum class NavItem { BACK, INBOX, ARCHIVED, TRASH, BACKUP, SCHEDULED, BLOCKING, MESSAGE_UTILS, SETTINGS, ABOUT, INVITE }
