package dev.octoshrimpy.quik.feature.compose

import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Every update of the open conversation paired with its thread's messages, so the message list
 * shows current names and photos: a contact renamed while their thread was open used to keep the
 * old name until it was reopened (#81). The messages are reloaded only when the thread changes, and
 * a new thread's conversation is never paired with the previous thread's messages.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest is stable in practice; still marked experimental
internal fun liveThread(
    conversations: Flow<Conversation>,
    messagesFor: (threadId: Long) -> Flow<List<Message>>
): Flow<Pair<Conversation, List<Message>>> =
    combine(
        conversations,
        conversations
            .distinctUntilChangedBy { conversation -> conversation.id }
            .flatMapLatest { conversation -> messagesFor(conversation.id).map { list -> conversation.id to list } }
    ) { conversation, (threadId, list) -> Triple(conversation, threadId, list) }
        .filter { (conversation, threadId, _) -> conversation.id == threadId }
        .map { (conversation, _, list) -> conversation to list }
