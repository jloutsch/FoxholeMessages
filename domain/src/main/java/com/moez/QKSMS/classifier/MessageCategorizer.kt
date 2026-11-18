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

import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.SenderCategoryRuleRepository
import dev.octoshrimpy.quik.repository.TrustedSenderRepository
import javax.inject.Inject

/**
 * Decides which [Category] a message belongs in; see [decideCategory] for the rules. This class
 * supplies the sender lookups (trusted list, manual rules, contacts, whether the user has replied)
 * to it.
 */
class MessageCategorizer @Inject constructor(
    private val trustedSenderRepo: TrustedSenderRepository,
    private val senderRuleRepo: SenderCategoryRuleRepository,
    private val contactsRepo: ContactRepository,
    private val messageRepo: MessageRepository,
    private val classifier: MessageClassifier
) {

    fun categorize(address: String, body: String, threadId: Long): Category = decideCategory(
        classifier,
        address,
        body,
        isTrusted = { trustedSenderRepo.isTrusted(address) },
        rule = { ruleOf(address) },
        isContact = { contactsRepo.isContact(address) },
        hasReplied = { threadId in messageRepo.getThreadIdsWithSentMessages(listOf(threadId)) }
    )

    /**
     * Returns a categorize function with the per-address lookups memoized, for classifying a large
     * batch at once. Only the body-dependent keyword classification then runs per message; the
     * sender lookups - one of which is a cross-process query to the contacts provider - run once
     * per distinct address instead of once per message. That matters because a whole thread's
     * worth of messages share one address, so a backfill over a full history would otherwise
     * repeat the same provider query thousands of times.
     *
     * The memo lives only as long as the returned function, so it cannot go stale against contact
     * or rule changes the way caching on this shared instance would.
     */
    fun bulkCategorizer(): (address: String, body: String, threadId: Long) -> Category {
        val trusted = HashMap<String, Boolean>()
        val rules = HashMap<String, Category?>()
        val contacts = HashMap<String, Boolean>()
        // One query for every conversation the user has sent in, instead of one per message.
        val repliedThreads by lazy { messageRepo.getThreadIdsWithSentMessages() }

        return { address, body, threadId ->
            decideCategory(
                classifier,
                address,
                body,
                isTrusted = { trusted.getOrPut(address) { trustedSenderRepo.isTrusted(address) } },
                rule = {
                    if (rules.containsKey(address)) rules[address]
                    else ruleOf(address).also { rules[address] = it }
                },
                isContact = { contacts.getOrPut(address) { contactsRepo.isContact(address) } },
                hasReplied = { threadId in repliedThreads }
            )
        }
    }

    private fun ruleOf(address: String): Category? =
        senderRuleRepo.getRule(address)?.let { rule ->
            try {
                Category.valueOf(rule.category)
            } catch (e: IllegalArgumentException) {
                Category.UNCLASSIFIED
            }
        }

}

/**
 * Which [Category] an incoming message belongs in, in priority order:
 * 1. A trusted sender is always [Category.PERSONAL].
 * 2. A manual rule for the sender ("Move to…") wins next.
 * 3. A **business sender** - short code, named sender or toll-free number (see [SenderKind]) - is
 *    never Personal: it is classified by its wording, and wording that matches nothing counts as
 *    [Category.TRANSACTIONAL] (alerts, deliveries, codes). This applies even when it's saved as a
 *    contact (e.g. a bank).
 * 4. A **saved contact** with an ordinary phone number stays Personal, unless the message is
 *    clearly an alert (see [MessageClassifier.classifyFromContact]).
 * 5. Any other phone number is classified by its wording. If that matches nothing:
 *    - and the user has **never replied** in the conversation, it is [Category.PROMOTIONAL]: in
 *      practice these are one-off blasts, reminders and spam. Replying moves the conversation back
 *      (SendNewMessage re-checks it), so a new person's first text is one reply away from Personal.
 *    - otherwise [Category.UNCLASSIFIED], which shows in Personal.
 *
 * Lookups stay behind lambdas so the short-circuits hold: a trusted sender never triggers a rule or
 * contacts lookup, businesses never trigger a (cross-process) contacts lookup at all, and the reply
 * check only runs for unknown numbers whose wording matched nothing.
 */
internal inline fun decideCategory(
    classifier: MessageClassifier,
    address: String,
    body: String,
    isTrusted: () -> Boolean,
    rule: () -> Category?,
    isContact: () -> Boolean,
    hasReplied: () -> Boolean
): Category {
    if (isTrusted()) {
        return Category.PERSONAL
    }

    rule()?.let { return it }

    if (SenderKind.of(address).isBusiness) {
        val category = classifier.classify(body)
        return if (category == Category.UNCLASSIFIED) Category.TRANSACTIONAL else category
    }

    if (isContact()) {
        return classifier.classifyFromContact(body)
    }

    val category = classifier.classify(body)
    if (category == Category.UNCLASSIFIED && !hasReplied()) {
        return Category.PROMOTIONAL
    }
    return category
}
