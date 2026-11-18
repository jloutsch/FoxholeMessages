/*
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

/**
 * The rules that decide which messages get removed automatically. Kept as plain functions, apart
 * from the database code that applies them, so they can be unit-tested: a mistake in either one
 * deletes real messages.
 */

/**
 * What two messages must share to count as duplicates of each other ("Remove duplicate messages").
 *
 * Two rows are the same message (a restore or sync inserting it twice, or a carrier re-delivering an
 * SMS) when they share thread, sender, box, sent time and content. Weaker signatures deleted real
 * messages: outgoing SMS have dateSent 0, so every identical reply in a thread matched, and MMS have
 * an empty body, so separate photos sent within the same second matched.
 */
internal object DuplicateSignature {

    /** One MMS part, as compared for duplicates. */
    data class Part(val seq: Int, val type: String, val name: String?, val text: String?)

    fun of(
        type: String,
        threadId: Long,
        address: String,
        boxId: Int,
        dateSent: Long,
        date: Long,
        body: String,
        subject: String,
        isMms: Boolean,
        parts: List<Part>
    ): String = buildString {
        append(type).append('|')
        append(threadId).append('|')
        append(address).append('|')
        append(boxId).append('|')
        // dateSent is 0 when unknown (e.g. every outgoing SMS without a delivery report).
        append(dateSent.takeIf { it != 0L } ?: date).append('|')
        append(body).append('|')
        append(subject)
        if (isMms) {
            // The body is usually empty, so compare the receive time and every part too.
            append('|').append(date)
            parts.sortedBy { it.seq }.forEach { part ->
                append('|').append(part.type).append(':').append(part.name).append(':').append(part.text)
            }
        }
    }
}

/**
 * Which OTP-tagged messages "OTP message retention" may permanently delete once they're old enough.
 *
 * Tagging is a loose keyword match ("code"/"pin"/... plus any 4-8 digit number), so it also catches
 * ordinary texts like a friend's "door code is 4821". Deletion is therefore limited to what can only
 * be a received code: incoming, not starred or locked, and not from a saved contact.
 */
internal object OtpRetentionRule {
    fun isDeletable(isIncoming: Boolean, isStarred: Boolean, isLocked: Boolean, isFromContact: () -> Boolean): Boolean =
        isIncoming && !isStarred && !isLocked && !isFromContact()
}
