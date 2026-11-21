package dev.octoshrimpy.quik.feature.compose

import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables

/**
 * Every update of the open conversation paired with its thread's messages, so the message list
 * shows current names and photos: a contact renamed while their thread was open used to keep the
 * old name until it was reopened (#81). The messages are reloaded only when the thread changes, and
 * a new thread's conversation is never paired with the previous thread's messages.
 */
internal fun liveThread(
    conversations: Observable<Conversation>,
    messagesFor: (threadId: Long) -> Observable<List<Message>>
): Observable<Pair<Conversation, List<Message>>> =
    Observables.combineLatest(
        conversations,
        conversations
            .distinctUntilChanged { conversation -> conversation.id }
            .switchMap { conversation -> messagesFor(conversation.id).map { list -> conversation.id to list } }
    ) { conversation, (threadId, list) -> Triple(conversation, threadId, list) }
        .filter { (conversation, threadId, _) -> conversation.id == threadId }
        .map { (conversation, _, list) -> conversation to list }
