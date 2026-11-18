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
package dev.octoshrimpy.quik.classifier

/**
 * What kind of sender an SMS address is. People text from ordinary phone numbers; businesses,
 * banks, delivery services and campaigns mostly text from short codes ("72166") or named sender IDs
 * ("VZWNOTICE"), which a person can't send from. That makes the address itself a strong signal that
 * a message isn't personal, independent of its wording.
 */
enum class SenderKind {
    PHONE_NUMBER,
    SHORT_CODE,
    NAMED_SENDER,
    /** A US/Canada toll-free number. Ordinary phones can't text from one; only businesses can. */
    TOLL_FREE;

    /** Short codes, named senders and toll-free numbers are businesses, never a person's phone. */
    val isBusiness: Boolean get() = this != PHONE_NUMBER

    companion object {
        private val separators = Regex("[\\s()\\-.+]")
        private val digitsOnly = Regex("\\d+")
        private val tollFreeAreaCodes = setOf("800", "833", "844", "855", "866", "877", "888")

        fun of(address: String): SenderKind {
            val compact = address.replace(separators, "")
            return when {
                // Email addresses (texts sent from email) are left as personal-capable rather than
                // guessed at; they're rare and could be either.
                compact.isEmpty() || '@' in compact -> PHONE_NUMBER
                compact.any { it.isLetter() } -> NAMED_SENDER
                !compact.matches(digitsOnly) -> PHONE_NUMBER
                // US/Canada short codes are 5-6 digits; some carriers and countries use 3-4.
                compact.length <= 6 -> SHORT_CODE
                nanpAreaCode(compact) in tollFreeAreaCodes -> TOLL_FREE
                else -> PHONE_NUMBER
            }
        }

        /** Area code of a 10-digit (or 1 + 10-digit) North American number, else null. */
        private fun nanpAreaCode(digits: String): String? = when {
            digits.length == 10 -> digits.take(3)
            digits.length == 11 && digits.startsWith("1") -> digits.substring(1, 4)
            else -> null
        }
    }
}
