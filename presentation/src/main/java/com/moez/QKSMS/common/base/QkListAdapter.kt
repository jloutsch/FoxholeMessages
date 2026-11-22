package dev.octoshrimpy.quik.common.base

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import dev.octoshrimpy.quik.common.util.extensions.setVisible
import timber.log.Timber

/**
 * A list adapter fed plain lists (not live Realm results), with the selection, highlight and
 * empty-view behaviour QkRealmAdapter had. Rows are matched by [itemId]; a row is rebound unless
 * [sameContents] says it hasn't changed.
 */
abstract class QkListAdapter<T : Any, VH : QkViewHolder>(
    private val itemId: (T) -> Long,
    sameContents: (T, T) -> Boolean = { _, _ -> false }
) : ListAdapter<T, VH>(diffCallback(itemId, sameContents)) {

    companion object {
        fun <T : Any> diffCallback(
            itemId: (T) -> Long,
            sameContents: (T, T) -> Boolean = { _, _ -> false }
        ): DiffUtil.ItemCallback<T> = object : DiffUtil.ItemCallback<T>() {
            override fun areItemsTheSame(oldItem: T, newItem: T) = itemId(oldItem) == itemId(newItem)
            override fun areContentsTheSame(oldItem: T, newItem: T) = sameContents(oldItem, newItem)
        }
    }

    /** null until the first list arrives, so the empty view doesn't flash before data loads. */
    var data: List<T>? = null
        private set

    /** Shown when the latest list is empty; hidden before any list has arrived. */
    var emptyView: View? = null
        set(value) {
            if (field === value) return
            field = value
            value?.setVisible(data?.isEmpty() == true)
        }

    // The current selection (a BehaviorSubject under RxJava): replays the latest to new collectors
    val selectionChanges = MutableSharedFlow<List<Long>>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var selection = mutableListOf<Long>()

    var highlight: Long = -1L
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun updateData(list: List<T>?) {
        data = list
        emptyView?.setVisible(list?.isEmpty() == true)
        submitList(list)
    }

    override fun getItemId(position: Int): Long = getItem(position)?.let(itemId) ?: -1L

    public override fun getItem(position: Int): T? {
        if (position < 0 || position >= itemCount) {
            Timber.w("No item at position $position (count $itemCount)")
            return null
        }
        return super.getItem(position)
    }

    /**
     * Toggles the selected state for a particular view. If we are not in selection mode, we only
     * toggle if [force].
     */
    protected fun toggleSelection(id: Long, force: Boolean = true): Boolean {
        if (!force && selection.isEmpty()) return false

        when (selection.contains(id)) {
            true -> selection -= id
            false -> selection += id
        }
        selectionChanges.tryEmit(selection)
        return true
    }

    protected fun isSelected(id: Long): Boolean = selection.contains(id)

    fun clearSelection() {
        selection.clear()
        selectionChanges.tryEmit(selection)
        notifyDataSetChanged()
    }

    fun toggleSelectAll() {
        val needToSelectAll = selection.size != itemCount
        selection.clear()
        if (needToSelectAll) {
            for (position in 0 until itemCount) selection += getItemId(position)
        }
        selectionChanges.tryEmit(selection)
        notifyDataSetChanged()
    }
}
