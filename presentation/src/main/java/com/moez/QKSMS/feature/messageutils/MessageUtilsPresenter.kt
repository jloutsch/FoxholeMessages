package dev.octoshrimpy.quik.feature.messageutils

import android.content.Context
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkFlowPresenter
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import dev.octoshrimpy.quik.interactor.DeduplicateMessages
import dev.octoshrimpy.quik.interactor.DeleteOldMessages
import dev.octoshrimpy.quik.worker.RetentionWorker
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import javax.inject.Inject

class MessageUtilsPresenter @Inject constructor(
    private val context: Context,
    private val deduplicateMessages: DeduplicateMessages,
    private val prefs: Preferences,
    private val deleteOldMessages: DeleteOldMessages,
    private val messageRepo: MessageRepository
) : QkFlowPresenter<MessageUtilsView, MessageUtilsState>(MessageUtilsState()) {
    init {
        @OptIn(FlowPreview::class) // sample() is stable in practice; still marked preview
        messageRepo.deduplicationProgress
            .sample(16)
            .collectIn(presenterScope) { progress -> newState { copy(deduplicationProgress = progress) } }

        prefs.autoDeduplicate.asFlow()
            .collectIn(presenterScope) { enabled -> newState { copy(autoDeduplicateMessages = enabled) } }

        prefs.autoDelete.asFlow()
            .collectIn(presenterScope) { autoDelete -> newState { copy(autoDelete = autoDelete) } }
    }

    override fun bindIntents(view: MessageUtilsView) {
        super.bindIntents(view)

        view.deduplicateClickIntent.collectIn(view.bindScope) {
            if (!view.showDeduplicationConfirmationDialog()) return@collectIn
            when (val result = withContext(Dispatchers.IO) { deduplicateMessages.perform(Unit) }) {
                is MessageRepository.DeduplicationResult.Success ->
                    view.handleDeduplicationResult(R.string.deduplicating_messages_finished)

                is MessageRepository.DeduplicationResult.NoDuplicates ->
                    view.handleDeduplicationResult(R.string.deduplicating_messages_no_messages)

                is MessageRepository.DeduplicationResult.Failure -> {
                    view.handleDeduplicationResult(R.string.deduplicating_messages_failed)
                    Timber.e("Error while deduplicating messages: ${result.error}")
                }
            }
        }

        view.autoDeduplicateClickIntent.collectIn(view.bindScope) {
            prefs.autoDeduplicate.set(!prefs.autoDeduplicate.get())
        }

        view.autoDeleteClickIntent.collectIn(view.bindScope) {
            view.showAutoDeleteDialog(prefs.autoDelete.get())
        }

        view.autoDeleteChanged().collectIn(view.bindScope) { maxAge ->
            // Choosing a period that would delete messages right away asks first
            if (maxAge != 0) {
                val count = withContext(Dispatchers.IO) { messageRepo.getOldMessageCounts(maxAge).values.sum() }
                if (count != 0 && !view.showAutoDeleteWarningDialog(count)) return@collectIn
            }

            when (maxAge == 0) {
                true -> RetentionWorker.cancel(context, RetentionWorker.AUTO_DELETE)
                false -> {
                    RetentionWorker.schedule(context, RetentionWorker.AUTO_DELETE)
                    deleteOldMessages.execute(Unit)
                }
            }
            prefs.autoDelete.set(maxAge)
        }
    }
}
