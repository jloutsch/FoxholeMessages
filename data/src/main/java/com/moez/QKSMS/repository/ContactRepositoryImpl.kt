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
package dev.octoshrimpy.quik.repository

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import dev.octoshrimpy.quik.db.FoxholeDatabase
import dev.octoshrimpy.quik.db.Relations
import dev.octoshrimpy.quik.db.Tables
import dev.octoshrimpy.quik.db.observe
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx2.asObservable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val context: Context,
    private val database: FoxholeDatabase,
    private val relations: Relations
) : ContactRepository {

    private val contactDao get() = database.contactDao()

    override fun getContacts(): List<Contact> = relations.contacts(contactDao.all())

    override fun getUnmanagedContact(lookupKey: String): Contact? =
        contactDao.get(lookupKey)?.let { relations.contacts(listOf(it)).first() }

    override fun getUnmanagedAllContacts(): List<Contact> = getContacts()

    override fun getUnmanagedContacts(starred: Boolean): Observable<List<Contact>> =
        database.observe(Tables.CONTACTS) { relations.contacts(if (starred) contactDao.starred() else contactDao.all()) }
                .map { contacts ->
                    contacts.sortedWith { c1, c2 ->
                        val initial = c1.name.firstOrNull()
                        val other = c2.name.firstOrNull()
                        if (initial?.isLetter() == true && other?.isLetter() != true) {
                            -1
                        } else if (initial?.isLetter() != true && other?.isLetter() == true) {
                            1
                        } else {
                            c1.name.compareTo(c2.name, ignoreCase = true)
                        }
                    }
                }
                .asObservable()
                .observeOn(AndroidSchedulers.mainThread()) // screens expect Realm's main-thread updates

    // Only groups with at least one saved contact in them
    override fun getUnmanagedContactGroups(): Observable<List<ContactGroup>> =
        database.observe(Tables.GROUPS) { relations.contactGroups(contactDao.groups()).filter { it.contacts.isNotEmpty() } }
                .asObservable()
                .observeOn(AndroidSchedulers.mainThread()) // screens expect Realm's main-thread updates

    override fun setDefaultPhoneNumber(lookupKey: String, phoneNumberId: Long) =
        contactDao.setDefaultNumber(lookupKey, phoneNumberId)

    override fun isContact(address: String): Boolean {
        // A blank address appends nothing to the lookup path, producing a malformed URI
        // ("content://com.android.contacts/phone_lookup/") that the contacts provider rejects with
        // an IllegalArgumentException rather than an empty result. MMS messages routinely carry a
        // blank address, so this has to be handled here rather than at every call site.
        if (address.isBlank()) {
            return false
        }

        val uri : Uri
        if (address.contains('@')) {
            uri = Uri.withAppendedPath(Email.CONTENT_FILTER_URI, Uri.encode(address))
        } else {
            uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        }
        return context.contentResolver.query(
            uri,
            arrayOf(BaseColumns._ID),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.count > 0
        } ?: false
    }

}
