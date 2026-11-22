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
package dev.octoshrimpy.quik.feature.qkreply

import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkFlowViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import android.telephony.SmsMessage
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.ExternalNavigator
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.compat.SubscriptionManagerCompat
import dev.octoshrimpy.quik.interactor.DeleteMessages
import dev.octoshrimpy.quik.interactor.MarkRead
import dev.octoshrimpy.quik.interactor.SendNewMessage
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.ActiveSubscriptionObservable
import javax.inject.Inject
import javax.inject.Named

class QkReplyViewModel @Inject constructor(
    @Named("threadId") private val threadId: Long,
    private val conversationRepo: ConversationRepository,
    private val deleteMessages: DeleteMessages,
    private val markRead: MarkRead,
    private val messageRepo: MessageRepository,
    private val navigator: Navigator,
    private val externalNavigator: ExternalNavigator,
    private val sendNewMessage: SendNewMessage,
    private val subscriptionManager: SubscriptionManagerCompat
) : QkFlowViewModel<QkReplyView, QkReplyState>(QkReplyState(threadId = threadId)) {

    // The latest conversation; null until it loads
    private val conversation: StateFlow<Conversation?> = conversationRepo.observeConversation(threadId)
            .filterNotNull()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Unread messages only, until "expand" asks for every message in the thread
    private val showAll = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest is stable in practice; still marked experimental
    private val messages: StateFlow<List<Message>?> = showAll
            .flatMapLatest { all ->
                if (all) messageRepo.getMessages(threadId) else messageRepo.observeUnreadMessages(threadId)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // When the set of messages changes, update the state
        // If we're ever showing an empty set of messages, then it's time to shut down to activity
        combine(messages.filterNotNull(), conversation.filterNotNull()) { messages, conversation ->
            newState { copy(data = Pair(conversation, messages)) }
            messages
        }
                .filter { it.isEmpty() }
                .collectIn(viewModelScope) { newState { copy(hasError = true) } }

        conversation.filterNotNull()
                .map { conversation -> conversation.getTitle() }
                .distinctUntilChanged()
                .collectIn(viewModelScope) { title -> newState { copy(title = title) } }

        val latestSubId = messages.filterNotNull()
                .map { messages -> messages.lastOrNull()?.subId ?: -1 }
                .distinctUntilChanged()

        // ActiveSubscriptionObservable is still RxJava (Compose reads it too): bridged until Task 29
        val subscriptions = ActiveSubscriptionObservable(subscriptionManager).asFlow()
        combine(latestSubId, subscriptions) { subId, subs ->
            if (subs.size > 1) subs.firstOrNull { it.subscriptionId == subId } ?: subs[0] else null
        }.collectIn(viewModelScope) { sub -> newState { copy(subscription = sub) } }
    }

    override fun onCleared() {
        // Released when the popup closes, as under RxJava
        markRead.dispose()
        sendNewMessage.dispose()
        super.onCleared()
    }

    override fun bindView(view: QkReplyView) {
        super.bindView(view)

        // only update saved draft to ui once
        view.bindScope.launch { view.setDraft(conversation.filterNotNull().first().draft) }

        // The text in the box, for sending (what withLatestFrom read)
        var text: CharSequence = ""

        view.menuItemIntent.collectIn(view.bindScope) { id ->
            when (id) {
                // Mark read
                R.id.read -> markRead.execute(listOf(threadId)) { newState { copy(hasError = true) } }

                // Call
                R.id.call -> {
                    val address = state.value.data?.second?.lastOrNull { !it.isMe() }?.address // most recent non-me msg address
                            ?: conversation.value?.recipients?.firstOrNull()?.address // first recipient in convo
                    if (address != null) {
                        externalNavigator.makePhoneCall(address)
                        newState { copy(hasError = true) }
                    }
                }

                // Show all messages
                R.id.expand -> {
                    showAll.value = true
                    newState { copy(expanded = true) }
                }

                // Show unread messages only
                R.id.collapse -> {
                    showAll.value = false
                    newState { copy(expanded = false) }
                }

                // Delete new messages
                R.id.delete -> {
                    val messages = withContext(Dispatchers.IO) { messageRepo.getUnreadMessages(threadId).map { it.id } }
                    deleteMessages.execute(DeleteMessages.Params(messages, threadId)) { newState { copy(hasError = true) } }
                }

                // View conversation
                R.id.view -> {
                    navigator.showConversation(threadId)
                    newState { copy(hasError = true) }
                }
            }
        }

        // Enable the send button when there is text input into the new message body or there's
        // an attachment, disable otherwise
        view.textChangedIntent.collectIn(view.bindScope) { newText ->
            text = newText
            newState { copy(canSend = newText.isNotBlank()) }
        }

        // Show the remaining character counter when necessary
        view.textChangedIntent
                .map { draft -> SmsMessage.calculateLength(draft, false) }
                .map { array ->
                    val messages = array[0]
                    val remaining = array[2]

                    when {
                        messages <= 1 && remaining > 10 -> ""
                        messages <= 1 && remaining <= 10 -> "$remaining"
                        else -> "$remaining / $messages"
                    }
                }
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .collectIn(view.bindScope) { remaining -> newState { copy(remaining = remaining) } }

        // Update the draft whenever the text is changed
        @OptIn(FlowPreview::class) // debounce is stable in practice; still marked preview
        view.textChangedIntent
                .debounce(100)
                .map { draft -> draft.toString() }
                .collectIn(view.bindScope) { draft ->
                    withContext(Dispatchers.IO) { conversationRepo.saveDraft(threadId, draft) }
                }

        // Toggle to the next sim slot
        view.changeSimIntent.collectIn(view.bindScope) {
            val subs = subscriptionManager.activeSubscriptionInfoList
            val subIndex = subs.indexOfFirst { it.subscriptionId == state.value.subscription?.subscriptionId }
            val subscription = when {
                subIndex == -1 -> null
                subIndex < subs.size - 1 -> subs[subIndex + 1]
                else -> subs[0]
            }
            newState { copy(subscription = subscription) }
        }

        // Send a message when the send button is clicked, and disable editing mode if it's enabled
        view.sendIntent.collectIn(view.bindScope) {
            val conversation = conversation.value ?: return@collectIn
            sendNewMessage.execute(SendNewMessage.Params(
                state.value.subscription?.subscriptionId ?: -1, 0,
                conversation.recipients.map { it.address }, text.toString(), conversation.sendAsGroup
            ))
            view.setDraft("")
            markRead.execute(listOf(threadId)) { newState { copy(hasError = true) } }
        }
    }

}
