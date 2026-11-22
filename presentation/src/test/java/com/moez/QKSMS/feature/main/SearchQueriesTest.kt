package dev.octoshrimpy.quik.feature.main

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueriesTest {

    @Test
    fun theSameQueryAfterClearingSearchesAgain() = runBlocking {
        // Search "mom", clear the box (back to the inbox), type "mom" again: it must search again
        assertEquals(listOf("mom", "mom"), flowOf("mom", "", "mom").searchableQueries().toList())
    }

    @Test
    fun shortQueriesAndRepeatsAreSkipped() = runBlocking {
        assertEquals(listOf("mo", "mom"), flowOf("m", "mo", "mo", "mom").searchableQueries().toList())
    }
}
