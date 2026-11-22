/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // flatMapLatest: stable in practice

package dev.octoshrimpy.quik.feature.compose

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.base.QkViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Vibrator
import android.telephony.SmsMessage
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toFile
import com.moez.QKSMS.common.QkMediaPlayer
import com.moez.QKSMS.contentproviders.MmsPartProvider
import com.moez.QKSMS.manager.BluetoothMicManager
import com.moez.QKSMS.manager.MediaRecorderManager
import com.moez.QKSMS.manager.MediaRecorderManager.AUDIO_FILE_PREFIX
import com.moez.QKSMS.manager.MediaRecorderManager.AUDIO_FILE_SUFFIX
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.ExternalNavigator
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.util.ClipboardUtils
import dev.octoshrimpy.quik.common.util.MessageDetailsFormatter
import dev.octoshrimpy.quik.common.util.extensions.makeToast
import dev.octoshrimpy.quik.common.widget.MicInputCloudView
import dev.octoshrimpy.quik.common.widget.QkContextMenuRecyclerView
import dev.octoshrimpy.quik.compat.SubscriptionManagerCompat
import dev.octoshrimpy.quik.extensions.isAudio
import dev.octoshrimpy.quik.extensions.isImage
import dev.octoshrimpy.quik.extensions.isSafeToAttachFromOtherApp
import dev.octoshrimpy.quik.extensions.isSmil
import dev.octoshrimpy.quik.extensions.isText
import dev.octoshrimpy.quik.extensions.isVideo
import dev.octoshrimpy.quik.interactor.ActionDelayedMessage
import dev.octoshrimpy.quik.interactor.AddScheduledMessage
import dev.octoshrimpy.quik.interactor.DeleteMessages
import dev.octoshrimpy.quik.interactor.MarkRead
import dev.octoshrimpy.quik.interactor.SendExistingMessage
import dev.octoshrimpy.quik.interactor.SaveImage
import dev.octoshrimpy.quik.interactor.SendNewMessage
import dev.octoshrimpy.quik.manager.ActiveConversationManager
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.model.Attachment
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.getText
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import dev.octoshrimpy.quik.util.activeSubscriptions
import dev.octoshrimpy.quik.util.FileUtils
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.extensions.getResourceBytes
import dev.octoshrimpy.quik.util.Constants.Companion.DELAY_CANCELLED_CACHED_ATTACHMENTS_FILE_PREFIX
import dev.octoshrimpy.quik.util.Constants.Companion.SAVED_MESSAGE_TEXT_FILE_PREFIX
import dev.octoshrimpy.quik.util.tryOrNull
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import android.os.VibrationEffect
import android.os.Build

