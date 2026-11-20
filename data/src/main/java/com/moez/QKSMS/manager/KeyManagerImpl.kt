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
package dev.octoshrimpy.quik.manager

import dev.octoshrimpy.quik.db.FoxholeDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyManagerImpl @Inject constructor(private val database: FoxholeDatabase) : KeyManager {

    private var initialized = false
    private var maxValue: Long = 0

    /**
     * Should be called when a new sync is being started
     */
    @Synchronized
    override fun reset() {
        initialized = true
        maxValue = 0L
    }

    /**
     * Returns a valid ID that can be used to store a new message
     */
    // Synchronized: receiving and syncing ask for ids on different threads
    @Synchronized
    override fun newId(): Long {
        if (!initialized) {
            // Reactions take ids from the same counter, so start above both tables' highest id;
            // starting from messages alone could reuse a reaction's id and overwrite it
            maxValue = maxOf(database.messageDao().maxId() ?: 0L, database.emojiReactionDao().maxId() ?: 0L)
            initialized = true
        }

        maxValue++
        return maxValue
    }

}