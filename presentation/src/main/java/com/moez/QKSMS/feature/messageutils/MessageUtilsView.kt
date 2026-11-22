package dev.octoshrimpy.quik.feature.messageutils

import dev.octoshrimpy.quik.common.base.QkFlowView
import kotlinx.coroutines.flow.Flow

interface MessageUtilsView: QkFlowView<MessageUtilsState> {
    val autoDeduplicateClickIntent: Flow<Unit>
    val deduplicateClickIntent: Flow<Unit>
    val autoDeleteClickIntent: Flow<Unit>

    suspend fun showDeduplicationConfirmationDialog(): Boolean
    fun handleDeduplicationResult(resIdString: Int)

    fun autoDeleteChanged(): Flow<Int>
    fun showAutoDeleteDialog(days: Int)
    suspend fun showAutoDeleteWarningDialog(messages: Int): Boolean
}
