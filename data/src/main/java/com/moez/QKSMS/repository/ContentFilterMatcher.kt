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

import timber.log.Timber

/**
 * Whether one message content filter (Blocking > Message content filters) blocks a message. Kept
 * apart from the database code so it can be unit-tested.
 */
internal object ContentFilterMatcher {

    fun matches(
        value: String,
        caseSensitive: Boolean,
        isRegex: Boolean,
        includeContacts: Boolean,
        body: String,
        isContact: Boolean
    ): Boolean {
        if (isContact && !includeContacts) return false

        if (isRegex) {
            // Regex filters find their pattern anywhere in the message, ignoring case, with "."
            // crossing line breaks - the same sense as keyword filters. They used to have to match
            // the WHOLE message, case-sensitively, with "." stopping at line breaks, so "prize[0-9]+"
            // never blocked "You won PRIZE123!" (the dialog even greys out "Case sensitive" for
            // regexes, implying case doesn't matter). Anchors still work: "^...$" means the whole
            // message, so a filter written that way on purpose keeps its meaning.
            // An invalid saved pattern must not throw: this runs on the receive path, and an
            // exception stops the incoming message from being processed.
            return try {
                Regex(value, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(body)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Skipping invalid regex content filter")
                false
            }
        }

        // Keyword filters match whole words: the keyword must not be directly preceded or followed
        // by another letter or digit. This used \b, which only exists next to a letter or digit, so
        // a keyword starting or ending with punctuation ("$5 off", "50%", "#ad") could never match.
        val keyword = if (caseSensitive) value else value.lowercase()
        val text = if (caseSensitive) body else body.lowercase()
        return Regex("(?<!\\w)" + Regex.escape(keyword) + "(?!\\w)").containsMatchIn(text)
    }
}
