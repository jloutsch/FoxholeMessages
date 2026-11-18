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
package dev.octoshrimpy.quik.util

/**
 * Names of the folders (in the app's files dir) holding copies of a scheduled message's attachments.
 *
 * Saving used to build the name as "scheduled-" + "-" + id ("scheduled--123"), while deleting looked
 * for "scheduled-123" and the daily clean-up read the id after the first dash ("-123", which matches
 * nothing). So the clean-up treated every scheduled message's folder as left over and deleted it,
 * and scheduled messages were sent without their pictures. New folders use one dash; folders saved
 * the old way are still recognised.
 */
object ScheduledMessageFiles {

    private const val PREFIX = Constants.SCHEDULED_MESSAGE_FILE_PREFIX

    /** Folder name for a new scheduled message's attachments. */
    fun dirName(id: Long) = "$PREFIX$id"

    /** Every folder name a scheduled message's attachments may be under, current and old style. */
    fun dirNames(id: Long) = listOf("$PREFIX$id", "$PREFIX-$id")

    /** The scheduled message id a folder belongs to, or null if the name isn't one of ours. */
    fun idFromDirName(name: String): Long? =
        if (!name.startsWith(PREFIX)) null
        else name.removePrefix(PREFIX).removePrefix("-").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
}
