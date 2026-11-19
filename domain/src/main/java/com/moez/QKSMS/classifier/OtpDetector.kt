/*
 * Copyright (C) 2026 Foxhole Messages contributors
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
package dev.octoshrimpy.quik.classifier

import javax.inject.Inject

class OtpDetector @Inject constructor() {

    private val codePattern = Regex("\\b\\d{4,8}\\b")
    private val keywordPattern = Regex(
        "\\b(code|otp|verification|pin|passcode|one[- ]time)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isOtp(body: String): Boolean =
        keywordPattern.containsMatchIn(body) && codePattern.containsMatchIn(body)

    /**
     * The code itself: the 4-8 digit number nearest a keyword, on either side, ignoring pieces of
     * dates and phone numbers ("On 2026-11-18 your code is 482193", "482193 is your code. Call
     * 1-800-935-9935"). It's shown large and copied, so a wrong pick gets pasted.
     * shortcut: a number nearer the keyword than the code still wins ("PIN for order 12345678: 4821").
     */
    fun extractCode(body: String): String? {
        val keywords = keywordPattern.findAll(body).map { it.range }.toList()
        return codePattern.findAll(body)
            .filterNot { candidate -> isPartOfLongerNumber(body, candidate.range) }
            .minByOrNull { candidate ->
                keywords.minOfOrNull { keyword -> distance(candidate.range, keyword) } ?: candidate.range.first
            }
            ?.value
    }

    // A digit group joined to other digits by '-', '.' or '/' (dates, phone numbers). A letter
    // prefix like "G-482193" doesn't count.
    private fun isPartOfLongerNumber(body: String, range: IntRange): Boolean {
        val joined = setOf('-', '.', '/')
        val before = body.getOrNull(range.first - 1) in joined && body.getOrNull(range.first - 2)?.isDigit() == true
        val after = body.getOrNull(range.last + 1) in joined && body.getOrNull(range.last + 2)?.isDigit() == true
        return before || after
    }

    private fun distance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }

}
