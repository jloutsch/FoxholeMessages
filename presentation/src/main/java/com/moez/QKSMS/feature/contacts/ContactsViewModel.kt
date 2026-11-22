/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
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
package dev.octoshrimpy.quik.feature.contacts

import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkViewModel
import android.view.inputmethod.EditorInfo
import dev.octoshrimpy.quik.extensions.removeAccents
import dev.octoshrimpy.quik.feature.compose.editing.ComposeItem
import dev.octoshrimpy.quik.feature.compose.editing.PhoneNumberAction
import dev.octoshrimpy.quik.filter.ContactFilter
import dev.octoshrimpy.quik.filter.ContactGroupFilter
import dev.octoshrimpy.quik.interactor.SetDefaultPhoneNumber
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.PhoneNumber
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import javax.inject.Inject

class ContactsViewModel @Inject constructor(
    sharing: Boolean,
    serializedChips: HashMap<String, String?>,
    private val contactFilter: ContactFilter,
    private val contactGroupFilter: ContactGroupFilter,
    private val contactsRepo: ContactRepository,
    private val conversationRepo: ConversationRepository,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val setDefaultPhoneNumber: SetDefaultPhoneNumber
) : QkViewModel<ContactsContract, ContactsState>(ContactsState()) {

    // The repositories are still RxJava (other screens read them): bridged until Task 29
    private val contactGroups: Flow<List<ContactGroup>> by lazy { contactsRepo.getUnmanagedContactGroups() }
    private val contacts: Flow<List<Contact>> by lazy { contactsRepo.getUnmanagedContacts() }
    private val recents: Flow<List<Conversation>> by lazy {
        if (sharing) conversationRepo.getUnmanagedConversations() else flowOf(listOf())
    }
    private val starredContacts: Flow<List<Contact>> by lazy { contactsRepo.getUnmanagedContacts(true) }

    private val selectedChips: Flow<List<Recipient>> = flow {
        emit(serializedChips.map { (address, lookupKey) ->
            Recipient(address = address).apply { contact = lookupKey?.let(contactsRepo::getUnmanagedContact) }
        })
    }.flowOn(Dispatchers.IO)

    private var shouldOpenKeyboard: Boolean = true

    /** Everything the suggestions are built from, apart from the selected chips */
    private class Sources(
        val query: CharSequence,
        val recents: List<Conversation>,
        val starredContacts: List<Contact>,
        val contactGroups: List<ContactGroup>,
        val contacts: List<Contact>
    )

    override fun bindView(view: ContactsContract) {
        super.bindView(view)

        if (shouldOpenKeyboard) {
            view.openKeyboard()
            shouldOpenKeyboard = false
        }

        // Update the state's query, so we know if we should show the cancel button
        view.queryChangedIntent.collectIn(view.bindScope) { query -> newState { copy(query = query.toString()) } }

        // Clear the query
        view.queryClearedIntent.collectIn(view.bindScope) { view.clearQuery() }

        // Update the list of contact suggestions based on the query input, while also filtering out any contacts
        // that have already been selected
        combine(view.queryChangedIntent, recents, starredContacts, contactGroups, contacts, ::Sources)
                .combine(selectedChips) { sources, selectedChips -> composeItems(sources, selectedChips) }
                .flowOn(Dispatchers.Default)
                .collectIn(view.bindScope) { items -> newState { copy(composeItems = items) } }

        // Listen for ComposeItems being selected, and then send them off to the number picker dialog in case
        // the user needs to select a phone number
        merge(
                view.queryEditorActionIntent
                        .filter { actionId -> actionId == EditorInfo.IME_ACTION_DONE }
                        .mapNotNull { state.value.composeItems.firstOrNull() }
                        .map { composeItem -> composeItem to false },
                view.composeItemPressedIntent.map { composeItem -> composeItem to false },
                view.composeItemLongPressedIntent.map { composeItem -> composeItem to true }
        ).collectIn(view.bindScope) { (composeItem, force) ->
            val result = HashMap<String, String?>()
            for (contact in composeItem.getContacts()) {
                if (contact.numbers.size == 1 || contact.getDefaultNumber() != null && !force) {
                    val address = contact.getDefaultNumber()?.address ?: contact.numbers[0]!!.address
                    result[address] = contact.lookupKey
                    continue
                }

                // Ask which number to use. The dialog closes however this ends, including when the
                // screen goes away mid-question (otherwise it would reopen with nobody listening)
                val action = try {
                    newState { copy(selectedContact = contact) }
                    view.phoneNumberActionIntent.first()
                } finally {
                    newState { copy(selectedContact = null) }
                }
                // The picker pre-selects the default (or first) number, so this is set by now
                val numberId = view.phoneNumberSelectedIntent.value
                val number = contact.numbers.find { number -> number.id == numberId }

                if (action == PhoneNumberAction.CANCEL || number == null) {
                    return@collectIn
                }

                if (action == PhoneNumberAction.ALWAYS) {
                    val params = SetDefaultPhoneNumber.Params(contact.lookupKey, number.id)
                    setDefaultPhoneNumber.execute(params)
                }

                result[number.address] = contact.lookupKey
            }

            if (result.isNotEmpty()) view.finish(result)
        }
    }

    private fun composeItems(sources: Sources, selectedChips: List<Recipient>): List<ComposeItem> {
        val query = sources.query
        val composeItems = mutableListOf<ComposeItem>()
        if (query.isBlank()) {
            composeItems += sources.recents
                    .filter { conversation ->
                        conversation.recipients.any { recipient ->
                            selectedChips.none { chip ->
                                if (recipient.contact == null) {
                                    chip.address == recipient.address
                                } else {
                                    chip.contact?.lookupKey == recipient.contact?.lookupKey
                                }
                            }
                        }
                    }
                    .map(ComposeItem::Recent)

            composeItems += sources.starredContacts
                    .filter { contact -> selectedChips.none { it.contact?.lookupKey == contact.lookupKey } }
                    .map(ComposeItem::Starred)

            composeItems += sources.contactGroups
                    .filter { group ->
                        group.contacts.any { contact ->
                            selectedChips.none { chip -> chip.contact?.lookupKey == contact.lookupKey }
                        }
                    }
                    .map(ComposeItem::Group)

            composeItems += sources.contacts
                    .filter { contact -> selectedChips.none { it.contact?.lookupKey == contact.lookupKey } }
                    .map(ComposeItem::Person)
        } else {
            // If the entry is a valid destination, allow it as a recipient
            if (phoneNumberUtils.isPossibleNumber(query.toString())) {
                val newAddress = phoneNumberUtils.formatNumber(query)
                val newContact = Contact().apply { numbers = listOf(PhoneNumber(address = newAddress)) }
                composeItems += ComposeItem.New(newContact)
            }

            // Strip the accents from the query. This can be an expensive operation, so
            // cache the result instead of doing it for each contact
            val normalizedQuery = query.removeAccents()
            composeItems += sources.starredContacts
                    .asSequence()
                    .filter { contact -> selectedChips.none { it.contact?.lookupKey == contact.lookupKey } }
                    .filter { contact -> contactFilter.filter(contact, normalizedQuery) }
                    .map(ComposeItem::Starred)

            composeItems += sources.contactGroups
                    .asSequence()
                    .filter { group ->
                        group.contacts.any { contact ->
                            selectedChips.none { chip -> chip.contact?.lookupKey == contact.lookupKey }
                        }
                    }
                    .filter { group -> contactGroupFilter.filter(group, normalizedQuery) }
                    .map(ComposeItem::Group)

            composeItems += sources.contacts
                    .asSequence()
                    .filter { contact -> selectedChips.none { it.contact?.lookupKey == contact.lookupKey } }
                    .filter { contact -> contactFilter.filter(contact, normalizedQuery) }
                    .map(ComposeItem::Person)
        }

        return composeItems
    }

}
