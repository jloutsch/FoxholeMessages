package dev.octoshrimpy.quik.common.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list adapter that replaces QkRealmAdapter (Realm → Room, Task 1) tells rows apart by id. */
class QkListAdapterDiffTest {

    private class Row(val id: Long, val text: String)

    @Test
    fun itemsAreTheSameById() {
        val callback = QkListAdapter.diffCallback<Row>(itemId = { it.id })
        assertTrue(callback.areItemsTheSame(Row(7, "a"), Row(7, "b")))
        assertFalse(callback.areItemsTheSame(Row(7, "a"), Row(8, "a")))
    }

    @Test
    fun contentsDifferByDefault() {
        val callback = QkListAdapter.diffCallback<Row>(itemId = { it.id })
        assertFalse(callback.areContentsTheSame(Row(7, "a"), Row(7, "a")))
    }

    @Test
    fun contentsUseTheGivenComparison() {
        val callback = QkListAdapter.diffCallback<Row>(itemId = { it.id }, sameContents = { a, b -> a.text == b.text })
        assertTrue(callback.areContentsTheSame(Row(7, "a"), Row(7, "a")))
        assertFalse(callback.areContentsTheSame(Row(7, "a"), Row(7, "b")))
    }
}
