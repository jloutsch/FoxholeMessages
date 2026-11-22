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

import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkFlowViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import androidx.recyclerview.widget.ItemTouchHelper
import dev.octoshrimpy.quik.classifier.MessageCategoryBackfill
import dev.octoshrimpy.quik.worker.ClassifyExistingMessagesWorker
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.ExternalNavigator
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.feature.conversations.Tab
import dev.octoshrimpy.quik.interactor.DeleteConversations
import dev.octoshrimpy.quik.interactor.MarkAllSeen
import dev.octoshrimpy.quik.interactor.MarkArchived
import dev.octoshrimpy.quik.interactor.MarkPinned
import dev.octoshrimpy.quik.interactor.MarkRead
import dev.octoshrimpy.quik.interactor.MarkUnarchived
import dev.octoshrimpy.quik.interactor.MarkUnpinned
import dev.octoshrimpy.quik.interactor.MarkUnread
import dev.octoshrimpy.quik.interactor.SpeakThreads
import dev.octoshrimpy.quik.interactor.SyncContacts
import dev.octoshrimpy.quik.interactor.SyncMessages
import dev.octoshrimpy.quik.listener.ContactAddedListener
import dev.octoshrimpy.quik.manager.ChangelogManager
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.manager.RatingManager
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.EmojiReactionRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import dev.octoshrimpy.quik.repository.SenderCategoryRuleRepository
import dev.octoshrimpy.quik.repository.SyncRepository
import dev.octoshrimpy.quik.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.DelicateCoroutinesApi

