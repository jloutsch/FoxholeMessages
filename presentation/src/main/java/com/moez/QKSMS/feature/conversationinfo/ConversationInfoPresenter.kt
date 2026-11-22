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
package dev.octoshrimpy.quik.feature.conversationinfo

import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkFlowPresenter
import kotlinx.coroutines.flow.map
import android.content.Context
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.ExternalNavigator
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.util.ClipboardUtils
import dev.octoshrimpy.quik.common.util.extensions.makeToast
import dev.octoshrimpy.quik.feature.conversationinfo.ConversationInfoItem.ConversationInfoMedia
import dev.octoshrimpy.quik.feature.conversationinfo.ConversationInfoItem.ConversationInfoRecipient
import dev.octoshrimpy.quik.interactor.DeleteConversations
import dev.octoshrimpy.quik.interactor.MarkArchived
import dev.octoshrimpy.quik.interactor.MarkPinned
import dev.octoshrimpy.quik.interactor.MarkUnarchived
import dev.octoshrimpy.quik.interactor.MarkUnpinned
import dev.octoshrimpy.quik.interactor.MarkUnread
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import javax.inject.Inject
import javax.inject.Named

class ConversationInfoPresenter @Inject constructor(
    @Named("threadId") private val threadId: Long,
    messageRepo: MessageRepository,
    private val context: Context,
    private val prefs: Preferences,
    private val conversationRepo: ConversationRepository,
    private val deleteConversations: DeleteConversations,
    private val markUnread: MarkUnread,
    private val markArchived: MarkArchived,
    private val markUnarchived: MarkUnarchived,
    private val markPinned: MarkPinned,
    private val markUnpinned: MarkUnpinned,
    private val navigator: Navigator,
    private val externalNavigator: ExternalNavigator,
    private val permissionManager: PermissionManager
): QkFlowPresenter<ConversationInfoView, ConversationInfoState>(
        ConversationInfoState(threadId = threadId)
) {

    // The current conversation; null until it loads (taps before then are ignored, as withLatestFrom did)
    private val conversation = MutableStateFlow<Conversation?>(null)

    init {
        // A deleted conversation arrives as null, which closes the screen (hasError)
        conversationRepo.observeConversation(threadId).collectIn(presenterScope) { conversation ->
            when {
                conversation == null -> newState { copy(hasError = true) }
                conversation.id != 0L -> this.conversation.value = conversation
            }
        }

        combine(
                conversation.filterNotNull(),
                messageRepo.getPartsForConversation(threadId),
                prefs.backgroundUri(threadId).asFlow()
        ) { conversation, parts, backgroundUri ->
            val data = mutableListOf<ConversationInfoItem>()

            data += conversation.recipients.map(::ConversationInfoRecipient)
            data += ConversationInfoItem.ConversationInfoSettings(
                    name = conversation.name,
                    recipients = conversation.recipients,
                    archived = conversation.archived,
                    blocked = conversation.blocked,
                    pinned = conversation.pinned,
                    backgroundUri = backgroundUri)
            data += parts.map(::ConversationInfoMedia)
            data
        }.collectIn(presenterScope) { data -> newState { copy(data = data) } }
    }

    override fun onCleared() {
        // What `disposables +=` held for these
        markArchived.dispose()
        markUnarchived.dispose()
        deleteConversations.dispose()
        super.onCleared()
    }

    override fun bindIntents(view: ConversationInfoView) {
        super.bindIntents(view)

        // Add or display the contact. Runs in presenterScope: opening Contacts detaches this screen,
        // which must not cancel the lookup (under RxJava this was scoped to ON_DESTROY)
        view.recipientClicks().collectIn(view.bindScope) { recipientId ->
            presenterScope.launch {
                val recipient = withContext(Dispatchers.IO) { conversationRepo.getRecipient(recipientId) }
                        ?: return@launch
                recipient.contact?.lookupKey?.let(externalNavigator::showContact)
                        ?: externalNavigator.addContact(recipient.address)
            }
        }

        // Copy phone number
        view.recipientLongClicks().collectIn(view.bindScope) { recipientId ->
            val address = withContext(Dispatchers.IO) { conversationRepo.getRecipient(recipientId)?.address }
                    ?: return@collectIn
            ClipboardUtils.copy(context, address)
            context.makeToast(R.string.info_copied_address)
        }

        // Show the theme settings for the conversation
        view.themeClicks().collectIn(view.bindScope, view::showThemePicker)

        // Show the conversation title dialog
        view.nameClicks().collectIn(view.bindScope) {
            conversation.value?.let { conversation -> view.showNameDialog(conversation.name) }
        }

        // Set the conversation title
        view.nameChanges().collectIn(view.bindScope) { name ->
            val conversation = conversation.value ?: return@collectIn
            withContext(Dispatchers.IO) { conversationRepo.setConversationName(conversation.id, name).await() }
        }

        // Show the notifications settings for the conversation
        view.notificationClicks().collectIn(view.bindScope) {
            conversation.value?.let { conversation -> externalNavigator.showNotificationSettings(conversation.id) }
        }

        // Open the background image picker
        view.backgroundImageClicks().collectIn(view.bindScope) { view.showBackgroundImagePicker() }

        // Save the selected background image
        view.backgroundImageSelected().collectIn(view.bindScope) { uri -> prefs.backgroundUri(threadId).set(uri.toString()) }

        // Clear the background image
        view.backgroundImageLongClicks().collectIn(view.bindScope) { prefs.backgroundUri(threadId).delete() }

        view.markUnreadClicks().collectIn(view.bindScope) {
            val conversation = conversation.value ?: return@collectIn
            markUnread.execute(listOf(conversation.id))
            navigator.showMainActivity()
        }

        // Toggle the pinned state of the conversation. Pinned conversations already sort to the
        // top of the main list - this just surfaces the existing toggle somewhere findable, rather
        // than only behind a long-press on the conversation.
        view.pinClicks().collectIn(view.bindScope) {
            val conversation = conversation.value ?: return@collectIn
            when (conversation.pinned) {
                true -> markUnpinned.execute(listOf(conversation.id))
                false -> markPinned.execute(listOf(conversation.id))
            }
        }

        // Toggle the archived state of the conversation
        view.archiveClicks().collectIn(view.bindScope) {
            val conversation = conversation.value ?: return@collectIn
            when (conversation.archived) {
                true -> markUnarchived.execute(listOf(conversation.id))
                false -> markArchived.execute(listOf(conversation.id))
            }
        }

        // Toggle the blocked state of the conversation
        view.blockClicks().collectIn(view.bindScope) {
            val conversation = conversation.value ?: return@collectIn
            view.showBlockingDialog(listOf(conversation.id), !conversation.blocked)
        }

        // Show the delete confirmation dialog
        view.deleteClicks().collectIn(view.bindScope) {
            if (permissionManager.isDefaultSms()) view.showDeleteDialog() else view.requestDefaultSms()
        }

        // Delete the conversation
        view.confirmDelete().collectIn(view.bindScope) {
            conversation.value?.let { conversation -> deleteConversations.execute(listOf(conversation.id)) }
        }

        // Media
        view.mediaClicks().collectIn(view.bindScope, navigator::showMedia)
    }

}
