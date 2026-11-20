package dev.octoshrimpy.quik.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.octoshrimpy.quik.db.entity.ContactGroupMember
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.model.PhoneNumber
import dev.octoshrimpy.quik.model.Recipient
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipientDao {
    @Query("SELECT * FROM Recipient") fun all(): List<Recipient>
    @Query("SELECT * FROM Recipient WHERE contactLookupKey IS NOT NULL")
    fun observeWithContact(): Flow<List<Recipient>>
    @Query("SELECT * FROM Recipient WHERE id = :id") fun get(id: Long): Recipient?
    @Query("SELECT * FROM Recipient WHERE id IN (:ids)") fun get(ids: List<Long>): List<Recipient>
    @Query("SELECT MAX(id) FROM Recipient") fun maxId(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(recipients: List<Recipient>)
    @Query("DELETE FROM Recipient") fun clear()
}

/** Contacts as last synced from Android's contacts, with their numbers and groups. */
@Dao
interface ContactDao {
    @Query("SELECT * FROM Contact ORDER BY name") fun all(): List<Contact>
    @Query("SELECT * FROM Contact WHERE starred = 1 ORDER BY name") fun starred(): List<Contact>
    @Query("SELECT * FROM Contact ORDER BY name") fun observeAll(): Flow<List<Contact>>
    @Query("SELECT * FROM Contact WHERE lookupKey = :lookupKey") fun get(lookupKey: String): Contact?
    @Query("SELECT * FROM Contact WHERE lookupKey IN (:lookupKeys)") fun get(lookupKeys: List<String>): List<Contact>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(contacts: List<Contact>)
    @Query("DELETE FROM Contact") fun clear()

    @Query("SELECT * FROM PhoneNumber WHERE contactLookupKey IN (:lookupKeys)")
    fun numbersFor(lookupKeys: List<String>): List<PhoneNumber>
    @Query("SELECT * FROM PhoneNumber") fun allNumbers(): List<PhoneNumber>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertNumbers(numbers: List<PhoneNumber>)
    @Query("DELETE FROM PhoneNumber") fun clearNumbers()

    @Query("SELECT * FROM ContactGroup") fun groups(): List<ContactGroup>
    @Query("SELECT * FROM ContactGroup") fun observeGroups(): Flow<List<ContactGroup>>
    @Query("SELECT * FROM ContactGroupMember WHERE groupId IN (:groupIds)")
    fun groupMembers(groupIds: List<Long>): List<ContactGroupMember>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertGroups(groups: List<ContactGroup>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertGroupMembers(members: List<ContactGroupMember>)
    @Query("DELETE FROM ContactGroup") fun clearGroups()
    @Query("DELETE FROM ContactGroupMember") fun clearGroupMembers()
}