class MainViewModel @Inject constructor(
    context: Context,
    contactAddedListener: ContactAddedListener,
    markAllSeen: MarkAllSeen,
    syncRepository: SyncRepository,
    private val changelogManager: ChangelogManager,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val deleteConversations: DeleteConversations,
    private val markArchived: MarkArchived,
    private val markPinned: MarkPinned,
    private val markRead: MarkRead,
    private val markUnarchived: MarkUnarchived,
    private val markUnpinned: MarkUnpinned,
    private val markUnread: MarkUnread,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val senderCategoryRuleRepo: SenderCategoryRuleRepository,
    private val speakThreads: SpeakThreads,
    private val navigator: Navigator,
    private val externalNavigator: ExternalNavigator,
    private val permissionManager: PermissionManager,
    private val prefs: Preferences,
    private val ratingManager: RatingManager,
    private val reactions: EmojiReactionRepository,
    private val syncContacts: SyncContacts,
    private val syncMessages: SyncMessages
) : QkFlowViewModel<MainView, MainState>(
    MainState(
        page = Inbox()
    )
) {

    // The archived list arrives continuously; kept so opening Archived shows it at once
    private var latestArchived: List<Conversation>? = null
    companion object {
        // Bump to re-run the emoji reaction reparse once on every device's next launch
        private const val EMOJI_REPARSE_VERSION = 4
    }

    private var lastArchivedThreadIds = listOf<Long>(0)

    // Released when the ViewModel is cleared, as under RxJava
    private val interactors = listOf(deleteConversations, markAllSeen, markArchived, markUnarchived, syncContacts, syncMessages)

    init {
        // Show the syncing UI. The repository is still RxJava (other screens read it): bridged
        @OptIn(FlowPreview::class) // sample() is stable in practice; still marked preview
        syncRepository.syncProgress.asFlow()
                .sample(16)
                .distinctUntilChanged()
                .collectIn(viewModelScope) { syncing -> newState { copy(syncing = syncing) } }

        // Inbox tabs, Starred and Archived follow the database live, re-queried when the
        // "unread at top" setting changes
        prefs.unreadAtTop.asFlow()
            .distinctUntilChanged()
            .flatMapLatest { unreadAtTop -> observeTabData(conversationRepo, unreadAtTop) }
            .collectIn(viewModelScope) { tabData -> newState { copy(tabData = tabData) } }

        messageRepo.getStarredMessages()
            .collectIn(viewModelScope) { starred -> newState { copy(starredMessages = starred) } }

        prefs.unreadAtTop.asFlow()
            .distinctUntilChanged()
            .flatMapLatest { unreadAtTop -> conversationRepo.getConversations(unreadAtTop, true) }
            .collectIn(viewModelScope) { archived ->
                newState { if (page is Archived) copy(page = page.copy(data = archived)) else this }
                latestArchived = archived
            }

        // Show the rating UI. RatingManager is still RxJava: bridged until Task 29
        ratingManager.shouldShowRating.asFlow()
                .collectIn(viewModelScope) { show -> newState { copy(showRating = show) } }

        // Fetch scheduled messages and assign it to hasScheduledMessage
        scheduledMessageRepo.observeScheduledMessages()
            .map { messages -> messages.map { it.conversationId }.toSet() }
            .collectIn(viewModelScope) { ids -> newState { copy(scheduledConversationIds = ids) } }

        // If we have all permissions and we've never run a sync, run a sync. This will be the case
        // when upgrading from 2.7.3, or if the app's data was cleared
        // (read in the background, #71)
        viewModelScope.launch {
            try {
                val neverSynced = withContext(Dispatchers.IO) {
                    syncRepository.getLastSyncDate() == 0L && permissionManager.isDefaultSms() &&
                            permissionManager.hasReadSms() && permissionManager.hasContacts()
                }
                if (neverSynced) syncMessages.execute(Unit)
            } catch (e: Exception) {
                Timber.w(e)
            }
        }

        // This is only used when we update to a version that newly supports emoji reactions
        reactions.reparseIfFlagged()

        // The EmojiSyncNeeded row above is created by a schema migration, so it only ever fires
        // once per install and can't be used again. This re-runs the same reparse when
        // EMOJI_REPARSE_VERSION is bumped, to recover reaction messages that were received while
        // recognition wasn't working and are now sitting in threads as plain text.
        // When the sorting rules change (MessageCategoryBackfill.CLASSIFIER_VERSION), re-sort once in
        // the background so existing conversations move to the tab the new rules give them;
        // otherwise only messages arriving from now on would benefit. Skipped when the user has
        // turned automatic sorting off.
        if (prefs.autoSortEnabled.get() &&
            prefs.classifierVersion.get() < MessageCategoryBackfill.CLASSIFIER_VERSION) {
            Timber.i("Re-sort requested (stored rules=${prefs.classifierVersion.get()}, current=${MessageCategoryBackfill.CLASSIFIER_VERSION})")
            ClassifyExistingMessagesWorker.trigger(context)
        }

        if (prefs.emojiReparseVersion.get() < EMOJI_REPARSE_VERSION) {
            Timber.i("Emoji reparse requested (stored=${prefs.emojiReparseVersion.get()}, current=$EMOJI_REPARSE_VERSION)")
            reactions.reparseAll(
                onDone = {
                    prefs.emojiReparseVersion.set(EMOJI_REPARSE_VERSION)
                    Timber.i("Emoji reparse finished")
                },
                onError = { error -> Timber.e(error, "Emoji reparse failed") }
            )
        }

        // Sync contacts when we detect a change. The listener is still RxJava: bridged until Task 29
        if (permissionManager.hasContacts()) {
            @OptIn(FlowPreview::class) // debounce is stable in practice; still marked preview
            contactAddedListener.listen().asFlow()
                    .debounce(1000)
                    .flowOn(Dispatchers.IO)
                    .collectIn(viewModelScope) { syncContacts.execute(Unit) }
        }

        ratingManager.addSession()
        markAllSeen.execute(Unit)
    }

    override fun onCleared() {
        interactors.forEach { it.dispose() }
        super.onCleared()
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class) // debounce, flatMapLatest: stable in practice
    override fun bindView(view: MainView) {
        super.bindView(view)

        when {
            !permissionManager.isDefaultSms() -> view.requestDefaultSms()
            !permissionManager.hasReadSms() || !permissionManager.hasContacts() -> view.requestPermissions()
        }

        // when unreadAtTop preference changes, reload the model view data to refresh view
        prefs.unreadAtTop.asFlow()
            .drop(1)
            .debounce(400)
            .collectIn(view.bindScope) { newState { if (page is Inbox) copy(page = Inbox()) else this } }

        // On resume, reflect the default SMS and permission states in the State; if we go from not
        // having all SMS permissions to having them, sync messages. Read in the background.
        var hadAllPermissions: Boolean? = null
        view.activityResumedIntent.filter { resumed -> resumed }.collectIn(view.bindScope) {
            val permissions = withContext(Dispatchers.IO) {
                listOf(permissionManager.isDefaultSms(), permissionManager.hasReadSms(),
                        permissionManager.hasContacts(), permissionManager.hasNotifications())
            }
            val (defaultSms, smsPermission, contactPermission, notificationPermission) = permissions
            newState {
                copy(defaultSms = defaultSms, smsPermission = smsPermission,
                        contactPermission = contactPermission, notificationPermission = notificationPermission)
            }

            val hasAllPermissions = defaultSms && smsPermission && contactPermission
            if (hadAllPermissions == false && hasAllPermissions) {
                syncMessages.execute(Unit)
            }
            hadAllPermissions = hasAllPermissions
        }

        // Launch screen from intent
        view.onNewIntentIntent.collectIn(view.bindScope) { intent ->
            when (intent.getStringExtra("screen")) {
                "compose" -> navigator.showConversation(intent.getLongExtra("threadId", 0))
                "blocking" -> navigator.showBlockedConversations()
            }
        }

        // Show changelog
        if (changelogManager.didUpdate()) {
            if (Locale.getDefault().language.startsWith("en")) {
                @OptIn(DelicateCoroutinesApi::class) // shown once after an update
                GlobalScope.launch(Dispatchers.Main) {
                    val changelog = changelogManager.getChangelog()
                    changelogManager.markChangelogSeen()
                    view.showChangelog(changelog)
                }
            } else {
                changelogManager.markChangelogSeen()
            }
        } else {
            changelogManager.markChangelogSeen()
        }

        view.changelogMoreIntent.collectIn(view.bindScope) { externalNavigator.showChangelog() }

        view.queryChangedIntent
                .debounce(200)
                .map { query -> query.trim().toString() }
                .onEach { query ->
                    if (query.isEmpty()) newState { if (page is Searching) copy(page = Inbox()) else this }
                }
                .filter { query -> query.length >= 2 }
                .distinctUntilChanged()
                .collectIn(view.bindScope) { query ->
                    newState { copy(page = (page as? Searching) ?: Searching()) }
                    val data = withContext(Dispatchers.IO) { conversationRepo.searchConversations(query) }
                    newState { copy(page = Searching(data = data)) }
                }

        // While paused, redraw the list when a theme setting changes; stop listening once resumed
        view.activityResumedIntent
                .flatMapLatest { resumed ->
                    if (resumed) emptyFlow()
                    else merge(
                            prefs.keyChanges.filter { key -> key.contains("theme") }.map { },
                            prefs.autoColor.asFlow().drop(1).map { })
                }
                .collectIn(view.bindScope) { view.themeChanged() }

        view.composeIntent.collectIn(view.bindScope) { navigator.showCompose() }

        view.homeIntent.collectIn(view.bindScope) {
            val state = state.value
            when {
                state.page is Searching -> view.clearSearch()
                state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                state.page is Archived && state.page.selected > 0 -> view.clearSelection()

                else -> newState { copy(drawerOpen = true) }
            }
        }

        view.drawerToggledIntent.collectIn(view.bindScope) { open ->
            newState { copy(drawerOpen = open) }
            view.drawerToggled(open)
        }

        // The previous drawer item: picking the page you're already on does nothing (what
        // distinctUntilChanged did)
        var lastNavItem: NavItem? = null
        view.navigationIntent.collectIn(view.bindScope) { drawerItem ->
            val state = state.value
            newState { copy(drawerOpen = false) }
            when (drawerItem) {
                NavItem.BACK -> when {
                    state.drawerOpen -> Unit
                    state.page is Searching -> view.clearSearch()
                    state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                    state.page is Archived && state.page.selected > 0 -> view.clearSelection()
                    state.page !is Inbox -> {
                        newState { copy(page = Inbox()) }
                    }
                    else -> newState { copy(hasError = true) }
                }
                NavItem.TRASH -> navigator.showTrash()
                NavItem.BACKUP -> navigator.showBackup()
                NavItem.SCHEDULED -> navigator.showScheduled(null)
                NavItem.BLOCKING -> navigator.showBlockedConversations()
                NavItem.MESSAGE_UTILS -> navigator.showMessageUtils()
                NavItem.SETTINGS -> navigator.showSettings()
                NavItem.ABOUT -> navigator.showAbout()
                NavItem.INVITE -> externalNavigator.showInvite()
                else -> Unit
            }

            if (drawerItem != lastNavItem) {
                when (drawerItem) {
                    NavItem.INBOX -> newState { copy(page = Inbox()) }
                    NavItem.ARCHIVED -> newState { copy(page = Archived(data = latestArchived)) }
                    else -> Unit
                }
            }
            lastNavItem = drawerItem
        }

        // The latest selection, which the menu actions apply to (what withLatestFrom read)
        var selectedConversations: List<Long> = emptyList()
        view.conversationsSelectedIntent.collectIn(view.bindScope) { selection -> selectedConversations = selection }

        view.optionsItemIntent.collectIn(view.bindScope) { itemId ->
            // A copy: the live selection list is emptied by clearSelection() before
            // MarkArchived's mark-as-read step reads it, so the conversation stayed unread.
            val conversations = selectedConversations.toList()
            when (itemId) {
                R.id.select_all -> view.toggleSelectAll()

                R.id.archive -> {
                    markArchived.execute(conversations)
                    lastArchivedThreadIds = conversations
                    view.showArchivedSnackbar(lastArchivedThreadIds.count(), true)
                    view.clearSelection()
                }

                R.id.unarchive -> {
                    markUnarchived.execute(conversations)
                    view.showArchivedSnackbar(conversations.count(), false)
                    view.clearSelection()
                }

                R.id.delete -> if (isDefaultSms(view)) view.showDeleteDialog(conversations)

                R.id.add -> {
                    view.clearSelection()
                    val address = conversations.singleOrNull()?.let { conversationId ->
                        withContext(Dispatchers.IO) {
                            conversationRepo.getConversation(conversationId)?.recipients
                                    ?.let { recipients -> recipients[0]?.address?.takeIf { recipients.size == 1 } }
                        }
                    }
                    address?.let(externalNavigator::addContact)
                }

                R.id.pin -> {
                    markPinned.execute(conversations)
                    view.clearSelection()
                }

                R.id.unpin -> {
                    markUnpinned.execute(conversations)
                    view.clearSelection()
                }

                R.id.read -> if (isDefaultSms(view)) {
                    markRead.execute(conversations)
                    view.clearSelection()
                }

                R.id.unread -> if (isDefaultSms(view)) {
                    markUnread.execute(conversations)
                    view.clearSelection()
                }

                R.id.block -> {
                    view.showBlockingDialog(conversations, true)
                    view.clearSelection()
                }

                R.id.move_to -> view.showMoveToDialog(conversations)

                R.id.rename -> {
                    val conversation = conversations.firstOrNull()?.let { conversationId ->
                        withContext(Dispatchers.IO) { conversationRepo.getConversation(conversationId) }
                    }
                    conversation?.let { view.showRenameDialog(it.name) }
                }
            }
        }

        view.rateIntent.collectIn(view.bindScope) {
            externalNavigator.showRating()
            ratingManager.rate()
        }

        view.dismissRatingIntent.collectIn(view.bindScope) { ratingManager.dismiss() }

        // The selected conversations are read in the background (#71), then applied to the current page
        view.conversationsSelectedIntent.collectIn(view.bindScope) { selection ->
            val (add, pin, read) = withContext(Dispatchers.IO) {
                val conversations = selection.mapNotNull(conversationRepo::getConversation)
                val add = conversations.firstOrNull()
                        ?.takeIf { conversations.size == 1 }
                        ?.takeIf { conversation -> conversation.recipients.size == 1 }
                        ?.recipients?.first()
                        ?.takeIf { recipient -> recipient.contact == null } != null
                val pin = conversations.count { it.pinned } * 2 <= conversations.size // at most half pinned
                val read = when (conversations.size) {
                    0    -> false
                    1    -> conversations[0].unread
                    else -> true
                }
                Triple(add, pin, read)
            }
            val selected = selection.size
            newState {
                when (page) {
                    is Inbox -> copy(page = page.copy(addContact = add, markPinned = pin, markRead = read, selected = selected))
                    is Archived -> copy(page = page.copy(addContact = add, markPinned = pin, markRead = read, selected = selected))
                    is Searching -> this // Ignore
                }
            }
        }

        // Delete the conversation
        view.confirmDeleteIntent.collectIn(view.bindScope) { conversations ->
            deleteConversations.execute(conversations.toList())
            view.clearSelection()
        }

        // Archive the conversation (after confirmation, when "Confirm before archiving" is on)
        view.confirmArchiveIntent.collectIn(view.bindScope) { conversations ->
            markArchived.execute(conversations.toList()) {
                lastArchivedThreadIds = conversations.toList()
                view.showArchivedSnackbar(conversations.size, true)
            }
        }

        view.renameConversationIntent.collectIn(view.bindScope) { newConversationName ->
            val conversationId = selectedConversations.firstOrNull() ?: return@collectIn
            view.clearSelection()
            withContext(Dispatchers.IO) {
                conversationRepo.setConversationName(conversationId, newConversationName).await()
            }
        }

        // Move the selected conversations to a category, and remember the choice as a sender rule
        // for each of their recipients so future messages from them sort the same way
        view.moveToIntent.collectIn(view.bindScope) { category ->
            val conversationIds = selectedConversations.toList()
            view.clearSelection()
            withContext(Dispatchers.IO) {
                conversationRepo.updateCategoryOverride(conversationIds, category)
                conversationIds.forEach { conversationId ->
                    conversationRepo.getConversation(conversationId)?.recipients?.forEach { recipient ->
                        senderCategoryRuleRepo.setRule(recipient.address, category)
                    }
                }
            }
        }

        view.swipeConversationIntent.collectIn(view.bindScope) { (threadId, direction) ->
            val action =
                if (direction == ItemTouchHelper.RIGHT) prefs.swipeRight.get()
                else prefs.swipeLeft.get()
            when (action) {
                Preferences.SWIPE_ACTION_ARCHIVE ->
                    if (prefs.confirmArchive.get()) {
                        view.showArchiveDialog(listOf(threadId))
                    } else {
                        markArchived.execute(listOf(threadId)) {
                            lastArchivedThreadIds = listOf(threadId)
                            view.showArchivedSnackbar(1, true)
                        }
                    }
                Preferences.SWIPE_ACTION_DELETE ->
                    view.showDeleteDialog(listOf(threadId))
                Preferences.SWIPE_ACTION_BLOCK ->
                    view.showBlockingDialog(listOf(threadId), true)
                Preferences.SWIPE_ACTION_CALL -> {
                    // Read in the background (#71), then dial on the main thread
                    try {
                        val address = withContext(Dispatchers.IO) {
                            messageRepo.getMessagesSync(threadId).lastOrNull { !it.isMe() }
                                ?.address // most recent non-me msg address
                            ?: conversationRepo.getConversation(threadId)
                                ?.recipients?.firstOrNull()?.address  // first recipient in convo
                        }
                        address?.let(externalNavigator::makePhoneCall)
                    } catch (e: Exception) {
                        Timber.w(e)
                    }
                }
                Preferences.SWIPE_ACTION_READ -> markRead.execute(listOf(threadId))
                Preferences.SWIPE_ACTION_UNREAD -> markUnread.execute(listOf(threadId))
                Preferences.SWIPE_ACTION_SPEAK -> speakThreads.execute(listOf(threadId))
            }
        }

        view.undoArchiveIntent.collectIn(view.bindScope) {
            markUnarchived.execute(lastArchivedThreadIds.toList())
            lastArchivedThreadIds = listOf()
        }

        view.snackbarButtonIntent.collectIn(view.bindScope) {
            val state = state.value
            when {
                !state.defaultSms -> view.requestDefaultSms()
                !state.smsPermission -> view.requestPermissions()
                !state.contactPermission -> view.requestPermissions()
                !state.notificationPermission -> {
                    if (prefs.hasAskedForNotificationPermission.get()) {
                        externalNavigator.showPermissionsInSettings()
                    } else {
                        prefs.hasAskedForNotificationPermission.set(true)
                        view.requestPermissions()
                    }
                }
            }
        }

        // Recompute the tab unread-count badges whenever any tab's conversation list changes (a
        // message read/unread, a new message arriving, or a conversation being moved to another
        // tab via "Move to..."), instead of only on resume, so a moved conversation's unread
        // badge follows it immediately. tabData's lists are already live (a new list on every change),
        // this just derives the counts from that same live data instead of a one-off snapshot.
        // Tied to the view, not the ViewModel: bindView runs again on every rotation, and a
        // ViewModel-lifetime copy would pile up one more (4 count queries per change) each time.
        state
            .map { it.tabData to it.starredMessages }
            .distinctUntilChanged()
            .collectIn(view.bindScope) {
                // Counted on the background thread: newState's block runs on the main thread (#71)
                val counts = withContext(Dispatchers.IO) { buildTabUnreadCounts(conversationRepo, messageRepo) }
                newState { copy(tabUnreadCounts = counts) }
            }

        // Refresh tab contents/unread counts on resume too, as a fallback in case data changed
        // while the process was backgrounded and missed the live listeners above
        view.activityResumedIntent.filter { resumed -> resumed }.collectIn(view.bindScope) {
            val counts = withContext(Dispatchers.IO) { buildTabUnreadCounts(conversationRepo, messageRepo) }
            newState { copy(tabUnreadCounts = counts) }
        }
    }

    private fun isDefaultSms(view: MainView): Boolean =
            permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() }

}

// Message sorting: conversations are bucketed into tabs by category, rather than through the
// MainPage sealed class, since tabs coexist with whichever page is showing. Starred is excluded
// here - it lists messages, not conversations, and is carried separately in MainState.
private fun observeTabData(conversationRepo: ConversationRepository, unreadAtTop: Boolean): Flow<Map<Tab, List<Conversation>?>> {
    val tabs = Tab.values().filter { tab -> tab.category != null }
    val sources = tabs.map { tab ->
        conversationRepo.getConversationsByCategory(unreadAtTop, requireNotNull(tab.category))
    }
    return combine(sources) { lists -> tabs.indices.associate { index -> tabs[index] to lists[index] } }
}

private fun buildTabUnreadCounts(
    conversationRepo: ConversationRepository,
    messageRepo: MessageRepository
): Map<Tab, Long> =
    Tab.values().associateWith { tab ->
        if (tab == Tab.STARRED) messageRepo.getStarredCount()
        else conversationRepo.getUnreadCountByCategory(requireNotNull(tab.category))
    }
