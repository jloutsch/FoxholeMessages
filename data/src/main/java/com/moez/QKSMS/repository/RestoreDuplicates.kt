/*
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

/**
 * Restore inserted every message in the backup file, so restoring onto a phone that still had them
 * (the same phone, or a second restore) doubled every message. This skips backup messages that are
 * already on the phone.
 *
 * Matching is by type, folder, sender address and time, plus the text for SMS (MMS text lives in
 * parts). It counts rather than just checking presence: an iPhone can send several pictures as
 * separate MMS within the same second, and if two of those are on the phone and the backup has
 * three, the third is still restored.
 */
internal class RestoreDuplicates(existingKeys: Iterable<String>) {

    private val remaining: MutableMap<String, Int> = existingKeys.groupingBy { it }.eachCount().toMutableMap()

    /** True if a message with [key] is already on the phone; each existing message matches once. */
    fun isAlreadyPresent(key: String): Boolean {
        val count = remaining[key] ?: return false
        if (count <= 1) remaining.remove(key) else remaining[key] = count - 1
        return true
    }

    companion object {
        fun key(isMms: Boolean, box: Int, address: String, date: Long, body: String?): String =
            if (isMms) "mms|$box|$address|$date"
            else "sms|$box|$address|$date|${body.orEmpty()}"
    }
}