class ComposeViewModel @Inject constructor(
    @Named("query") private val query: String,
    @Named("threadId") private val threadId: Long,
    @Named("addresses") private val addresses: List<String>,
    @Named("text") private val sharedText: String,
    @Named("attachments") val sharedAttachments: List<Attachment>,
    @Named("mode") private val mode: String,
    @Named("subscriptionId") val sharedSubscriptionId: Int,
    @Named("sendAsGroup") val sharedSendAsGroup: Boolean?,
    @Named("scheduleDateTime") val sharedScheduledDateTime: Long,
    private val contactRepo: ContactRepository,
    private val context: Context,
    private val activeConversationManager: ActiveConversationManager,
    private val addScheduledMessage: AddScheduledMessage,
    private val actionDelayedMessage: ActionDelayedMessage,
    private val conversationRepo: ConversationRepository,
    private val deleteMessages: DeleteMessages,
    private val markRead: MarkRead,
    private val messageDetailsFormatter: MessageDetailsFormatter,
    private val messageRepo: MessageRepository,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val navigator: Navigator,
    private val externalNavigator: ExternalNavigator,
    private val permissionManager: PermissionManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val sendExistingMessage: SendExistingMessage,
    private val sendNewMessage: SendNewMessage,
    private val subscriptionManager: SubscriptionManagerCompat,
    private val saveImage: SaveImage,
) : QkViewModel<ComposeView, ComposeState>(ComposeState(
        editingMode = threadId == 0L && addresses.isEmpty(),
        threadId = threadId,
        query = query)
) {
    private val chipsReducer = events<(List<Recipient>) -> List<Recipient>>()

    // Latest-value streams (BehaviorSubjects under RxJava): each replays its last value to new
    // collectors and repeats equal values, which a StateFlow would skip
    private val conversation = latest<Conversation>()
    private val messages = latest<List<Message>>()
    private val selectedChips = latest<List<Recipient>>().apply { tryEmit(listOf()) }
    private val searchResults = latest<List<Message>>()
    private val searchSelection = latest<Long>().apply { tryEmit(-1L) }

    private val latestConversation: Conversation? get() = conversation.replayCache.lastOrNull()

    private var shouldShowContacts = threadId == 0L && addresses.isEmpty()
    private var showScheduledToast = false

    private var bluetoothMicManager: BluetoothMicManager? = null

    init {
        // set shared subscription into state if set
        subscriptionManager.activeSubscriptionInfoList.firstOrNull {
            it.subscriptionId == sharedSubscriptionId
        }?.let { newState { copy(subscription = it)} }

        // set shared scheduled datetime into state if set
        if (sharedScheduledDateTime != 0L)
            newState { copy (scheduled = sharedScheduledDateTime) }

        // set shared sendAsGroup into state if set
        if (sharedSendAsGroup != null)
            newState { copy(sendAsGroup = sharedSendAsGroup) }

        // set shared attachments into state
        newState { copy(attachments = sharedAttachments) }

        // A deleted conversation arrives as null, which closes the screen (hasError)
        val initialConversation: Flow<Conversation> = threadId.takeIf { it != 0L }
            ?.let { id -> conversationRepo.observeConversation(id) }
            ?.onEach { conversation -> if (conversation == null) newState { copy(hasError = true) } }
            ?.filterNotNull()
            ?: emptyFlow()

        val selectedConversation = selectedChips
            .dropWhile { recipients -> recipients.isEmpty() }
            .map { recipients -> recipients.map { it.address } }
            .distinctUntilChanged()
            .onEach { newState { copy(loading = true) } }
            // background thread for possible long telephony running
            .onEach { addresses -> withContext(Dispatchers.IO) { conversationRepo.getOrCreateConversation(addresses) } }
            .flatMapLatest { addresses ->
                // monitors convos and triggers when wanted convo is present
                conversationRepo.getConversations(false)
                    .mapNotNull { withContext(Dispatchers.IO) { conversationRepo.getConversation(addresses) } }
                    .onEach { newState { copy(loading = false) } }
            }
            .catch { e ->
                Timber.e(e, "Error while resolving conversation")
                newState { copy(loading = false) }
            }

        // Merges two potential conversation sources (constructor threadId and contact selection)
        // into a single stream of conversations. A deleted conversation is handled above.
        merge(selectedConversation, initialConversation).collectIn(viewModelScope) { conversation.emit(it) }

        if (addresses.isNotEmpty())
            selectedChips.tryEmit(addresses.map { address -> Recipient(address = address) })

        // The chip edits accumulate from the start, but only reach selectedChips once editing mode
        // has begun, and stop for good when it ends (skipUntil/takeUntil under RxJava)
        var editingStarted = false
        val chipsJob = chipsReducer
                .scan(listOf<Recipient>()) { previousState, reducer -> reducer(previousState) }
                .onEach { chips -> newState { copy(selectedChips = chips) } }
                .filter { editingStarted }
                .collectIn(viewModelScope) { chips -> selectedChips.emit(chips) }
        viewModelScope.launch {
            state.first { state -> state.editingMode }
            editingStarted = true
        }
        viewModelScope.launch {
            state.first { state -> !state.editingMode }
            chipsJob.cancel()
        }

        // update state sendAsGroup when conversation sendAsGroup value changes
        conversation
            .map { conversation -> conversation.sendAsGroup }
            .distinctUntilChanged()
            .collectIn(viewModelScope) { sendAsGroup -> newState { copy(sendAsGroup = sendAsGroup) } }

        // update recipient count whenever conversation changes
        conversation
            .distinctUntilChanged()
            .collectIn(viewModelScope) { conversation ->
                newState { copy (recipientCount = conversation.recipients.size) }
            }

        // Every conversation update (a renamed contact, #81) with its thread's messages, for the adapter
        var lastMessages: List<Message>? = null
        liveThread(conversation) { threadId -> messageRepo.getMessages(threadId) }
                .collectIn(viewModelScope) { (conversation, list) ->
                    newState { copy(threadId = conversation.id, messages = Pair(conversation, list)) }
                    // Only when the messages themselves changed, not for a conversation-only update
                    if (list !== lastMessages) {
                        lastMessages = list
                        messages.emit(list)
                    }
                }

        conversation
                .map { conversation -> conversation.getTitle() }
                .distinctUntilChanged()
                .collectIn(viewModelScope) { title -> newState { copy(conversationtitle = title) } }

        // Re-runs whenever the query changes, rather than reading it once at subscribe time. It
        // used to take the query from the launch intent and then terminate as soon as the query
        // emptied, which was enough when the only way in was tapping a result on the conversation
        // list - but the in-thread search bar lets the query change repeatedly.
        combine(
                    conversation.map { conversation -> conversation.id }.distinctUntilChanged(),
                    state.map { it.query }.distinctUntilChanged()
                ) { id, query -> id to query }
                .flatMapLatest { (id, query) ->
                    if (query.isEmpty()) flowOf(emptyList())
                    else messageRepo.getMessages(id, query)
                }
                .collectIn(viewModelScope) { results -> searchResults.emit(results) }

        // on conversation change/init, work out how many non-me participants of the conversation
        // have a valid address (subscriber number) for replying/sending to
        conversation
            .distinctUntilChangedBy { conversation -> conversation.id }
            .map { conversation ->
                conversation.recipients.count { recipient -> phoneNumberUtils.isPossibleNumber(recipient.address) }
            }
            .collectIn(viewModelScope) { validRecipientNumbers ->
                newState { copy(validRecipientNumbers = validRecipientNumbers) }
            }

        combine(searchSelection, searchResults) { selected, messages -> selected to messages }
            .collectIn(viewModelScope) { (selected, messages) ->
                when {
                    messages.isEmpty() ->
                        // Also drops the highlight: a cleared search ends here, after the clear's own reducer
                        newState { copy(searchSelectionPosition = 0, searchResults = 0, searchSelectionId = -1) }

                    selected == -1L ->
                        messages.lastOrNull()?.let { message -> searchSelection.emit(message.id) }

                    else -> {
                        val position = messages.indexOfFirst { it.id == selected } + 1
                        newState { copy(searchSelectionPosition = position, searchResults = messages.size) }
                    }
                }
            }

        val latestSubId = messages
                .map { messages -> messages.lastOrNull()?.subId ?: -1 }
                .distinctUntilChanged()

        val subscriptions = activeSubscriptions(subscriptionManager)
        combine(latestSubId, subscriptions) { subId, subs ->
            if (subs.size > 1) subs.firstOrNull { it.subscriptionId == subId } ?: subs[0] else null
        }.collectIn(viewModelScope) { sub -> newState { copy(subscription = sub) } }

        // checks if there are any scheduled messages in convo
        conversation
            .distinctUntilChangedBy { conversation -> conversation.id }
            .flatMapLatest { conversation ->
                scheduledMessageRepo.getScheduledMessagesForConversation(conversation.id)
            }
            .collectIn(viewModelScope) { liveResults ->
                val hasAny = liveResults.isNotEmpty()
                newState { copy(hasScheduledMessages = hasAny) }
            }

        // actions
        if (mode == "scheduling")
            newState { copy(scheduling = true) }
    }

    @SuppressLint("StringFormatInvalid")
    override fun bindView(view: ComposeView) {
        super.bindView(view)

        val sharing = (sharedText.isNotEmpty() || sharedAttachments.isNotEmpty())
        if (shouldShowContacts) {
            shouldShowContacts = false
            view.showContacts(sharing, selectedChips.replayCache.last())
        }

        // The latest message selection (what withLatestFrom read); null until the adapter reports one
        var selectedMessages: List<Long>? = null
        view.messagesSelectedIntent.collectIn(view.bindScope) { selection -> selectedMessages = selection }

        // The text in the message box, for sending and saving the draft
        var messageText: CharSequence = ""
        view.textChangedIntent.collectIn(view.bindScope) { text -> messageText = text }

        view.chipsSelectedIntent.collectIn(view.bindScope) { hashmap ->
            val chips = selectedChips.replayCache.last()
            // If there's no contacts already selected, and the user cancelled the contact
            // selection, close the activity
            if (hashmap.isEmpty() && chips.isEmpty()) {
                newState { copy(hasError = true) }
            }
            // Filter out any numbers that are already selected
            val newAddresses = hashmap.filter { (address) ->
                chips.none { recipient -> phoneNumberUtils.compare(address, recipient.address) }
            }
            if (newAddresses.isEmpty()) return@collectIn

            val newChips = withContext(Dispatchers.IO) {
                newAddresses.map { (address, lookupKey) ->
                    conversationRepo.getRecipients()
                            .asSequence()
                            .filter { recipient -> recipient.contact?.lookupKey == lookupKey }
                            .firstOrNull { recipient -> phoneNumberUtils.compare(recipient.address, address) }
                            ?: Recipient(address = address).apply {
                                    contact = lookupKey?.let(contactRepo::getUnmanagedContact) }
                }
            }
            chipsReducer.tryEmit { list -> list + newChips }
            view.showKeyboard()
        }

        // Set the contact suggestions list to visible when the add button is pressed
        view.optionsItemIntent.filter { it == R.id.add }.collectIn(view.bindScope) {
            newState { copy(saveDraft = false) }  // do not save draft on next activity invisibility
            view.showContacts(sharing, selectedChips.replayCache.last())
        }

        // Update the list of selected contacts when a new contact is selected or an existing one is deselected
        view.chipDeletedIntent.collectIn(view.bindScope) { contact ->
            chipsReducer.tryEmit { contacts ->
                val result = contacts.filterNot { it == contact }
                if (result.isEmpty()) {
                    view.showContacts(sharing, result)
                }
                result
            }
        }

        // When the menu is loaded, render again so that the menu options are drawn correctly. An
        // unchanged state isn't re-emitted by a StateFlow, so render directly.
        view.menuReadyIntent.collectIn(view.bindScope) { view.render(state.value) }

        // Show scheduled messages
        view.optionsItemIntent.filter { it == R.id.viewScheduledMessages }.collectIn(view.bindScope) {
            latestConversation?.let { conversation -> navigator.showScheduled(conversation.id) }
        }

        // toggle select all / select none
        view.optionsItemIntent.filter { it == R.id.select_all }.collectIn(view.bindScope) { view.toggleSelectAll() }

        // Open the phone dialer if the call button is clicked
        view.optionsItemIntent.filter { it == R.id.call }.collectIn(view.bindScope) {
            val conversation = latestConversation ?: return@collectIn
            val address = state.value.messages?.second?.lastOrNull { !it.isMe() }?.address // most recent non-me msg address
                ?: conversation.recipients.firstOrNull()?.address  // first recipient in convo
            address?.let(externalNavigator::makePhoneCall)
        }

        // Open the conversation settings if info button is clicked
        view.optionsItemIntent.filter { it == R.id.info }.collectIn(view.bindScope) {
            latestConversation?.let { conversation -> navigator.showConversationInfo(conversation.id) }
        }

        // Copy the message contents
        view.optionsItemIntent.filter { it == R.id.copy }.collectIn(view.bindScope) {
            val messageIds = selectedMessages ?: return@collectIn
            val text = withContext(Dispatchers.IO) {
                messageIds.mapNotNull(messageRepo::getMessage).sortedBy { it.date }.getText()
            }
            ClipboardUtils.copy(context, text)
            view.clearSelection()
        }

        // share the message text contents
        view.optionsItemIntent.filter { it == R.id.share }.collectIn(view.bindScope) {
            val messageIds = selectedMessages ?: return@collectIn
            val filename = "$SAVED_MESSAGE_TEXT_FILE_PREFIX${
                SimpleDateFormat(
                    "yyyy-MM-dd-HH-mm-ss",
                    Locale.getDefault()
                ).format(System.currentTimeMillis())}.txt"

            val mimeType = "text/plain"

            // save all messages text to a file in cache
            val (uri, e) = withContext(Dispatchers.IO) {
                FileUtils.createAndWrite(
                    context,
                    FileUtils.Location.Cache,
                    filename,
                    mimeType,
                    messageIds
                        .mapNotNull(messageRepo::getMessage)
                        .sortedBy { it.date }
                        .getText()
                        .toByteArray()
                )
            }

            if (e is Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.messages_text_share_file_error),
                    Toast.LENGTH_LONG
                ).show()
                Timber.e(e, "Error writing to messages text cache file")
            } else {
                // share file from cache
                navigator.viewFile(
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.messagesText",
                        uri.toFile()
                    ),
                    mimeType
                )
                Timber.d("Created and shared messages text file: $filename")
            }
            view.clearSelection()
        }

        // Show the message details
        view.optionsItemIntent.filter { it == R.id.details }.collectIn(view.bindScope) {
            val messageId = selectedMessages?.let { messages -> messages.firstOrNull().also { view.clearSelection() } }
                ?: return@collectIn
            val details = withContext(Dispatchers.IO) {
                messageRepo.getMessage(messageId)?.let(messageDetailsFormatter::format)
            } ?: return@collectIn
            view.showDetails(details)
        }

        // Toggle the starred state of the selected message
        view.optionsItemIntent.filter { it == R.id.star }.collectIn(view.bindScope) {
            val messageId = selectedMessages?.let { messages -> messages.firstOrNull().also { view.clearSelection() } }
                ?: return@collectIn
            withContext(Dispatchers.IO) {
                messageRepo.getMessage(messageId)?.let { message -> messageRepo.setStarred(message.id, !message.isStarred) }
            }
        }

        // Show the delete message dialog if one or more messages selected
        view.optionsItemIntent.filter { it == R.id.delete }.collectIn(view.bindScope) {
            val messageIds = selectedMessages ?: return@collectIn
            if (permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() }) {
                view.showDeleteDialog(messageIds)
            }
        }

        // show the clear current message dialog if no messages selected
        view.optionsItemIntent.filter { it == R.id.delete }.collectIn(view.bindScope) {
            if (state.value.selectedMessages == 0) view.showClearCurrentMessageDialog()
        }

        // Forward the message
        view.optionsItemIntent.filter { it == R.id.forward }.collectIn(view.bindScope) {
            val messageIds = selectedMessages ?: return@collectIn
            // the message may be gone
            val message = withContext(Dispatchers.IO) { messageIds.firstOrNull()?.let { messageRepo.getMessage(it) } }
            message?.let {
                navigator.showCompose(message.getText(), message.parts.filter { !it.isSmil() }.mapNotNull { it.getUri() })
            }
            view.clearSelection()
        }

        // expand message to show additional info
        view.optionsItemIntent.filter { it == R.id.show_status }.collectIn(view.bindScope) {
            val messageIds = selectedMessages ?: return@collectIn
            view.expandMessages(messageIds, true)
            view.clearSelection()
        }

        // Show the previous search result
        view.optionsItemIntent.filter { it == R.id.previous }.collectIn(view.bindScope) {
            val messages = searchResults.replayCache.lastOrNull() ?: return@collectIn
            val selection = searchSelection.replayCache.last()
            val currentPosition = messages.indexOfFirst { it.id == selection }
            val id = if (currentPosition <= 0L) messages.lastOrNull()?.id ?: -1
                     else messages.getOrNull(currentPosition - 1)?.id ?: -1
            if (id != -1L) searchSelection.emit(id)
        }

        // Show the next search result
        view.optionsItemIntent.filter { it == R.id.next }.collectIn(view.bindScope) {
            val messages = searchResults.replayCache.lastOrNull() ?: return@collectIn
            val selection = searchSelection.replayCache.last()
            val currentPosition = messages.indexOfFirst { it.id == selection }
            val id = if (currentPosition >= messages.size - 1) messages.firstOrNull()?.id ?: -1
                     else messages.getOrNull(currentPosition + 1)?.id ?: -1
            if (id != -1L) searchSelection.emit(id)
        }

        // Open the in-thread search bar
        view.optionsItemIntent.filter { it == R.id.search }.collectIn(view.bindScope) {
            newState { copy(searching = true) }
        }

        // Search as the user types. Debounced the same way the conversation list's search is, so a
        // fast typist doesn't kick off a database query per keystroke.
        @OptIn(FlowPreview::class) // debounce is stable in practice; still marked preview
        view.queryChangedIntent
                .debounce(200)
                .map { query -> query.trim().toString() }
                .distinctUntilChanged()
                .collectIn(view.bindScope) { query -> newState { copy(query = query) } }

        // Clear the search. Resets the selection through its stream as well as the state - the
        // state field alone would leave the old match highlighted the next time results arrive.
        view.optionsItemIntent.filter { it == R.id.clear }.collectIn(view.bindScope) {
            searchSelection.emit(-1)
            newState { copy(query = "", searching = false, searchSelectionId = -1) }
        }

        // message part context menu item selected - save
        view.contextItemIntent
            .filter { it.itemId == R.id.save }
            .filter { permissionManager.hasStorage().also { if (!it) view.requestStoragePermission() } }
            .collectIn(view.bindScope) {
                @Suppress("UNCHECKED_CAST") val menuInfo = it.menuInfo as QkContextMenuRecyclerView.ContextMenuInfo<Long, MmsPart>
                val part = menuInfo.viewHolderValue
                if (part != null)
                    saveImage.execute(part.id) {
                        // Audio goes to Downloads, not the gallery, so saying "Saved to gallery"
                        // would send you looking in the wrong place.
                        context.makeToast(
                            if (part.isAudio()) R.string.audio_toast_saved
                            else R.string.gallery_toast_saved
                        )
                    }
            }

        // message part context menu item selected - save all. On-device testing showed a batch
        // of photos is usually several separate messages, not one MMS with multiple parts, so
        // "the group" is the run of consecutive messages around the long-pressed one, from the
        // same sender, that are themselves nothing but an image/video with no caption text -
        // the run stops at the first text message or a message from someone else. Lets a
        // visually-grouped set of photos be saved without opening the gallery viewer first.
        view.contextItemIntent
            .filter { it.itemId == R.id.save_all }
            .filter { permissionManager.hasStorage().also { if (!it) view.requestStoragePermission() } }
            .collectIn(view.bindScope) {
                @Suppress("UNCHECKED_CAST") val menuInfo = it.menuInfo as QkContextMenuRecyclerView.ContextMenuInfo<Long, MmsPart>
                val part = menuInfo.viewHolderValue ?: return@collectIn
                val partIds = withContext(Dispatchers.IO) { // finding the group reads the database
                    // getMessage() looks a Message up by its own id, but part.messageId holds the
                    // provider's message id (Message.contentId), so passing it here matched nothing
                    // and left this null - which made sameSender false for every candidate, so the
                    // group never formed and save-all quietly saved only the tapped photo.
                    // getMessageForPart() is the lookup that actually goes part -> message.
                    val message = messageRepo.getMessageForPart(part.id)

                    // Parts are looked up by both ids, since MmsPart.messageId is the provider's id
                    // rather than Message.id and the parts relationship has been seen empty on its
                    // own. Logged per-message so if this is still wrong, the next log shows exactly
                    // which check failed.
                    fun isGroupable(candidate: Message): Boolean {
                        val candidateParts = messageRepo.getPartsForMessage(candidate.id, candidate.contentId)
                        val meaningfulParts = candidateParts.filter { p ->
                            !p.isSmil() && !(p.type.lowercase() == "text/plain" && p.text.isNullOrBlank())
                        }
                        val sameSender = message != null && candidate.isMe() == message.isMe() &&
                            candidate.address == message.address
                        val mediaOnly = meaningfulParts.isNotEmpty() &&
                            meaningfulParts.all { p -> p.isImage() || p.isVideo() }
                        val groupable = sameSender && mediaOnly
                        if (!groupable) {
                            Timber.v("save_all: message ${candidate.id} not groupable with " +
                                "${message?.id} - sameSender=$sameSender mediaOnly=$mediaOnly " +
                                "parts=${candidateParts.map { p -> p.type }}")
                        }
                        return groupable
                    }

                    val threadMessages = message?.let { messageRepo.getMessagesSync(it.threadId).toList() }
                    val index = threadMessages?.indexOfFirst { m -> m.id == message.id } ?: -1

                    val group = mutableListOf<Message>()
                    if (threadMessages != null && index != -1) {
                        for (i in index downTo 0) {
                            if (!isGroupable(threadMessages[i])) break
                            group.add(0, threadMessages[i])
                        }
                        for (i in (index + 1) until threadMessages.size) {
                            if (!isGroupable(threadMessages[i])) break
                            group.add(threadMessages[i])
                        }
                    }

                    group
                        .flatMap { m ->
                            messageRepo.getPartsForMessage(m.id, m.contentId)
                                    .filter { p -> p.isImage() || p.isVideo() }
                        }
                        .map { p -> p.id }
                        .takeIf { it.isNotEmpty() }
                        ?: listOf(part.id)
                }

                var remaining = partIds.size
                partIds.forEach { id ->
                    saveImage.execute(id) {
                        remaining -= 1
                        if (remaining == 0) {
                            context.makeToast(context.resources.getQuantityString(
                                    R.plurals.gallery_toast_saved_all, partIds.size, partIds.size))
                        }
                    }
                }
            }

        // message part context menu item selected - share
        view.contextItemIntent.filter { it.itemId == R.id.share }.collectIn(view.bindScope) {
            @Suppress("UNCHECKED_CAST") val menuInfo = it.menuInfo as QkContextMenuRecyclerView.ContextMenuInfo<Long, MmsPart>
            if (menuInfo.viewHolderValue != null)
                navigator.shareFile(
                    MmsPartProvider().getUriForMmsPartId(
                        context, menuInfo.viewHolderValue.id,
                        menuInfo.viewHolderValue.getBestFilename()),
                    menuInfo.viewHolderValue.type
                )
        }

        // message part context menu item selected - forward
        view.contextItemIntent.filter { it.itemId == R.id.forward }.collectIn(view.bindScope) {
            @Suppress("UNCHECKED_CAST") val menuInfo = it.menuInfo as QkContextMenuRecyclerView.ContextMenuInfo<Long, MmsPart>
            if (menuInfo.viewHolderValue != null)
                navigator.showCompose("", listOf(menuInfo.viewHolderValue.getUri()))
        }

        // message part context menu item selected - open externally
        view.contextItemIntent.filter { it.itemId == R.id.openExternally }.collectIn(view.bindScope) {
            @Suppress("UNCHECKED_CAST") val menuInfo = it.menuInfo as QkContextMenuRecyclerView.ContextMenuInfo<Long, MmsPart>
            if (menuInfo.viewHolderValue != null)
                navigator.viewFile(
                    MmsPartProvider().getUriForMmsPartId(
                        context, menuInfo.viewHolderValue.id,
                        menuInfo.viewHolderValue.getBestFilename()),
                    menuInfo.viewHolderValue.type
                )
        }

        // toggle the group sending mode and update the conversation saved value
        view.sendAsGroupIntent.collectIn(view.bindScope) {
            val conversation = latestConversation ?: return@collectIn
            val sendAsGroup = !state.value.sendAsGroup
            withContext(Dispatchers.IO) { conversationRepo.updateSendAsGroup(conversation.id, sendAsGroup) }
        }

        // Scroll to search position
        searchSelection
                .filter { id -> id != -1L }
                .collectIn(view.bindScope) { id ->
                    newState { copy(searchSelectionId = id) }
                    view.scrollToMessage(id)
                }

        // Theme changes
        prefs.keyChanges
                .filter { key -> key.contains("theme") }
                .collectIn(view.bindScope) { view.themeChanged() }

        // Attachment clicks: pictures and videos open in the gallery, anything else in another app
        view.messagePartClickIntent.collectIn(view.bindScope) { partId ->
            val part = withContext(Dispatchers.IO) { messageRepo.getPart(partId) } ?: return@collectIn
            if (part.isImage() || part.isVideo()) {
                navigator.showMedia(part.id)
            } else {
                navigator.viewFile(
                    MmsPartProvider().getUriForMmsPartId(context, part.id, part.getBestFilename()),
                    part.type
                )
            }
        }

        // Save button on an attachment that carries its own (the audio player). Same interactor
        // the context menu's Save uses - this just reaches it from somewhere pressable.
        view.messagePartSaveIntent
                .filter { permissionManager.hasStorage().also { if (!it) view.requestStoragePermission() } }
                .collectIn(view.bindScope) { partId ->
                    saveImage.execute(partId) { context.makeToast(R.string.audio_toast_saved) }
                }

        // Update the State when the message selected count changes
        view.messagesSelectedIntent.collectIn(view.bindScope) { selectedMessageIds ->
            val hasText = withContext(Dispatchers.IO) {
                selectedMessageIds.any { messageRepo.getMessage(it)?.hasNonWhitespaceText() ?: false }
            }
            newState {
                copy(
                    selectedMessages = selectedMessageIds.size,
                    selectedMessagesHaveText = hasText,
                    editingMode = false
                )
            }
        }

        // cancel sending a delayed message
        view.cancelDelayedIntent.collectIn(view.bindScope) { messageId ->
            // most important thing first - cancel the send timer
            val unmanagedMessage = withContext(Dispatchers.IO) {
                messageRepo.cancelDelayedSmsAlarm(messageId)
                messageRepo.getUnmanagedMessage(messageId)
            }

            // copy text from copy of message being cancelled
            view.setDraft(unmanagedMessage?.getText(false) ?: "")
            unmanagedMessage ?: return@collectIn

            // get attachments from copy of message and save locally to be attached to current
            // message. it's done this way because the original message is being deleted and
            // it's mms:// provider attachment uris will go away from
            // it's understood that images re-attached this way could be lower quality than the
            // originals because they may have been reduced to fit in the cancelled mms.
            // in, hopefully, rare cases they could become ridiculously low res if sent and
            // cancelled multiple times with other attachments that force them small.
            // but what can you do.
            val listOfAttachments = withContext(Dispatchers.IO) {
                unmanagedMessage.parts.filter { !(it.isSmil() || it.isText()) }
                    .mapNotNull { unmanagedMessagePart ->
                        try {
                            // get best name of attachment uri
                            val filename = unmanagedMessagePart.getBestFilename()

                            val (cacheFileUri, e) = FileUtils.createAndWrite(
                                context,
                                FileUtils.Location.Cache,
                                "$DELAY_CANCELLED_CACHED_ATTACHMENTS_FILE_PREFIX-" +
                                                    "${UUID.randomUUID()}/${filename}",
                                unmanagedMessagePart.type,
                                unmanagedMessagePart.getUri().getResourceBytes(context)
                            )
                            if (e is Exception)
                                throw e

                            Attachment(context, cacheFileUri)
                        } catch (e: Exception) {
                            null
                        }
                    }
            }
            newState { copy(attachments = listOfAttachments) }

            // cancel/delete the message
            actionDelayedMessage.execute(
                ActionDelayedMessage.Params(unmanagedMessage.id, ActionDelayedMessage.Action.Cancel)
            )
        }

        // send a delayed message now
        view.sendDelayedNowIntent.collectIn(view.bindScope) { messageId ->
            actionDelayedMessage.execute(
                ActionDelayedMessage.Params(messageId, ActionDelayedMessage.Action.Send)
            )
        }

        // resend a failed message
        view.resendIntent.collectIn(view.bindScope) { messageId ->
            val message = withContext(Dispatchers.IO) { messageRepo.getMessage(messageId) } ?: return@collectIn
            if (message.isFailedMessage()) sendExistingMessage.execute(message.id)
        }

        // Show the message details
        view.messageLinkAskIntent.collectIn(view.bindScope) { view.showMessageLinkAskDialog(it) }

        // Show reaction details popup
        view.reactionClickIntent.collectIn(view.bindScope) { messageId ->
            val message = withContext(Dispatchers.IO) { messageRepo.getMessage(messageId) } ?: return@collectIn
            val conv = latestConversation ?: return@collectIn
            val reactions = message.emojiReactions.map { reaction ->
                val contactName = conv.recipients
                    .firstOrNull { recipient ->
                        phoneNumberUtils.compare(recipient.address, reaction.senderAddress)
                    }
                    ?.getDisplayName()
                    ?: reaction.senderAddress
                "${reaction.emoji} $contactName"
            }
            view.showReactionsDialog(reactions)
        }

        // Set the current conversation
        combine(
                view.activityVisibleIntent.distinctUntilChanged(),
                conversation.map { conversation -> conversation.id }.distinctUntilChanged()
        ) { visible, threadId -> visible to threadId }
                .collectIn(view.bindScope) { (visible, threadId) ->
                    when (visible) {
                        true -> {
                            activeConversationManager.setActiveConversation(threadId)
                            markRead.execute(listOf(threadId))
                        }

                        false -> activeConversationManager.setActiveConversation(null)
                    }
                }

        // Save draft when the activity goes into the background. In viewModelScope, so a screen
        // that is closing doesn't cancel the write
        view.activityVisibleIntent.filter { visible -> !visible }.collectIn(view.bindScope) {
            val threadId = latestConversation?.id ?: return@collectIn
            val draftText = messageText
            val saveDraft = state.value.saveDraft
            // NonCancellable: not a child of viewModelScope, so a screen being destroyed can't stop the write
            viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                if (saveDraft)
                    conversationRepo.saveDraft(
                        threadId,
                        if (draftText.isNotBlank()) draftText.toString()
                        else ""
                    )
            }
            newState { copy(saveDraft = true) }
        }

        // Open the attachment options
        view.attachIntent.collectIn(view.bindScope) { newState { copy(attaching = !attaching) } }

        // Attach a photo from camera
        view.cameraIntent.collectIn(view.bindScope) {
            newState { copy(attaching = false) }
            view.requestCamera()
        }

        // pick a photo (specifically) from image provider apps
        view.attachImageFileIntent.collectIn(view.bindScope) {
            newState { copy(attaching = false) }
            view.requestGallery("image/*")
        }

        // pick any file from any provider apps
        view.attachAnyFileIntent.collectIn(view.bindScope) {
            newState { copy(attaching = false) }
            view.requestGallery("*/*")
        }

        // Choose a time to schedule the message
        view.scheduleIntent.collectIn(view.bindScope) {
            newState { copy(attaching = false) }
            view.requestDatePicker()
        }

        view.scheduleAction.take(1).collectIn(view.bindScope) {
            newState { copy(scheduling = false) }
            view.requestDatePicker()
        }

        // an attachment was picked by the user
        merge(
            view.attachAnyFileSelectedIntent.map { uri -> Attachment(context, uri) },
            // Keyboard-inserted content (GIFs, stickers) comes from another app too; see isSafeToAttachFromOtherApp.
            view.inputContentIntent
                .filter { inputContent -> inputContent.contentUri.isSafeToAttachFromOtherApp(context) }
                .map { inputContent -> Attachment(context, inputContent = inputContent) }
        ).collectIn(view.bindScope) {
            newState { copy(attachments = attachments + it, attaching = false) }
        }

        // Set the scheduled time
        view.scheduleSelectedIntent
                .filter { scheduled ->
                    (scheduled > System.currentTimeMillis()).also { future ->
                        if (!future) context.makeToast(R.string.compose_scheduled_future)
                    }
                }
                .collectIn(view.bindScope) { scheduled -> newState { copy(scheduled = scheduled) } }

        // Attach a contact
        view.attachContactIntent.collectIn(view.bindScope) {
            newState { copy(attaching = false) }
            view.requestContact()
        }

        // Contact was selected for attachment
        view.contactSelectedIntent.collectIn(view.bindScope) { uri ->
            try {
                val attachment = withContext(Dispatchers.IO) { Attachment(context, uri = uri) }
                newState { copy(attachments = attachments + attachment) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.makeToast(R.string.compose_contact_error)
                Timber.w(e)
            }
        }

        // Detach an attachment
        view.attachmentDeletedIntent.collectIn(view.bindScope) {
            newState { copy(attachments = attachments - it) }

            // if the attachment is backed by a local file, delete the file
            it.removeCacheFile(context)
        }

        conversation
                .map { conversation -> conversation.draft }
                .distinctUntilChanged()
                .collectIn(view.bindScope) { draft ->

                    // If text was shared into the conversation, it should take priority over the
                    // existing draft
                    //
                    // TODO: Show dialog warning user about overwriting draft
                    if (sharedText.isNotBlank()) {
                        view.setDraft(sharedText)
                    } else {
                        view.setDraft(draft)
                    }
                }

        // set canSend state depending on if there is text input or an attachment
        combine(
            view.textChangedIntent,
            state
                .distinctUntilChangedBy { state -> state.attachments }
                .map { it.attachments.size }
        ) { text, attachmentCount -> text.isNotBlank() || attachmentCount > 0 }
            .collectIn(view.bindScope) { canSend -> newState { copy(canSend = canSend) } }

        // Show the remaining character counter when necessary
        view.textChangedIntent
                // The listener stays on the main thread; only the counting runs on Default
                .map { draft -> draft.toString() }
                .mapNotNull { draft -> withContext(Dispatchers.Default) { tryOrNull { SmsMessage.calculateLength(draft, false) } } }
                .map { array ->
                    val messages = array[0]
                    val remaining = array[2]

                    when {
                        messages <= 1 && remaining > 10 -> ""
                        messages <= 1 && remaining <= 10 -> "$remaining"
                        else -> "$remaining / $messages"
                    }
                }
                .distinctUntilChanged()
                .collectIn(view.bindScope) { remaining -> newState { copy(remaining = remaining) } }

        // Cancel the scheduled time
        view.scheduleCancelIntent.collectIn(view.bindScope) { newState { copy(scheduled = 0) } }

        // Toggle to the next sim slot
        view.changeSimIntent.collectIn(view.bindScope) {
            val subs = subscriptionManager.activeSubscriptionInfoList
            val subIndex = subs.indexOfFirst { it.subscriptionId == state.value.subscription?.subscriptionId }
            val subscription = when {
                subIndex == -1 -> null
                subIndex < subs.size - 1 -> subs[subIndex + 1]
                else -> subs[0]
            }

            if (subscription != null) {
                context.getSystemService<Vibrator>()?.let { vibrator ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION") vibrator.vibrate(40)
                    }
                }
                context.makeToast(context.getString(R.string.compose_sim_changed_toast,
                        subscription.simSlotIndex + 1, subscription.displayName))
            }

            newState { copy(subscription = subscription) }
        }

        // speech recognition button clicked
        view.speechRecogniserIntent.collectIn(view.bindScope) { view.startSpeechRecognition() }

        // shade clicked
        view.shadeIntent.collectIn(view.bindScope) { newState { copy(attaching = false) } }

        // starting or stopping (change state) of audio message ui
        state
            .distinctUntilChangedBy { state -> state.audioMsgRecording }
            .drop(1)    // skip initial value
            .collectIn(view.bindScope) {
                // stop any audio playback (ie from mms attachment or audio recorder)
                QkMediaPlayer.reset()

                // if leaving audio recording mode
                if (!it.audioMsgRecording) {
                    // ensure recording stopped and delete any recording file
                    FileUtils.deleteFile(MediaRecorderManager.stopRecording())
                    view.recordAudioStartStopRecording.tryEmit(false)
                }
            }

        // starting or stopping the recording of audio
        view.recordAudioStartStopRecording.collectIn(view.bindScope) { start ->
            // if start recording
            if (start == true) {
                view.recordAudioPlayerVisible.tryEmit(false)  // hide audio player

                // check have permissions to record audio
                if (permissionManager.hasRecordAudio().also {
                    if (!it) view.requestRecordAudioPermission()
                }) {
                    // create bluetooth mic device manager
                    bluetoothMicManager?.close()
                    bluetoothMicManager = BluetoothMicManager(
                        context,
                        object : BluetoothMicManager.Callbacks {
                            override fun onNoDeviceFound() {
                                // no bluetooth sco device found, use built-in mic
                                this.onConnected(null)
                            }
                            override fun onDeviceFound(device: AudioDeviceInfo?) {
                                // show bluetooth placeholder until bluetooth connected
                                view.recordAudioMsgRecordVisible.tryEmit(false)
                            }
                            override fun onConnecting(device: AudioDeviceInfo?) { /* nothing */ }
                            override fun onConnected(device: AudioDeviceInfo?) {
                                // show record button and chronometer, hide bluetooth placeholder
                                view.recordAudioMsgRecordVisible.tryEmit(true)
                                view.recordAudioChronometer.tryEmit(true)  // start chronometer
                                MediaRecorderManager.startRecording(context, device)
                            }
                            override fun onDisconnected(device: AudioDeviceInfo?) {
                                // if bluetooth disconnects, stop recording
                                if (device != null) {
                                    view.recordAudioRecord.tryEmit(
                                        MicInputCloudView.ViewState.PAUSED_STATE
                                    )
                                }
                            }
                        }
                    )
                    bluetoothMicManager?.startBluetoothDevice()
                }
            } else {
                // stop recording
                bluetoothMicManager?.close()
                view.recordAudioChronometer.tryEmit(false)  // stop chronometer
                MediaRecorderManager.stopRecording()
            }
        }

        // record an audio message menu item or main mic icon
        view.recordAnAudioMessage.collectIn(view.bindScope) {
            view.recordAudioStartStopRecording.tryEmit(true)  // start recording
            newState { copy( attaching = false, audioMsgRecording = true) }
        }

        // abort recording audio message button
        view.recordAudioAbort.collectIn(view.bindScope) { newState { copy( audioMsgRecording = false) } }

        // main record/stop recording audio message button
        view.recordAudioRecord.collectIn(view.bindScope) {
            if (it == MicInputCloudView.ViewState.PAUSED_STATE) {
                view.recordAudioStartStopRecording.tryEmit(false)  // stop recording
                view.recordAudioPlayerVisible.tryEmit(true)  // show audio player
            } else {  // state = start recording
                FileUtils.deleteFile(MediaRecorderManager.uri)  // delete old recording file
                view.recordAudioStartStopRecording.tryEmit(true)  // start new recording
            }
        }

        // attach recorded audio message button
        view.recordAudioAttach.collectIn(view.bindScope) {
            MediaRecorderManager.stopRecording()

            try {
                // create new filename for recorded file because leaving the recording ui
                // will delete the original filename as a catch-all to not leave orphaned files
                val (newUri, e) = FileUtils.create(
                    FileUtils.Location.Cache,
                    context,
                    "$AUDIO_FILE_PREFIX-${UUID.randomUUID()}$AUDIO_FILE_SUFFIX",
                    ""
                )
                if (e is Exception)
                    throw e

                // rename recorded file to new name
                FileUtils.renameTo(MediaRecorderManager.uri, newUri)

                // attach newly named file to message
                val attachment = Attachment(context, newUri)
                newState {
                    copy(
                        audioMsgRecording = false,
                        attachments = attachments + attachment
                    )
                }
            }
            catch (e: Exception) { /* nothing */ }
        }

        // audio recording player play/pause button
        view.recordAudioPlayerPlayPause.collectIn(view.bindScope) {
            when (it) {
                QkMediaPlayer.PlayingState.Paused ->
                    view.recordAudioPlayerConfigUI.tryEmit(
                        QkMediaPlayer.PlayingState.Playing
                    )
                QkMediaPlayer.PlayingState.Playing ->
                    view.recordAudioPlayerConfigUI.tryEmit(
                        QkMediaPlayer.PlayingState.Paused
                    )
                else -> {
                    if (MediaRecorderManager.uri != Uri.EMPTY) {
                        QkMediaPlayer.setOnPreparedListener {
                            view.recordAudioPlayerConfigUI.tryEmit(
                                QkMediaPlayer.PlayingState.Playing
                            )
                        }
                        QkMediaPlayer.setOnCompletionListener {
                            view.recordAudioPlayerConfigUI.tryEmit(
                                QkMediaPlayer.PlayingState.Stopped
                            )
                        }

                        // start the media player play sequence
                        QkMediaPlayer.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )

                        QkMediaPlayer.reset()

                        QkMediaPlayer.setDataSource(context, MediaRecorderManager.uri)

                        QkMediaPlayer.prepareAsync()
                    }
                }
            }
        }

        // Send a message when the send button is clicked, and disable editing mode if it's enabled
        view.sendIntent.collectIn(view.bindScope) {
            val conversation = latestConversation ?: return@collectIn
            sendCurrentMessage(view, messageText, conversation)

            view.focusMessage()

            if (showScheduledToast) {
                context.makeToast(R.string.compose_scheduled_toast)
                showScheduledToast = false
            }
        }

        // Navigate back
        merge(view.optionsItemIntent.filter { it == android.R.id.home }.map { }, view.backPressedIntent)
                .collectIn(view.bindScope) {
                    val state = state.value
                    when {
                        state.selectedMessages > 0 -> view.clearSelection()
                        // Back closes the search bar first rather than leaving the thread outright
                        state.searching || state.query.isNotEmpty() -> {
                            searchSelection.emit(-1)
                            newState { copy(query = "", searching = false, searchSelectionId = -1) }
                        }
                        else -> newState { copy(hasError = true) }
                    }
                }

        // Delete the message
        view.confirmDeleteIntent.collectIn(view.bindScope) {
            val messages = selectedMessages ?: return@collectIn
            val conversation = latestConversation ?: return@collectIn
            deleteMessages.execute(DeleteMessages.Params(messages.toList(), conversation.id))
            view.clearSelection()
        }

        // clear the current message schedule, text and attachments
        view.clearCurrentMessageIntent.collectIn(view.bindScope) { removeCacheFiles ->
            // remove attachments cache files if they exist and flagged to do so
            if (removeCacheFiles)
                state.value.attachments.forEach { it.removeCacheFile(context) }

            view.setDraft("")
            newState {
                copy(
                    editingMode = false,
                    attachments = listOf(),
                    scheduled = 0,
                )
            }
        }
    }

    /** Sends (or schedules) the current message; does nothing when a permission is missing */
    private fun sendCurrentMessage(view: ComposeView, body: CharSequence, conversation: Conversation) {
        if (!permissionManager.isDefaultSms()) {
            view.requestDefaultSms()
            return
        }

        if (!permissionManager.hasSendSms()) {
            view.requestSmsPermission()
            return
        }

        val delay = when (prefs.sendDelay.get()) {
            Preferences.SEND_DELAY_SHORT -> 3000
            Preferences.SEND_DELAY_MEDIUM -> 5000
            Preferences.SEND_DELAY_LONG -> 10000
            else -> 0
        }

        val state = state.value
        if ((delay != 0 || state.scheduled != 0L) && !permissionManager.hasExactAlarms()) {
            externalNavigator.showExactAlarmsSettings()
            return
        }

        val subId = state.subscription?.subscriptionId ?: -1
        val conversationId = (conversation.id)
        val addresses = when (conversation.recipients.isNotEmpty()) {
            true -> conversation.recipients.map { it.address }
            false -> selectedChips.replayCache.last().map { chip -> chip.address }
        }
        val sendAsGroup = ((addresses.size > 1) && state.sendAsGroup)

        when {
            // Scheduling a message
            state.scheduled != 0L -> {
                addScheduledMessage.execute(
                    AddScheduledMessage.Params(
                        state.scheduled,
                        subId,
                        addresses,
                        sendAsGroup,
                        body.toString(),
                        state.attachments.map { it.uri },
                        conversationId
                    )
                )
                newState { copy(scheduled = 0) }
                showScheduledToast = true
            }

            // send message
            else -> {
                sendNewMessage.execute(
                    SendNewMessage.Params(subId, 0, addresses, body.toString(),
                        sendAsGroup, state.attachments.toList(), delay)
                )
            }
        }

        // clear the current message ready for new message composition
        view.clearCurrentMessageIntent.tryEmit(false)
    }

}

/** A replay-1 stream that keeps only the newest value when a collector falls behind (a BehaviorSubject's role) */
private fun <T> latest(): MutableSharedFlow<T> =
    MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
