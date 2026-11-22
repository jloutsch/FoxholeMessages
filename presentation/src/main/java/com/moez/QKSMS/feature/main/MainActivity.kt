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

import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import dev.octoshrimpy.quik.common.util.extensions.textChanges
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.drawerOpens
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.util.extensions.clicks
import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.ExternalNavigator
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.util.extensions.autoScrollToStart
import dev.octoshrimpy.quik.common.util.extensions.dismissKeyboard
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.scrapViews
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.common.util.extensions.setVisible
import dev.octoshrimpy.quik.common.widget.TextInputDialog
import dev.octoshrimpy.quik.classifier.Category
import dev.octoshrimpy.quik.feature.blocking.BlockingDialog
import dev.octoshrimpy.quik.databinding.MainActivityBinding
import dev.octoshrimpy.quik.databinding.MainPermissionHintBinding
import dev.octoshrimpy.quik.databinding.MainSyncingBinding
import dev.octoshrimpy.quik.feature.changelog.ChangelogDialog
import dev.octoshrimpy.quik.feature.conversations.ConversationItemTouchCallback
import dev.octoshrimpy.quik.feature.conversations.ConversationsAdapter
import dev.octoshrimpy.quik.feature.conversations.ConversationsPagerAdapter
import dev.octoshrimpy.quik.feature.conversations.StarredMessagesAdapter
import dev.octoshrimpy.quik.feature.conversations.Tab
import dev.octoshrimpy.quik.feature.conversations.badgeText
import dev.octoshrimpy.quik.manager.ChangelogManager
import dev.octoshrimpy.quik.repository.SyncRepository
import javax.inject.Inject
import javax.inject.Provider

class MainActivity : QkThemedActivity(), MainView {

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var activityScope: CoroutineScope
    @Inject lateinit var externalNavigator: ExternalNavigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var conversationsAdapterProvider: Provider<ConversationsAdapter>
    @Inject lateinit var itemTouchCallbackProvider: Provider<ConversationItemTouchCallback>
    @Inject lateinit var starredMessagesAdapter: StarredMessagesAdapter
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var binding: MainActivityBinding
    private lateinit var snackbarBinding: MainPermissionHintBinding
    private lateinit var syncingBinding: MainSyncingBinding

    override val bindScope get() = lifecycleScope

    override val onNewIntentIntent = events<Intent>()
    override val activityResumedIntent = events<Boolean>()
    override val queryChangedIntent by lazy { binding.toolbarSearch.textChanges() }
    override val composeIntent by lazy { binding.compose.clicks() }
    override val drawerToggledIntent: Flow<Boolean> by lazy {
        binding.drawerLayout.drawerOpens(Gravity.START)
    }
    override val homeIntent = events<Unit>()
    override val navigationIntent: Flow<NavItem> by lazy {
        merge(
                backPressedEvents,
                binding.drawer.inbox.clicks().map { NavItem.INBOX },
                binding.drawer.archived.clicks().map { NavItem.ARCHIVED },
                binding.drawer.trash.clicks().map { NavItem.TRASH },
                binding.drawer.backup.clicks().map { NavItem.BACKUP },
                binding.drawer.scheduled.clicks().map { NavItem.SCHEDULED },
                binding.drawer.blocking.clicks().map { NavItem.BLOCKING },
                binding.drawer.messageUtils.clicks().map { NavItem.MESSAGE_UTILS },
                binding.drawer.settings.clicks().map { NavItem.SETTINGS },
                binding.drawer.about.clicks().map { NavItem.ABOUT },
                binding.drawer.invite.clicks().map { NavItem.INVITE })
    }
    override val optionsItemIntent = events<Int>()
    override val dismissRatingIntent by lazy { binding.drawer.rateDismiss.clicks() }
    override val rateIntent by lazy { binding.drawer.rateOkay.clicks() }
    override val conversationsSelectedIntent by lazy {
        allConversationsAdapters.map { it.selectionChanges }.merge()
    }
    override val confirmDeleteIntent = events<List<Long>>()
    override val confirmArchiveIntent = events<List<Long>>()
    override val renameConversationIntent = events<String>()
    override val moveToIntent = events<String>()
    override val swipeConversationIntent by lazy {
        conversationTabPages.map { it.touchCallback.swipes }.merge()
    }
    override val changelogMoreIntent by lazy { changelogDialog.moreClicks }
    override val undoArchiveIntent = events<Unit>()
    override val snackbarButtonIntent = events<Unit>()

