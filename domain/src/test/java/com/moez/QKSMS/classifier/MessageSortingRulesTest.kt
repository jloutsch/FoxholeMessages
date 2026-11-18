package dev.octoshrimpy.quik.classifier

import dev.octoshrimpy.quik.classifier.Category.PERSONAL
import dev.octoshrimpy.quik.classifier.Category.PROMOTIONAL
import dev.octoshrimpy.quik.classifier.Category.TRANSACTIONAL
import dev.octoshrimpy.quik.classifier.Category.UNCLASSIFIED
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * End-to-end checks of the sorting decision (see decideCategory) on realistic texts. UNCLASSIFIED
 * shows in the Personal tab, so anything that must stay out of Personal must not be UNCLASSIFIED.
 */
class MessageSortingRulesTest {

    private val classifier = MessageClassifier()

    /** [replied]: whether the user has sent anything in the conversation. */
    private fun sort(
        address: String,
        body: String,
        contact: Boolean = false,
        trusted: Boolean = false,
        rule: Category? = null,
        replied: Boolean = true
    ) = decideCategory(classifier, address, body, { trusted }, { rule }, { contact }, { replied })

    // --- Business senders (short codes / named senders) never land in Personal ---

    @Test
    fun bankAlertFromShortCodeIsTransactional() =
        assertEquals(TRANSACTIONAL, sort("72166", "Chase: payment of \$45.00 received"))

    @Test
    fun deliveryNoticeFromShortCodeIsNotPersonal() =
        assertEquals(TRANSACTIONAL, sort("69877", "Your UPS package was delivered at 2:14pm"))

    @Test
    fun verificationCodeFromShortCodeIsNotPersonal() =
        assertEquals(TRANSACTIONAL, sort("89203", "Your Uber code is 4821. Don't share it"))

    @Test
    fun surveyFromNamedSenderIsNotPersonal() =
        assertEquals(TRANSACTIONAL, sort("VZWNOTICE", "Verizon Msg: Thanks for calling us. Click here to take a survey"))

    @Test
    fun marketingFromShortCodeIsPromotional() =
        assertEquals(PROMOTIONAL, sort("33733", "Weekend SALE: 30% off everything! Reply STOP to opt out"))

    @Test
    fun businessSavedAsContactIsStillSorted() =
        assertEquals(TRANSACTIONAL, sort("72166", "Your statement is ready", contact = true))

    @Test
    fun businessSenderNeverTriggersContactsOrReplyLookup() {
        decideCategory(classifier, "72166", "hello", { false }, { null },
            { fail("contacts looked up"); false }, { fail("reply check ran"); false })
    }

    @Test
    fun tollFreeNumberIsABusiness() =
        assertEquals(TRANSACTIONAL, sort("+18775755319", "Your appointment is confirmed for Tuesday", replied = false))

    @Test
    fun tollFreeMarketingIsPromotional() =
        assertEquals(PROMOTIONAL, sort("(844) 839-8816", "Last chance: 20% off. Reply STOP to opt out"))

    // --- Campaign texts from ordinary numbers are caught by their opt-out wording ---

    @Test
    fun politicalTextWithTextStopIsNotPersonal() =
        assertEquals(PROMOTIONAL, sort("+14695174484", "Will you chip in 5 bucks to help us win? Text STOP to quit"))

    @Test
    fun stop2EndIsPromotional() =
        assertEquals(PROMOTIONAL, sort("+12022352110", "Hi, it's Colin. Can I share my story with you? Stop2End"))

    @Test
    fun msgAndDataRatesWithStopIsPromotional() =
        assertEquals(PROMOTIONAL, sort("+18445550100", "Join our list! Msg & data rates may apply. Reply STOP to cancel"))

    @Test
    fun donationAskWithAmountStaysOutOfPersonal() {
        val tab = sort("+14705550100", "Donate \$10 now to fight for our district. Text STOP to quit")
        if (tab == PERSONAL || tab == UNCLASSIFIED) fail("fundraising landed in Personal: $tab")
    }

    // --- Real people stay in Personal ---

    @Test
    fun contactMentioningMoneyStaysPersonal() =
        assertEquals(PERSONAL, sort("+15550100199", "can you spot me \$20 for pizza?", contact = true))

    @Test
    fun contactChatStaysPersonal() =
        assertEquals(PERSONAL, sort("+15550100199", "running late, see you at 7", contact = true))

    @Test
    fun contactSendingAClearAlertIsTransactional() =
        assertEquals(TRANSACTIONAL, sort("+15550100199", "Payment of \$120.00 received for account ending 1234", contact = true))

    // --- Unknown numbers: unanswered one-offs leave Personal, answering brings them back ---

    @Test
    fun unansweredUnknownNumberLeavesPersonal() =
        assertEquals(PROMOTIONAL, sort("+15550100177", "Hey it's Sam from the gym, this is my number", replied = false))

    @Test
    fun answeredUnknownNumberIsPersonalTab() =
        assertEquals(UNCLASSIFIED, sort("+15550100177", "Hey it's Sam from the gym, this is my number", replied = true))

    @Test
    fun unknownNumberWithAlertWordingIsTransactionalEitherWay() {
        assertEquals(TRANSACTIONAL, sort("+15550100177", "Fraud alert: was your card ending 1234 used?", replied = false))
        assertEquals(TRANSACTIONAL, sort("+15550100177", "Fraud alert: was your card ending 1234 used?", replied = true))
    }

    @Test
    fun replyCheckOnlyRunsWhenWordingMatchedNothing() {
        decideCategory(classifier, "+15550100177", "Fraud alert: card ending 1234", { false }, { null },
            { false }, { fail("reply check ran"); false })
        decideCategory(classifier, "+15550100199", "see you at 7", { false }, { null },
            { true }, { fail("reply check ran for a contact"); false })
    }

    // --- Manual choices always win ---

    @Test
    fun trustedSenderIsPersonalEvenIfItLooksLikeMarketing() =
        assertEquals(PERSONAL, sort("33733", "50% off! Reply STOP to opt out", trusted = true))

    @Test
    fun moveToRuleWinsOverEverything() =
        assertEquals(PROMOTIONAL, sort("+15550100199", "running late", contact = true, rule = PROMOTIONAL))

    // --- Wording that must NOT be mistaken for opt-out text ---

    @Test
    fun everydayUseOfStopIsNotPromotional() {
        assertEquals(PERSONAL, sort("+15550100199", "stop by when you can", contact = true))
        assertEquals(UNCLASSIFIED, sort("+15550100177", "can't stop laughing at that"))
    }
}
