package dev.octoshrimpy.quik.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** The tables each kind of object, with its relations filled in, is read from. */
object Tables {
    val MESSAGES = arrayOf("Message", "MmsPart", "EmojiReaction")
    val CONTACTS = arrayOf("Contact", "PhoneNumber")
    val RECIPIENTS = arrayOf("Recipient") + CONTACTS
    val CONVERSATIONS = arrayOf("Conversation", "ConversationRecipient") + RECIPIENTS + MESSAGES
    val GROUPS = arrayOf("ContactGroup", "ContactGroupMember") + CONTACTS
}

/**
 * Runs [query] now and again whenever one of [tables] changes, off the main thread. Room's own live
 * queries only watch the tables their SQL names, which would miss relations (a contact renamed
 * while its conversation is on screen); Realm's live results saw those changes too.
 */
fun <T> FoxholeDatabase.observe(tables: Array<String>, query: () -> T): Flow<T> =
    invalidationTracker.createFlow(*tables, emitInitialState = true)
        // If changes arrive faster than the query runs (bulk sorting writes one per 500 messages),
        // run it once for the latest instead of once per change. On the Note 10 each list ran once
        // per change with no backlog (#85); this keeps a slower phone from queueing them.
        .conflate()
        .map { query() }
        .flowOn(Dispatchers.IO)