    // Message sorting: the Inbox page shows a ViewPager2 of category tabs instead of the flat
    // recyclerView above, which remains in use for Archived/Search. Each category tab gets its
    // own adapter/touch callback so selection and swipe state stay independent per tab; Starred
    // is a message list rather than a conversation list, so it has neither.
    private val tabPages by lazy {
        Tab.values().map { tab ->
            when (tab) {
                // Starred lists the starred messages themselves rather than the conversations
                // holding them, so it gets its own adapter and no swipe/selection behaviour.
                Tab.STARRED -> ConversationsPagerAdapter.TabPage.Starred(tab, starredMessagesAdapter)
                else -> ConversationsPagerAdapter.TabPage.Conversations(
                    tab, conversationsAdapterProvider.get(), itemTouchCallbackProvider.get()
                )
            }
        }
    }
    private val conversationTabPages by lazy {
        tabPages.filterIsInstance<ConversationsPagerAdapter.TabPage.Conversations>()
    }
    private val conversationsPagerAdapter by lazy { ConversationsPagerAdapter(tabPages) }
    private val allConversationsAdapters by lazy {
        listOf(conversationsAdapter) + conversationTabPages.map { it.conversationsAdapter }
    }

    private val viewModel by lazy {
        ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]
    }
    private val toggle by lazy {
        ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.main_drawer_open_cd,
            0
        )
    }
    private val progressAnimator by lazy {
        ObjectAnimator.ofInt(syncingBinding.syncingProgress, "progress", 0, 0)
    }
    private val changelogDialog by lazy { ChangelogDialog(this) }
    private val backPressedEvents = events<NavItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBack { backPressedEvents.tryEmit(NavItem.BACK) }
        viewModel.bindView(this)
        onNewIntentIntent.tryEmit(intent)

        snackbarBinding = MainPermissionHintBinding.bind(binding.snackbar.inflate()).also {
            it.snackbarButton.setOnClickListener { snackbarButtonIntent.tryEmit(Unit) }
        }

        syncingBinding = MainSyncingBinding.bind(binding.syncing.inflate()).also {
            it.syncingProgress.progressTintList = ColorStateList.valueOf(colors.theme().theme)
            it.syncingProgress.indeterminateTintList = ColorStateList.valueOf(colors.theme().theme)
        }

        toggle.syncState()
        binding.toolbar.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.tryEmit(Unit)
        }

        conversationsAdapter.autoScrollToStart(binding.recyclerView)

        binding.tabPager.adapter = conversationsPagerAdapter
        // Each tab keeps its own selection, so a selection made in one tab stayed active after
        // switching to another, and Delete/Archive/Block then acted on conversations the user could
        // no longer see. Leaving a tab ends selection mode.
        binding.tabPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = clearSelection()
        })
        buildTabStrip()

        // Don't allow clicks to pass through the drawer layout
        binding.drawer.root.setOnClickListener { }

        // Set the theme color tint to the recyclerView, progressbar, and FAB
        theme.collectIn(lifecycleScope) { theme ->
                    // Set the color for the drawer icons
                    val states = arrayOf(
                            intArrayOf(android.R.attr.state_activated),
                            intArrayOf(-android.R.attr.state_activated))

                    ColorStateList(states, intArrayOf(theme.theme,
                        resolveThemeColor(android.R.attr.textColorSecondary)
                    ))
                        .let { tintList ->
                            binding.drawer.inboxIcon.imageTintList = tintList
                            binding.drawer.archivedIcon.imageTintList = tintList
                        }

                    // Miscellaneous views
                    syncingBinding.syncingProgress.progressTintList = ColorStateList.valueOf(theme.theme)
                    syncingBinding.syncingProgress.indeterminateTintList = ColorStateList.valueOf(theme.theme)
                    binding.drawer.rateIcon.setTint(theme.theme)
                    binding.compose.setBackgroundTint(theme.theme)
                    binding.tabStrip.setSelectedTabIndicatorColor(theme.theme)
                    binding.tabStrip.setTabTextColors(
                        resolveThemeColor(android.R.attr.textColorSecondary), theme.theme
                    )

                    // Set the FAB compose icon color
                    binding.compose.setTint(theme.textPrimary)
        }
    }

    private var lastTabUnreadCounts: Map<Tab, Long> = emptyMap()

    private fun tabDisplayName(tab: Tab): String =
        prefs.tabTitle(tab.name).get().takeIf { it.isNotBlank() } ?: getString(tab.titleRes)

    private fun buildTabStrip() {
        TabLayoutMediator(binding.tabStrip, binding.tabPager) { tab, position ->
            tab.text = tabDisplayName(tabPages[position].tab)
        }.attach()

        // TabLayout has no per-tab long-click API, so reach into its tab views directly to
        // let the user rename a tab by long-pressing it.
        val tabViews = binding.tabStrip.getChildAt(0) as? ViewGroup
        tabPages.forEachIndexed { index, page ->
            tabViews?.getChildAt(index)?.setOnLongClickListener {
                showRenameTabDialog(page.tab)
                true
            }
        }
    }

    private fun showRenameTabDialog(tab: Tab) {
        TextInputDialog(this, getString(R.string.tab_rename_hint)) { newName ->
            prefs.tabTitle(tab.name).set(newName.trim())
            updateTabBadges(lastTabUnreadCounts)
        }
            .setText(tabDisplayName(tab))
            .show()
    }

    private fun updateTabBadges(unreadCounts: Map<Tab, Long>) {
        lastTabUnreadCounts = unreadCounts
        tabPages.forEachIndexed { index, page ->
            val count = unreadCounts[page.tab] ?: 0
            val label = tabDisplayName(page.tab)
            binding.tabStrip.getTabAt(index)?.text = page.tab.badgeText(label, count)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        intent ?: return
        super.onNewIntent(intent)
        onNewIntentIntent.tryEmit(intent)
    }

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        allConversationsAdapters.forEach { it.hasScheduledConversation = state.scheduledConversationIds }

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }

        binding.toolbarSearch.setVisible(state.page is Inbox &&
                state.page.selected == 0 ||
                state.page is Searching
        )
        binding.toolbarTitle.setVisible(binding.toolbarSearch.visibility != View.VISIBLE)

        val activeItemCount = when (state.page) {
            is Inbox -> tabPages.getOrNull(binding.tabPager.currentItem)?.adapter?.itemCount ?: 0
            is Archived -> conversationsAdapter.itemCount
            is Searching -> searchAdapter.itemCount
        }

        binding.toolbar.menu.apply {
            findItem(R.id.select_all)?.isVisible =
                (activeItemCount > 1) && selectedConversations != 0
            findItem(R.id.archive)?.isVisible =
                state.page is Inbox && selectedConversations != 0
            findItem(R.id.unarchive)?.isVisible =
                state.page is Archived && selectedConversations != 0
            findItem(R.id.delete)?.isVisible = selectedConversations != 0
            findItem(R.id.add)?.isVisible = addContact && selectedConversations != 0
            findItem(R.id.pin)?.isVisible = markPinned && selectedConversations != 0
            findItem(R.id.unpin)?.isVisible = !markPinned && selectedConversations != 0
            findItem(R.id.read)?.isVisible = ( markRead && selectedConversations != 0 ) ||
                    selectedConversations > 1
            findItem(R.id.unread)?.isVisible = ( !markRead && selectedConversations != 0 ) ||
                    selectedConversations > 1
            findItem(R.id.block)?.isVisible = selectedConversations != 0
            findItem(R.id.rename)?.isVisible = selectedConversations == 1
            findItem(R.id.move_to)?.isVisible = state.page is Inbox && selectedConversations != 0
        }

        binding.drawer.rateLayout.setVisible(state.showRating)

        binding.compose.setVisible(state.page is Inbox || state.page is Archived)
        conversationsAdapter.emptyView = binding.empty.takeIf { state.page is Archived }
        searchAdapter.emptyView = binding.empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(state.page.selected > 0)
                title = getString(R.string.main_title_selected, state.page.selected)
                binding.tabStrip.isVisible = true
                binding.tabPager.isVisible = true
                binding.recyclerView.isVisible = false
                binding.empty.isVisible = false
                tabPages.forEach { page ->
                    when (page) {
                        is ConversationsPagerAdapter.TabPage.Conversations ->
                            page.conversationsAdapter.updateData(state.tabData[page.tab])
                        is ConversationsPagerAdapter.TabPage.Starred ->
                            page.starredAdapter.updateData(state.starredMessages)
                    }
                }
                updateTabBadges(state.tabUnreadCounts)
            }

            is Searching -> {
                showBackButton(true)
                binding.tabStrip.isVisible = false
                binding.tabPager.isVisible = false
                binding.recyclerView.isVisible = true
                if (binding.recyclerView.adapter !== searchAdapter) binding.recyclerView.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                binding.empty.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                showBackButton(state.page.selected > 0)
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> getString(R.string.title_archived)
                }
                binding.tabStrip.isVisible = false
                binding.tabPager.isVisible = false
                binding.recyclerView.isVisible = true
                if (binding.recyclerView.adapter !== conversationsAdapter)
                    binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                binding.empty.setText(R.string.archived_empty_text)
            }
        }

        binding.drawer.inbox.isActivated = state.page is Inbox
        binding.drawer.archived.isActivated = state.page is Archived

        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START) && !state.drawerOpen)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        else if (!binding.drawerLayout.isDrawerVisible(GravityCompat.START) && state.drawerOpen)
            binding.drawerLayout.openDrawer(GravityCompat.START)

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                syncingBinding.root.isVisible = false
                snackbarBinding.root.isVisible = (!state.defaultSms ||
                        !state.smsPermission ||
                        !state.contactPermission ||
                        !state.notificationPermission)
            }

            is SyncRepository.SyncProgress.Running -> {
                syncingBinding.root.isVisible = true
                // Reset: the emoji pass below changes this label, and a later sync reuses the view
                syncingBinding.syncingLabel.setText(R.string.main_syncing)
                syncingBinding.syncingProgress.max = state.syncing.max
                progressAnimator.apply {
                    setIntValues(syncingBinding.syncingProgress.progress, state.syncing.progress)
                }.start()
                syncingBinding.syncingProgress.isIndeterminate = state.syncing.indeterminate
                snackbarBinding.root.isVisible = false
            }

            is SyncRepository.SyncProgress.ParsingEmojis -> {
                syncingBinding.root.isVisible = true
                syncingBinding.syncingLabel.setText(getString(R.string.main_sync_emojis))
                syncingBinding.syncingProgress.max = state.syncing.max
                progressAnimator.apply {
                    setIntValues(syncingBinding.syncingProgress.progress, state.syncing.progress)
                }.start()
                syncingBinding.syncingProgress.isIndeterminate = state.syncing.indeterminate
                snackbarBinding.root.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_default_sms_title)
                snackbarBinding.snackbarMessage.setText(R.string.main_default_sms_message)
                snackbarBinding.snackbarButton.setText(R.string.main_default_sms_change)
            }

            !state.smsPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_sms)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }

            !state.contactPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_contacts)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }

            !state.notificationPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_notifications)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }
        }
    }

    override fun onResume() =
        super.onResume().also { activityResumedIntent.tryEmit(true) }

    override fun onPause() =
        super.onPause().also { activityResumedIntent.tryEmit(false) }

    override fun onDestroy() =
        super.onDestroy().also { activityScope.cancel() }

    override fun showBackButton(show: Boolean) =
        toggle.let {
            it.onDrawerSlide(binding.drawer.root, if (show) 1f else 0f)
            it.drawerArrowDrawable.color = when (show) {
                true -> resolveThemeColor(android.R.attr.textColorSecondary)
                false -> resolveThemeColor(android.R.attr.textColorPrimary)
            }
        }

    override fun requestDefaultSms() =
        externalNavigator.showDefaultSmsDialog(this)

    override fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions += Manifest.permission.POST_NOTIFICATIONS

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)
    }

    override fun clearSearch() {
        dismissKeyboard()
        binding.toolbarSearch.text = null
    }

    override fun clearSelection() = allConversationsAdapters.forEach { it.clearSelection() }

    override fun toggleSelectAll() {
        if (!binding.tabPager.isVisible) {
            conversationsAdapter.toggleSelectAll()
            return
        }

        when (val page = tabPages.getOrNull(binding.tabPager.currentItem)) {
            is ConversationsPagerAdapter.TabPage.Conversations -> page.conversationsAdapter.toggleSelectAll()
            // Starred lists messages and has no selection mode of its own
            is ConversationsPagerAdapter.TabPage.Starred -> Unit
            null -> conversationsAdapter.toggleSelectAll()
        }
    }

    override fun themeChanged() = binding.recyclerView.scrapViews()

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.dialog_delete_message,
                    conversations.size,
                    conversations.size
                )
            )
            .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.tryEmit(conversations) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showArchiveDialog(conversations: List<Long>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_archive_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.dialog_archive_message,
                    conversations.size,
                    conversations.size
                )
            )
            .setPositiveButton(R.string.button_archive) { _, _ -> confirmArchiveIntent.tryEmit(conversations) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showMoveToDialog(conversations: List<Long>) {
        val categories = listOf(Category.PERSONAL, Category.TRANSACTIONAL, Category.PROMOTIONAL)
        val labels: Array<CharSequence> = arrayOf(
            getString(R.string.message_sorting_category_personal),
            getString(R.string.message_sorting_category_transactional),
            getString(R.string.message_sorting_category_promotional)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.main_menu_move_to)
            .setItems(labels) { _, which -> moveToIntent.tryEmit(categories[which].name) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showRenameDialog(conversationName: String) =
        TextInputDialog(
            this,
            getString(R.string.info_name),
            { name -> renameConversationIntent.tryEmit(name) }
        )
            .setText(conversationName)
            .show()

    override fun showChangelog(changelog: ChangelogManager.CumulativeChangelog) =
        changelogDialog.show(changelog)

    override fun showArchivedSnackbar(countConversationsArchived: Int, isArchiving: Boolean) =
        Snackbar.make(
            binding.drawerLayout,
            if (isArchiving) {
                resources.getQuantityString(R.plurals.toast_archived, countConversationsArchived, countConversationsArchived)
            } else {
                resources.getQuantityString(R.plurals.toast_unarchived, countConversationsArchived, countConversationsArchived)
            },
            if (countConversationsArchived < 10) Snackbar.LENGTH_LONG
            else Snackbar.LENGTH_INDEFINITE
        ).let {
            it.setAction(R.string.button_undo) { undoArchiveIntent.tryEmit(Unit) }
            it.setActionTextColor(colors.theme().theme)
            it.show()
        }

    override fun onCreateOptionsMenu(menu: Menu?) =
        menu?.let {
            menuInflater.inflate(R.menu.main, it)
            super.onCreateOptionsMenu(it)
        } ?: false

    override fun onOptionsItemSelected(item: MenuItem) =
        optionsItemIntent.tryEmit(item.itemId).let { true }

    override fun drawerToggled(opened: Boolean) {
        if (opened) {
            dismissKeyboard()
            if (!binding.drawer.inbox.isInTouchMode)
                binding.drawer.inbox.requestFocus()
        } else
            binding.toolbarSearch.requestFocus()
    }
}
