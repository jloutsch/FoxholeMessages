package dev.octoshrimpy.quik.feature.blocking

import dev.octoshrimpy.quik.model.MessageContentFilterData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The blocking-rules export file is read from wherever the user picks, so it may come from an older
 * build, a hand edit, or something hostile. These pin down: a real export always round-trips, a file
 * that isn't a rules file is rejected outright, and individual bad entries are skipped and counted
 * rather than failing the whole import.
 */
class BlockingRulesFileTest {

    private fun filter(value: String, caseSensitive: Boolean = false, regex: Boolean = false, contacts: Boolean = false) =
        MessageContentFilterData(value, caseSensitive, regex, contacts)

    private fun file(numbers: JSONArray = JSONArray(), filters: JSONArray = JSONArray(), format: String = "foxhole-blocking-rules", version: Int = 1) =
        JSONObject().put("format", format).put("version", version).put("blockedNumbers", numbers).put("filters", filters).toString()

    private fun rejected(raw: String) {
        try {
            BlockingRulesFile.parse(raw)
            fail("expected the file to be rejected")
        } catch (e: Exception) {
            // IllegalArgumentException or JSONException: either is a clean rejection, not a crash.
        }
    }

    // --- Round trip ---

    @Test
    fun exportThenImportGivesBackEverything() {
        val numbers = listOf("(555) 010-0101", "+1 555-010-0103", "72166")
        val filters = listOf(
            filter("testspamword"),
            filter("FreeCruise", caseSensitive = true, contacts = true),
            filter("prize[0-9]+", regex = true),
            filter("quotes \"and\" unicode ✓ émoji 😀")
        )
        val parsed = BlockingRulesFile.parse(BlockingRulesFile.toJson(numbers, filters))
        assertEquals(numbers, parsed.numbers)
        assertEquals(filters, parsed.filters)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun emptyExportRoundTrips() {
        val parsed = BlockingRulesFile.parse(BlockingRulesFile.toJson(emptyList(), emptyList()))
        assertTrue(parsed.numbers.isEmpty() && parsed.filters.isEmpty())
        assertEquals(0, parsed.skipped)
    }

    // --- Not a rules file at all: rejected as a whole ---

    @Test
    fun notJsonIsRejected() = rejected("this is not json")

    @Test
    fun wrongFormatIsRejected() = rejected(file(format = "something-else"))

    @Test
    fun unknownVersionIsRejected() = rejected(file(version = 2))

    @Test
    fun missingListsAreRejected() = rejected(JSONObject().put("format", "foxhole-blocking-rules").put("version", 1).toString())

    @Test
    fun tooManyEntriesIsRejected() =
        rejected(file(numbers = JSONArray((1..BlockingRulesFile.MAX_ENTRIES + 1).map { "555${it.toString().padStart(7, '0')}" })))

    @Test
    fun deeplyNestedFileIsRejectedNotCrashed() {
        // On Android this used to overflow the stack (a fatal error); the real org.json used in these
        // tests stops at a nesting limit instead. Either way the import must fail cleanly.
        val depth = 100_000
        rejected("""{"format":"foxhole-blocking-rules","version":1,"blockedNumbers":${"[".repeat(depth)}${"]".repeat(depth)},"filters":[]}""")
    }

    // --- Bad entries are skipped and counted, the rest still imports ---

    @Test
    fun badNumbersAreSkippedAndCounted() {
        val parsed = BlockingRulesFile.parse(file(numbers = JSONArray()
            .put("5550100404")
            .put("")                                   // blank (the old dialog could save this)
            .put("   ")                                // whitespace only
            .put("9".repeat(BlockingRulesFile.MAX_NUMBER_LENGTH + 1))
            .put(12345)                                // not text
            .put(JSONObject.NULL)))
        assertEquals(listOf("5550100404"), parsed.numbers)
        assertEquals(5, parsed.skipped)
    }

    @Test
    fun numbersAreTrimmed() =
        assertEquals(listOf("5550100404"), BlockingRulesFile.parse(file(numbers = JSONArray().put("  5550100404 "))).numbers)

    @Test
    fun badFiltersAreSkippedAndCounted() {
        val ok = JSONObject().put("value", "keepme").put("caseSensitive", false).put("isRegex", false).put("includeContacts", false)
        val parsed = BlockingRulesFile.parse(file(filters = JSONArray()
            .put(ok)
            .put(JSONObject(ok.toString()).put("value", "[unclosed").put("isRegex", true))    // invalid regex
            .put(JSONObject(ok.toString()).put("value", "x".repeat(BlockingRulesFile.MAX_FILTER_LENGTH + 1)))
            .put(JSONObject(ok.toString()).put("value", "   "))                            // blank
            .put(JSONObject(ok.toString()).put("caseSensitive", "yes"))                     // wrong type
            .put(JSONObject(ok.toString()).apply { remove("includeContacts") })             // missing field
            .put("not an object")))
        assertEquals(listOf(filter("keepme")), parsed.filters)
        assertEquals(6, parsed.skipped)
    }

    @Test
    fun regexFiltersAreNeverCaseSensitive() {
        val raw = file(filters = JSONArray().put(JSONObject()
            .put("value", "prize[0-9]+").put("caseSensitive", true).put("isRegex", true).put("includeContacts", false)))
        assertEquals(listOf(filter("prize[0-9]+", caseSensitive = false, regex = true)), BlockingRulesFile.parse(raw).filters)
    }

    // --- The checks shared with the Block and filter dialogs ---

    @Test
    fun filterProblems() {
        assertEquals(BlockingRulesFile.FilterProblem.BLANK, BlockingRulesFile.filterProblem(" ", isRegex = false))
        assertEquals(BlockingRulesFile.FilterProblem.TOO_LONG, BlockingRulesFile.filterProblem("x".repeat(1001), isRegex = false))
        assertEquals(BlockingRulesFile.FilterProblem.INVALID_REGEX, BlockingRulesFile.filterProblem("[promo", isRegex = true))
        assertNull(BlockingRulesFile.filterProblem("[promo", isRegex = false))   // fine as plain text
        assertNull(BlockingRulesFile.filterProblem("prize[0-9]+", isRegex = true))
    }

    @Test
    fun numberLimits() {
        assertTrue(BlockingRulesFile.isValidNumber("5550100101"))
        assertTrue(!BlockingRulesFile.isValidNumber(""))
        assertTrue(!BlockingRulesFile.isValidNumber("1".repeat(BlockingRulesFile.MAX_NUMBER_LENGTH + 1)))
    }
}
