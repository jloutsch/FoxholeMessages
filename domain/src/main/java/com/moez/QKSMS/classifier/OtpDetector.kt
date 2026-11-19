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
     * The code itself: the first 4-8 digit number after the keyword ("On 2026-11-18 your code is
     * 482193" must not give 2026), else the first one anywhere ("482193 is your code").
     */
    fun extractCode(body: String): String? {
        val afterKeyword = keywordPattern.find(body)?.range?.last?.plus(1) ?: 0
        return (codePattern.find(body, afterKeyword) ?: codePattern.find(body))?.value
    }

}
