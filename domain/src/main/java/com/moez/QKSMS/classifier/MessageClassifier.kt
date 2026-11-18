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

/**
 * Keyword/regex based classifier: counts matches against transactional and promotional patterns
 * and picks whichever scores higher (ties favor transactional). No matches from either set means
 * [Category.UNCLASSIFIED].
 */
class MessageClassifier @Inject constructor() {

    private val transactionalPatterns = listOf(
        Regex("\\$\\s?\\d+(\\.\\d{2})?"),
        Regex("\\botp\\b", RegexOption.IGNORE_CASE),
        Regex("\\bone[- ]time (code|password|passcode)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bverification code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsecurity code\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(account|acct|card)\\s+(ending|number)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bavailable balance\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwas (debited|credited|charged)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpayment (of|received|due)\\b", RegexOption.IGNORE_CASE),
        Regex("\\byou paid\\b", RegexOption.IGNORE_CASE),
        Regex("\\btransaction (of|alert)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpurchase of\\b", RegexOption.IGNORE_CASE),
        Regex("\\bstatement is ready\\b", RegexOption.IGNORE_CASE),
        Regex("\\bautopay\\b", RegexOption.IGNORE_CASE),
        Regex("\\bminimum payment\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdue date\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdeposit of\\b", RegexOption.IGNORE_CASE),
        Regex("\\bfraud alert\\b", RegexOption.IGNORE_CASE)
    )

    private val promotionalPatterns = listOf(
        Regex("\\d+%\\s?off", RegexOption.IGNORE_CASE),
        Regex("\\bsale\\b", RegexOption.IGNORE_CASE),
        Regex("\\boffer ends\\b", RegexOption.IGNORE_CASE),
        Regex("\\blimited time\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdiscount\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcoupon\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpromo\\s?code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bclearance\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(buy|shop) now\\b", RegexOption.IGNORE_CASE),
        Regex("\\bfree shipping\\b", RegexOption.IGNORE_CASE),
        // Opt-out wording. US rules require it on bulk/marketing texts, and campaigns phrase it many
        // ways ("Text STOP to quit", "Stop2End", "Reply STOP to opt out"); the original list only
        // knew "reply stop" and "txt stop", so most political and marketing texts matched nothing
        // and were filed as Personal.
        Regex("\\b(reply|text|txt|send)\\s+[\"']?stop\\b", RegexOption.IGNORE_CASE),
        Regex("\\bstop\\s*2\\s*(end|quit|stop|opt\\s*out)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bstop to (end|quit|cancel|stop|opt[- ]?out|unsubscribe)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bopt[- ]?out\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(msg|message)\\s*(&|and)\\s*data\\s+rates\\b", RegexOption.IGNORE_CASE),
        Regex("\\bunsubscribe\\b", RegexOption.IGNORE_CASE),
        Regex("\\bexclusive deal\\b", RegexOption.IGNORE_CASE),
        Regex("\\bflash sale\\b", RegexOption.IGNORE_CASE),
        // Fundraising asks.
        Regex("\\bdonat(e|ion)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bchip in\\b", RegexOption.IGNORE_CASE)
    )

    fun classify(body: String): Category = classify(scores(body))

    /**
     * Classification for a message from a saved contact. A single money mention ("can you spot me
     * $20?") is ordinary conversation between people, so it takes at least two transactional
     * patterns (e.g. an amount *and* "payment of") before a contact's message counts as an alert;
     * anything less stays personal.
     */
    fun classifyFromContact(body: String): Category {
        val scores = scores(body)
        return if (scores.transactional >= 2 && classify(scores) == Category.TRANSACTIONAL) {
            Category.TRANSACTIONAL
        } else {
            Category.PERSONAL
        }
    }

    private class Scores(val transactional: Int, val promotional: Int)

    private fun scores(body: String) = Scores(
        transactionalPatterns.count { it.containsMatchIn(body) },
        promotionalPatterns.count { it.containsMatchIn(body) }
    )

    private fun classify(scores: Scores): Category = when {
        scores.transactional == 0 && scores.promotional == 0 -> Category.UNCLASSIFIED
        scores.transactional < scores.promotional -> Category.PROMOTIONAL
        else -> Category.TRANSACTIONAL
    }

}
