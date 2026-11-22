package dev.octoshrimpy.quik.common.base

import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #119: Blocked messages handed the selection to Unblock, then cleared it. The adapter sent out its
 * own list, so clearing emptied the copy Unblock was about to read, and Unblock did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QkListAdapterSelectionTest {

    private class Adapter : QkListAdapter<Long, QkViewHolder>(itemId = { it }) {
        fun select(id: Long) = toggleSelection(id)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = QkViewHolder(View(parent.context))
        override fun onBindViewHolder(holder: QkViewHolder, position: Int) = Unit
    }

    @Test
    fun aSelectionAlreadySentOutSurvivesClearSelection() = runBlocking {
        val adapter = Adapter()
        adapter.select(7)
        val sent = adapter.selectionChanges.first()

        adapter.clearSelection()

        assertEquals(listOf(7L), sent)
    }

    @Test
    fun aSelectionAlreadySentOutSurvivesTheNextTap() = runBlocking {
        val adapter = Adapter()
        adapter.select(7)
        val sent = adapter.selectionChanges.first()

        adapter.select(8)

        assertEquals(listOf(7L), sent)
    }
}
