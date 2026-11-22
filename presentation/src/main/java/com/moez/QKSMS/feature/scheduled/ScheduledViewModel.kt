package dev.octoshrimpy.quik.feature.scheduled

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.util.ClipboardUtils
import dev.octoshrimpy.quik.interactor.DeleteScheduledMessages
import dev.octoshrimpy.quik.interactor.SendScheduledMessage
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import javax.inject.Inject
import javax.inject.Named

class ScheduledViewModel @Inject constructor(
    @Named("conversationId") private val conversationId: Long?,
    private val context: Context,
    private val navigator: Navigator,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val sendScheduledMessageInteractor: SendScheduledMessage,
    private val deleteScheduledMessagesInteractor: DeleteScheduledMessages,
) : QkViewModel<ScheduledView, ScheduledState>(ScheduledState(
    conversationId = conversationId
)) {

    init {
        loadMessages(conversationId)
    }

    override fun bindView(view: ScheduledView) {
        super.bindView(view)

        // The latest selection, which the menu actions apply to (what withLatestFrom read)
        var selectedMessages: List<Long> = emptyList()

        // update the state when the message selected count changes
        view.messagesSelectedIntent.collectIn(view.bindScope) { selection ->
            selectedMessages = selection
            newState { copy(selectedMessages = selection.size) }
        }

        view.optionsItemIntent.collectIn(view.bindScope) { itemId ->
            when (itemId) {
                // toggle select all / select none
                R.id.select_all -> view.toggleSelectAll()

                // show the delete message dialog if one or more messages selected
                R.id.delete -> {
                    val selected = selectedMessages
                    val ids = withContext(Dispatchers.IO) {
                        selected.mapNotNull(scheduledMessageRepo::getScheduledMessage).map { it.id }
                    }
                    view.showDeleteDialog(ids)
                }

                // copy the selected message text to the clipboard
                R.id.copy -> {
                    val selected = selectedMessages
                    val text = withContext(Dispatchers.IO) {
                        selected  // same order as messages on screen
                            .mapNotNull(scheduledMessageRepo::getScheduledMessage)
                            .sortedBy { it.date }
                            .let { scheduledMessages ->
                                when (scheduledMessages.size) {
                                    1 -> scheduledMessages.first().body
                                    else -> scheduledMessages.fold(StringBuilder()) { acc, message ->
                                        if (acc.isNotEmpty() && message.body.isNotEmpty())
                                            acc.append("\n\n")
                                        acc.append(message.body)
                                    }
                                }.toString()
                            }
                    }
                    ClipboardUtils.copy(context, text)
                }

                // send the messages now menu item selected
                R.id.send_now -> view.showSendNowDialog(selectedMessages)

                // edit message menu item selected
                R.id.edit_message -> selectedMessages.firstOrNull()?.let(view::showEditMessageDialog)

                // navigate back or unselect
                android.R.id.home -> backOrUnselect(view)
            }
        }

        // delete message(s) (fired after the confirmation dialog has been shown)
        view.deleteScheduledMessages.collectIn(view.bindScope) { selectedMessagesIds ->
            deleteScheduledMessagesInteractor.execute(selectedMessagesIds.toList())
            view.clearSelection()
        }

        // send message(s) now (fired after the confirmation dialog has been shown)
        view.sendScheduledMessages.collectIn(view.bindScope) { selectedMessagesIds ->
            selectedMessagesIds.forEach { selectedMessagesId ->
                sendScheduledMessageInteractor.execute(selectedMessagesId)
            }
            view.clearSelection()
        }

        // edit message (fired after the confirmation dialog has been shown)
        view.editScheduledMessage.collectIn(view.bindScope) { selectedMessageId ->
            val scheduledMessage = withContext(Dispatchers.IO) {
                scheduledMessageRepo.getScheduledMessage(selectedMessageId)
            }
            if (scheduledMessage != null) {
                navigator.showCompose(scheduledMessage)
                withContext(Dispatchers.IO) { scheduledMessageRepo.deleteScheduledMessage(scheduledMessage.id) }
            }
            view.clearSelection()
        }

        // navigate back or unselect
        view.backPressedIntent.collectIn(view.bindScope) { backOrUnselect(view) }

        view.composeIntent.collectIn(view.bindScope) {
            navigator.showCompose(mode = "scheduling")
            view.clearSelection()
        }
    }

    private fun backOrUnselect(view: ScheduledView) = when {
        state.value.selectedMessages > 0 -> view.clearSelection()
        else -> view.finishActivity()
    }

    // Replaced, not added to, when the conversation filter changes
    private var messagesJob: Job? = null

    private fun loadMessages(conversationId: Long?) {
        val messages = if (conversationId != null)
            scheduledMessageRepo.getScheduledMessagesForConversation(conversationId)
        else
            scheduledMessageRepo.observeScheduledMessages()
        messagesJob?.cancel()
        messagesJob = messages.collectIn(viewModelScope) { list -> newState { copy(scheduledMessages = list) } }
    }
}
