package dev.octoshrimpy.quik.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a message content filter (Blocking > Message content filters) actually blocks. */
class ContentFilterMatcherTest {

    private fun blocks(
        filter: String, body: String,
        caseSensitive: Boolean = false, regex: Boolean = false,
        includeContacts: Boolean = false, fromContact: Boolean = false
    ) = ContentFilterMatcher.matches(filter, caseSensitive, regex, includeContacts, body, fromContact)

    // --- Keywords: whole words, case-insensitive by default ---

    @Test
    fun keywordMatchesAsAWholeWordAnywhere() {
        assertTrue(blocks("lottery", "You won the LOTTERY, claim now"))
        assertTrue(blocks("lottery", "lottery"))
        assertTrue(blocks("free cruise", "Claim your free cruise today"))
        assertTrue(blocks("lottery", "line one\nlottery on line two"))
    }

    @Test
    fun keywordDoesNotMatchInsideOtherWords() {
        assertFalse(blocks("ad", "I'm heading home, glad you're ok"))
        assertFalse(blocks("win", "window shopping"))
    }

    @Test
    fun keywordsWithPunctuationMatch() {
        // These never matched before (the \b word-boundary bug).
        assertTrue(blocks("\$5 off", "Get \$5 off your next order"))
        assertTrue(blocks("50%", "Everything 50% today"))
        assertTrue(blocks("#ad", "Loving this lipstick #ad"))
        assertTrue(blocks("$5 off", "\$5 off!"))
        // ...but still only as whole words.
        assertFalse(blocks("50%", "Everything 150% today"))
    }

    @Test
    fun caseSensitiveKeyword() {
        assertTrue(blocks("FreeCruise", "Book FreeCruise now", caseSensitive = true))
        assertFalse(blocks("FreeCruise", "book freecruise now", caseSensitive = true))
    }

    @Test
    fun keywordCharactersAreLiteralNotRegex() =
        assertFalse(blocks("a.b", "axb"))

    // --- Regex filters ---

    @Test
    fun regexMustMatchTheWholeMessage() {
        // Existing behaviour, pinned on purpose: a regex filter only blocks a message that it
        // matches from start to end, unlike keyword filters which match anywhere.
        assertTrue(blocks("prize[0-9]+", "prize123", regex = true))
        assertFalse(blocks("prize[0-9]+", "You won prize123!", regex = true))
        assertTrue(blocks(".*prize[0-9]+.*", "You won prize123!", regex = true))
    }

    @Test
    fun invalidRegexBlocksNothingAndDoesNotThrow() =
        assertFalse(blocks("[promo", "anything", regex = true))

    // --- Contacts ---

    @Test
    fun contactsAreExemptUnlessTheFilterIncludesThem() {
        assertFalse(blocks("lottery", "lottery", fromContact = true))
        assertTrue(blocks("lottery", "lottery", fromContact = true, includeContacts = true))
        assertTrue(blocks("lottery", "lottery", fromContact = false, includeContacts = true))
    }
}
