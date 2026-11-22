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
package dev.octoshrimpy.quik.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import dev.octoshrimpy.quik.db.FoxholeDatabase
import dev.octoshrimpy.quik.db.Relations
import dev.octoshrimpy.quik.db.Tables
import dev.octoshrimpy.quik.db.observe
import com.moez.QKSMS.manager.QkTransaction
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.webkit.MimeTypeMap
import androidx.core.content.contentValuesOf
import com.google.android.mms.ContentType
import com.klinker.android.send_message.SmsManagerFactory
import dev.octoshrimpy.quik.common.util.extensions.now
import dev.octoshrimpy.quik.compat.TelephonyCompat
import dev.octoshrimpy.quik.extensions.isImage
import dev.octoshrimpy.quik.extensions.isVideo
import dev.octoshrimpy.quik.extensions.map
import dev.octoshrimpy.quik.extensions.resourceExists
import dev.octoshrimpy.quik.manager.ActiveConversationManager
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.mapper.CursorToMessage
import dev.octoshrimpy.quik.mapper.CursorToPart
import dev.octoshrimpy.quik.model.Attachment
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.Message.Companion.TYPE_MMS
import dev.octoshrimpy.quik.model.Message.Companion.TYPE_SMS
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.receiver.MessageDeliveredReceiver
import dev.octoshrimpy.quik.receiver.MessageSentReceiver
import dev.octoshrimpy.quik.receiver.SendDelayedMessageReceiver
import dev.octoshrimpy.quik.receiver.SendDelayedMessageReceiver.Companion.MESSAGE_ID_EXTRA
import dev.octoshrimpy.quik.util.ImageUtils
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.util.VideoUtils
import dev.octoshrimpy.quik.util.sha256
import dev.octoshrimpy.quik.util.tryOrNull
import io.reactivex.Flowable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
open class MessageRepositoryImpl @Inject constructor(
    private val activeConversationManager: ActiveConversationManager,
    private val context: Context,
    private val messageIds: KeyManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val syncRepository: SyncRepository,
    // The implementation, not the interface: these call its database-level methods
    private val reactions: EmojiReactionRepositoryImpl,
    private val cursorToMessage: CursorToMessage,
    private val cursorToPart: CursorToPart,
    private val contactRepo: ContactRepository,
    private val database: FoxholeDatabase,
    private val relations: Relations,
) : MessageRepository {

    override val deduplicationProgress =
        MutableStateFlow<MessageRepository.DeduplicationProgress>(MessageRepository.DeduplicationProgress.Idle)

    companion object {
        const val TELEPHONY_UPDATE_CHUNK_SIZE = 200
    }

    private val messageDao get() = database.messageDao()
    private val partDao get() = database.mmsPartDao()

    /** Live messages with their parts and reactions, refreshed when any of them changes. */
    private fun observeMessages(query: () -> List<Message>): Flow<List<Message>> =
        database.observe(Tables.MESSAGES) { relations.messages(query()) }

    override fun getMessages(threadId: Long, query: String): Flow<List<Message>> =
        database.observe(Tables.MESSAGES) { getMessagesSync(threadId, query) }

    override fun getMessagesSync(threadId: Long, query: String): List<Message> =
        relations.threadMessages(
            threadId,
            if (query.isEmpty()) messageDao.thread(threadId) else messageDao.threadSearch(threadId, query)
        )

    override fun getMessage(messageId: Long) = relations.message(messageDao.get(messageId))

    override fun getReactionEmoji(messageIds: List<Long>): Map<Long, String> =
        messageIds.chunked(900).flatMap(database.emojiReactionDao()::forReactionMessages)
            .associate { it.reactionMessageId to it.emoji }

    override fun getUnmanagedMessage(messageId: Long) = getMessage(messageId)

    override fun getMessages(messageIds: Collection<Long>): List<Message> =
        relations.messages(messageIds.toList().chunked(900).flatMap(messageDao::get))

    override fun getMessageForPart(id: Long) = relations.message(messageDao.forPart(id))

    // Trashed messages are excluded, as in the unread queries below: this feeds quick-reply and
    // notification content
    override fun getLastIncomingMessage(threadId: Long): Flow<List<Message>> =
        observeMessages { messageDao.lastIncoming(threadId) }

    override fun getUnreadCount() = messageDao.unreadConversationCount()

    // Same filter as getUnreadMessages(threadId) but a count, since this is called once per visible
    // conversation row (to show a number instead of just a dot). An unread message moved to Trash
    // doesn't keep the badge up.

    override fun getPart(id: Long) = partDao.get(id)

    // Pictures of trashed messages stay out of the gallery
    override fun getPartsForConversation(threadId: Long): Flow<List<MmsPart>> = partDao.observeMediaForThread(threadId)

    // Ascending, unlike getPartsForConversation above: the pager advances through this list as the
    // user swipes left, so it has to run in the same direction the attachments do. Parts are linked
    // by MmsPart.messageId == Message.contentId (the provider's id), so messageId isn't needed.
    override fun getMediaPartsForMessage(messageId: Long, contentId: Long): List<MmsPart> =
        partDao.mediaForMessage(contentId)

    override fun getPartsForMessage(messageId: Long, contentId: Long): List<MmsPart> = partDao.forMessage(contentId)

    override fun savePart(id: Long): Uri? {
        val part = getPart(id) ?: return null

        // MimeTypeMap has no entry for plenty of real attachment types - voice messages in
        // particular arrive as things like audio/amr-wb or audio/x-caf - and returning null here
        // meant the save silently did nothing while still reporting success. Fall back to the
        // mime subtype, which is the extension often enough to be useful and is never worse than
        // not saving at all.
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(part.type)
            ?: part.type.substringAfterLast('/').substringBefore('+')
                .filter { it.isLetterOrDigit() }
                .takeIf { it.isNotEmpty() }
            ?: "bin"
        // fileDateAndTime is divided by 1000 in order to remove the extra 0's after date and time
        // This way the file name isn't so long.
        val fileDateAndTime = messageDao.forPart(part.id)?.date?.div(1000)
        // part.id makes this unique per-attachment - without it, every picture/video in the same
        // MMS message (same parent message date) generated the identical filename, so saving
        // several of them at once (eg. "Save all to gallery") raced on the same MediaStore row
        // instead of creating separate files.
        val fileName = "FoxholeMessages_${part.type.split("/").last()}_${fileDateAndTime}_${part.id}.$extension"

        val values = contentValuesOf(
            MediaStore.MediaColumns.DISPLAY_NAME to fileName,
            MediaStore.MediaColumns.MIME_TYPE to part.type,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            values.put(
                MediaStore.MediaColumns.RELATIVE_PATH, when {
                    part.isImage() -> "${Environment.DIRECTORY_PICTURES}/FoxholeMessages"
                    part.isVideo() -> "${Environment.DIRECTORY_MOVIES}/FoxholeMessages"
                    else -> "${Environment.DIRECTORY_DOWNLOADS}/FoxholeMessages"
                }
            )
        }

        val contentUri = when {
            part.isImage() -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            part.isVideo() -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI

            else -> MediaStore.Files.getContentUri("external")
        }

        val uri = context.contentResolver.insert(contentUri, values)
        Timber.v("Saving $fileName (${part.type}) to $uri")

        if (uri == null) {
            Timber.w("MediaStore refused a row for $fileName (${part.type}) - not saved")
            return null
        }

        uri.let {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                context.contentResolver.openInputStream(part.getUri())?.use { inputStream ->
                    inputStream.copyTo(outputStream, 1024)
                }
            }
            Timber.v("Saved $fileName (${part.type}) to $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    uri,
                    contentValuesOf(MediaStore.MediaColumns.IS_PENDING to 0),
                    null,
                    null
                )
                Timber.v("Marked $uri as not pending")
            }
        }

        return uri
    }

    // Trashed messages are excluded from both: these drive notification contents, and a message the
    // user has already moved to Trash shouldn't reappear in a notification afterwards
    override fun getUnreadUnseenMessages(threadId: Long): List<Message> =
        relations.messages(messageDao.unreadUnseen(threadId))

    override fun getUnreadMessages(threadId: Long): List<Message> = relations.messages(messageDao.unread(threadId))

    override fun observeUnreadMessages(threadId: Long): Flow<List<Message>> =
        observeMessages { messageDao.unread(threadId) }

    // marks all messages in threads as read and/or seen in the native provider
    private fun telephonyMarkSeenRead(
        seen: Boolean?,
        read: Boolean?,
        threadIds: Collection<Long>,
    ): Int {
        if (((seen == null) && (read == null)) || threadIds.isEmpty())
            return -1

        var countUpdated = 0

        // 'read' can be modified at the conversation level which updates all messages
        read?.let {
            tryOrNull(true) {
                // chunked so where clause doesn't get too long if there are many threads
                threadIds.forEach {
                    countUpdated += context.contentResolver.update(
                        ContentUris.withAppendedId(
                            Telephony.MmsSms.CONTENT_CONVERSATIONS_URI,
                            it
                        ),
                        contentValuesOf(Sms.READ to read),
                        "${Sms.READ} = ${if (read) 0 else 1}",
                        null
                    )
                }
            }
        }

        seen?.let {
            // 'seen' has to be modified at the messages level
            threadIds.chunked(TELEPHONY_UPDATE_CHUNK_SIZE).forEach {
                // chunked for smaller where clause size
                val values = contentValuesOf(Sms.SEEN to seen)
                val whereClause = "${Sms.SEEN} = ${if (seen) 0 else 1} " +
                        "and ${Sms.THREAD_ID} in (${it.joinToString(",")})"

                // sms messages
                tryOrNull(true) {
                    countUpdated += context.contentResolver.update(
                        Sms.CONTENT_URI, values, whereClause, null
                    )
                }

                // mms messages
                tryOrNull(true) {
                    countUpdated += context.contentResolver.update(
                        Mms.CONTENT_URI, values, whereClause, null
                    )
                }
            }
        }

        return countUpdated  // a mix of convo and message updates, so not overly useful. meh
    }

    override fun markAllSeen(): Int {
        val threadIds = database.runInTransaction<List<Long>> {
            messageDao.threadsWithUnseen().also { messageDao.markAllSeen() }
        }
        return telephonyMarkSeenRead(true, null, threadIds)
    }

    override fun markSeen(threadIds: Collection<Long>): Int {
        threadIds.toList().chunked(900).forEach { chunk -> messageDao.markSeen(chunk) }
        return telephonyMarkSeenRead(true, null, threadIds)
    }

    override fun markRead(threadIds: Collection<Long>): Int {
        if (threadIds.isEmpty()) return 0
        threadIds.toList().chunked(900).forEach { chunk -> messageDao.markRead(chunk) }
        return telephonyMarkSeenRead(seen = true, read = true, threadIds = threadIds)
    }

    override fun markUnread(threadIds: Collection<Long>): Int {
        if (threadIds.isEmpty()) return 0
        threadIds.toList().chunked(900).forEach { chunk -> messageDao.markLastUnread(chunk) }
        return telephonyMarkSeenRead(null, false, threadIds)
    }

    private fun syncProviderMessage(uri: Uri, sendAsGroup: Boolean): Message? {
        // if uri doesn't have valid type
        val type = when {
            uri.toString().contains(TYPE_MMS) -> TYPE_MMS
            uri.toString().contains(TYPE_SMS) -> TYPE_SMS
            else -> return null
        }

        // if uri doesn't have a valid id, fail
        val contentId = tryOrNull(false) { ContentUris.parseId(uri) } ?: return null

        val stableUri = when (type) {
            TYPE_MMS -> ContentUris.withAppendedId(Mms.CONTENT_URI, contentId)
            else -> ContentUris.withAppendedId(Sms.CONTENT_URI, contentId)
        }

        return context.contentResolver.query(
            stableUri, null, null, null, null
        )?.use { cursor ->
            // if there are no rows, return null. else, move to the first row
            if (!cursor.moveToFirst())
                return null

            cursorToMessage.map(Pair(cursor, CursorToMessage.MessageColumns(cursor))).apply {
                this.sendAsGroup = sendAsGroup

                if (isMms()) {
                    parts = cursorToPart.getPartsCursor(contentId)
                                ?.map { cursorToPart.map(it) }
                                .orEmpty()
                }

                relations.saveProviderMessage(this)
            }
        }
    }

    override fun sendNewMessages(
        subId: Int, toAddresses: Collection<String>, body: String,
        attachments: Collection<Attachment>, sendAsGroup: Boolean, delayMs: Int
    ): Collection<Message> {
        Timber.v("sending message(s)")

        val parts = mutableListOf<com.google.android.mms.MMSPart>()

        if (attachments.isNotEmpty()) {
            Timber.v("has attachments")
            val smsManager = SmsManagerFactory.createSmsManager(subId) // -1 gives the default SIM

            val maxWidth = smsManager.carrierConfigValues
                .getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH)
                .takeIf { prefs.mmsSize.get() == -1 }
                ?: Int.MAX_VALUE

            val maxHeight = smsManager.carrierConfigValues
                .getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT)
                .takeIf { prefs.mmsSize.get() == -1 }
                ?: Int.MAX_VALUE

            var remainingBytes = when (prefs.mmsSize.get()) {
                -1 -> smsManager.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE)
                0 -> Int.MAX_VALUE
                else -> prefs.mmsSize.get() * 1024
            } * 0.9 // Ugly, but buys us a bit of wiggle room

            remainingBytes -= body.takeIf { it.isNotEmpty() }?.toByteArray()?.size ?: 0

            // Attach those that can't be compressed by ImageUtils (ie. everything but images).
            //
            // Videos that don't already fit the remaining budget are routed through
            // VideoUtils's transcoder. Two prior hand-rolled MediaCodec attempts here both
            // failed real-device testing (the second confirmed via external-player testing that
            // the output file's video track was genuinely broken, not a rendering bug in our own
            // player) - VideoUtils.kt now uses the com.otaliastudios:transcoder library instead
            // of hand-rolled MediaCodec, since a third from-scratch attempt seemed unwise given
            // that track record. Still unverified on a real device - do not treat this as done
            // until confirmed. VideoUtils.getScaledVideo() already treats any exception as "give
            // up and send the original bytes unmodified" per its class doc, so a still-broken
            // transcoder degrades back to today's send-at-full-size behavior rather than
            // producing a corrupt attachment.
            parts += attachments
                // filter in non-images only
                .filter { !it.isImage(context) }
                // filter in only items that exist (user may have deleted the file)
                .filter { it.uri.resourceExists(context) }
                .map {
                    val bytes = when {
                        it.isVideo(context) && it.getSize(context) > remainingBytes ->
                            tryOrNull(false) {
                                VideoUtils.getScaledVideo(context, it.uri, remainingBytes.toLong())
                            } ?: it.getResourceBytes(context)

                        else -> it.getResourceBytes(context)
                    }

                    remainingBytes -= bytes.size
                    val part = com.google.android.mms.MMSPart().apply {
                        MimeType = it.getType(context)
                        Name = it.getName(context)
                        Data = bytes
                    }

                    // release the attachment hold on the image bytes so the GC can reclaim
                    it.releaseResourceBytes()

                    part
                }

            val imageBytesByAttachment = attachments
                // filter in images only
                .filter { it.isImage(context) }
                // filter in only items that exist (user may have deleted the file)
                .filter { it.uri.resourceExists(context) }
                .associateWith {
                    when (it.getType(context) == "image/gif") {
                        true -> ImageUtils.getScaledGif(context, it.uri, maxWidth, maxHeight)
                        false -> ImageUtils.getScaledImage(context, it.uri, maxWidth, maxHeight)
                    }
                }
                .toMutableMap()

            val imageByteCount = imageBytesByAttachment.values.sumOf { it.size }
            if (imageByteCount > remainingBytes) {
                imageBytesByAttachment.forEach { (attachment, originalBytes) ->
                    val uri = attachment.uri
                    val maxBytes = originalBytes.size / imageByteCount.toFloat() * remainingBytes

                    // Get the image dimensions
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                    val width = options.outWidth
                    val height = options.outHeight
                    val aspectRatio = width.toFloat() / height.toFloat()

                    var attempts = 0
                    var scaledBytes = originalBytes

                    while (scaledBytes.size > maxBytes) {
                        // Estimate how much we need to scale the image down by. If it's still
                        // too big, we'll need to try smaller and smaller values
                        val scale = maxBytes / originalBytes.size * (0.9 - attempts * 0.2)
                        if (scale <= 0) {
                            Timber.w(
                                "Failed to compress ${
                                    originalBytes.size / 1024
                                }Kb to ${maxBytes.toInt() / 1024}Kb"
                            )
                            return@forEach
                        }

                        val newArea = scale * width * height
                        val newWidth = sqrt(newArea * aspectRatio).toInt()
                        val newHeight = (newWidth / aspectRatio).toInt()

                        attempts++
                        scaledBytes = when (attachment.getType(context) == "image/gif") {
                            true -> ImageUtils.getScaledGif(
                                context, attachment.uri, newWidth, newHeight
                            )

                            false -> ImageUtils.getScaledImage(
                                context, attachment.uri, newWidth, newHeight
                            )
                        }

                        Timber.d(
                            "Compression attempt $attempts: ${
                                scaledBytes.size / 1024
                            }/${maxBytes.toInt() / 1024}Kb ($width*$height -> $newWidth*${
                                newHeight
                            })"
                        )

                        // release the attachment hold on the image bytes so the GC can reclaim
                        attachment.releaseResourceBytes()
                    }

                    Timber.v(
                        "Compressed ${originalBytes.size / 1024}Kb to ${
                            scaledBytes.size / 1024
                        }Kb with a target size of ${
                            maxBytes.toInt() / 1024
                        }Kb in $attempts attempts"
                    )
                    imageBytesByAttachment[attachment] = scaledBytes
                }
            }

            imageBytesByAttachment.forEach { (attachment, bytes) ->
                parts += com.google.android.mms.MMSPart().apply {
                    MimeType =
                        if (attachment.getType(context) == "image/gif") ContentType.IMAGE_GIF
                        else ContentType.IMAGE_JPEG
                    Name = attachment.getName(context)
                    Data = bytes
                }
            }
        }

        Timber.v("create os provider message")

        // 3 stage sending process - stage 1, create records in os provider
        val group = (sendAsGroup && (toAddresses.size > 1))
        val messageUri = QkTransaction.createMessage(
            context, subId, body, prefs.signature.get(),
            toAddresses.map(phoneNumberUtils::normalizeNumber).toTypedArray(),
            parts, group, prefs.longAsMms.get()
        )

        if (messageUri == Uri.EMPTY) {
            Timber.v("create os provider message failed")
            return listOf()
        }

        val message = syncProviderMessage(messageUri, group)
        if (message == null) {
            Timber.v("sync message failed for uri $messageUri")
            return listOf()
        }

        Timber.v("created message id ${message.id} from uri $messageUri")

        if (delayMs > 0) {  // if delaying
            val sendTime = (now() + delayMs)

            // set delay time on the db message
            messageDao.update(message.apply { date = sendTime })

            // create alarm that will trigger sending the message. Exact alarms need the "Alarms &
            // reminders" permission on Android 12+; without it setExact throws and the delayed
            // message never sends, so fall back to an inexact alarm.
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms())
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sendTime, getIntentForDelayedSms(message.id))
            else
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sendTime, getIntentForDelayedSms(message.id))

            Timber.v("set ${delayMs}ms delay for message id ${message.id}")

            return listOf(message)
        }

        // send now (message will be exploded, as required, and all sent)
        return sendMessage(message)
    }

    override fun sendMessage(message: Message): Collection<Message> {
        val retVal = mutableListOf<Message>()

        tryOrNull(true) {
            // explode message if needed
            val explodedMessages = QkTransaction.explodeMessage(
                context, message.getUri(), message.sendAsGroup
            ).filter { explodedMessageUri -> (explodedMessageUri != Uri.EMPTY) }

            // if multiple messages to send, create each and recurse to send
            if (explodedMessages.size > 1) {
                explodedMessages.forEach { explodedMessageUri ->
                    val childMessage = syncProviderMessage(explodedMessageUri, message.sendAsGroup)
                    if (childMessage != null) {
                        Timber.v("created message id ${childMessage.id} from uri $explodedMessageUri")
                        retVal.addAll(sendMessage(childMessage))
                    }
                    else
                        Timber.e("sync failed for uri $explodedMessageUri")
                }

                // mark original message as sent
                markSent(message.id)
            } else {
                markSending(message.id)

                // individual message to send, send it
                val sentIntent = Intent(context, MessageSentReceiver::class.java)
                    .putExtra(MessageSentReceiver.EXTRA_QUIK_MESSAGE_ID, message.id)

                val deliveryIntent =
                    if (prefs.delivery.get())
                        Intent(context, MessageDeliveredReceiver::class.java)
                            .putExtra(MessageDeliveredReceiver.EXTRA_QUIK_MESSAGE_ID, message.id)
                    else null

                // use values from os provider to resend the message, except subId
                if (!QkTransaction.sendMessage(context, message.getUri(), sentIntent, deliveryIntent)) {
                    Timber.e("message id ${message.id} not sent by smsmms")
                    // Nothing was handed to the carrier, so no "sent" result will ever arrive. Report the
                    // failure the way the system would, through MessageSentReceiver, so the message is
                    // marked failed and the "failed to send" notification shows instead of it staying
                    // on "Sending" forever.
                    context.sendOrderedBroadcast(
                        sentIntent, null, null, null, SmsManager.RESULT_ERROR_GENERIC_FAILURE, null, null
                    )
                }
            }

            retVal.add(message)
        }

        return retVal
    }

    override fun sendMessage(messageId: Long) =
        getMessage(messageId)
            ?.let { message -> sendMessage(message) }
            ?: listOf()

    override fun cancelDelayedSmsAlarm(messageId: Long) =
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(getIntentForDelayedSms(messageId))

    private fun getIntentForDelayedSms(messageId: Long) =
        PendingIntent.getBroadcast(
            context,
            messageId.toInt(),
            Intent(context, SendDelayedMessageReceiver::class.java)
                .putExtra(MESSAGE_ID_EXTRA, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun insertReceivedSms(subId: Int, address: String, body: String, sentTime: Long)
    : Message {
        val threadId = TelephonyCompat.getOrCreateThreadId(context, address)

        // insert the message to the native content provider
        val values = contentValuesOf(
            Sms.ADDRESS to address,
            Sms.BODY to body,
            Sms.DATE_SENT to sentTime,
            Sms.THREAD_ID to threadId
        )

        if (prefs.canUseSubId.get())
            values.put(Sms.SUBSCRIPTION_ID, subId)

        val providerContentId = context.contentResolver.insert(Sms.Inbox.CONTENT_URI, values)
            ?.let { insertedUri -> ContentUris.parseId(insertedUri) }
            ?: 0

        // insert the message into the database
        val message = Message().apply {
            id = messageIds.newId()

            this.address = address
            this.body = body
            this.dateSent = sentTime
            this.threadId = threadId
            this.subId = subId

            date = System.currentTimeMillis()

            contentId = providerContentId
            boxId = Sms.MESSAGE_TYPE_INBOX
            type = TYPE_SMS
            read = (activeConversationManager.getActiveConversation() == threadId)
        }

        database.runInTransaction {
            // Android announces this insert to our own sync, which can save the message first;
            // reuse that row's id so the message isn't stored twice
            if (providerContentId != 0L) {
                messageDao.getByProviderId(Message.TYPE_SMS, providerContentId)?.let { synced -> message.id = synced.id }
            }
            messageDao.upsert(message)

            reactions.parseEmojiReaction(body)?.let { parsedReaction ->
                val targetMessage = reactions.findTargetMessage(message, parsedReaction)
                reactions.saveEmojiReaction(message, parsedReaction, targetMessage)
            }
        }

        return message
    }

    override fun markAsSendingNow(messageId: Long) {
        editMessage(messageId) { date = System.currentTimeMillis() }
    }

    /**
     * Marks the message as sending, in case we need to retry sending it
     */
    private fun markSending(messageId: Long) {
        val message = editMessage(messageId) {
            boxId = when (isSms()) {
                true -> Sms.MESSAGE_TYPE_OUTBOX
                false -> Mms.MESSAGE_BOX_OUTBOX
            }
        } ?: return

        // Update the message in the native ContentProvider
        context.contentResolver.update(
            message.getUri(),
            when (message.isSms()) {
                true -> contentValuesOf(Sms.TYPE to Sms.MESSAGE_TYPE_OUTBOX)
                false -> contentValuesOf(Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_OUTBOX)
            },
            null,
            null
        )
    }

    override fun markSent(messageId: Long) {
        Timber.v("mark message id $messageId as sent")

        val message = editMessage(messageId) {
            boxId = if (isSms()) Sms.MESSAGE_TYPE_SENT else Mms.MESSAGE_BOX_SENT
        } ?: return

        // Update the message in the native ContentProvider
        context.contentResolver.update(
            message.getUri(),
            if (message.isSms()) contentValuesOf(Sms.TYPE to Sms.MESSAGE_TYPE_SENT)
            else contentValuesOf(Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_SENT),
            null,
            null
        )
    }

    override fun markFailed(messageId: Long, resultCode: Int): Boolean {
        Timber.v("mark message id $messageId as failed. code $resultCode")

        val message = messageDao.get(messageId) ?: return false
        val failedBox = if (message.isSms()) Sms.MESSAGE_TYPE_FAILED else Mms.MESSAGE_BOX_FAILED
        if (message.boxId == failedBox) return false

        // Update the message in the app's database
        editMessage(messageId) {
            boxId = failedBox
            errorCode = resultCode
        }

        if (message.isSms()) {
            // Update the message in the native ContentProvider
            context.contentResolver.update(
                message.getUri(),
                contentValuesOf(
                    Sms.TYPE to Sms.MESSAGE_TYPE_FAILED,
                    Sms.ERROR_CODE to resultCode,
                ),
                null,
                null
            )
        } else {
            // Update the message in the native ContentProvider
            context.contentResolver.update(
                message.getUri(),
                contentValuesOf(
                    Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_FAILED
                ),
                null,
                null
            )

            // TODO this query isn't able to find any results
            // Need to figure out why the message isn't appearing in the PendingMessages Uri,
            // so that we can properly assign the error type
            context.contentResolver.update(
                Telephony.MmsSms.PendingMessages.CONTENT_URI,
                contentValuesOf(
                    Telephony.MmsSms.PendingMessages.ERROR_TYPE to Telephony.MmsSms.ERR_TYPE_GENERIC_PERMANENT
                ),
                "${Telephony.MmsSms.PendingMessages.MSG_ID} = ?",
                arrayOf(message.id.toString())
            )
        }
        return true
    }

    override fun markDelivered(messageId: Long) {
        Timber.v("mark message id $messageId as delivered")

        val message = editMessage(messageId) {
            deliveryStatus = Sms.STATUS_COMPLETE
            dateSent = System.currentTimeMillis()
            read = true
        } ?: return

        // Update the message in the native ContentProvider
        context.contentResolver.update(
            message.getUri(),
            contentValuesOf(
                Sms.STATUS to Sms.STATUS_COMPLETE,
                Sms.DATE_SENT to System.currentTimeMillis(),
                Sms.READ to true,
            ),
            null,
            null
        )
    }

    override fun markDeliveryFailed(messageId: Long, resultCode: Int) {
        Timber.v("mark message id $messageId as delivery failed result code $resultCode")

        val message = editMessage(messageId) {
            deliveryStatus = Sms.STATUS_FAILED
            dateSent = System.currentTimeMillis()
            read = true
            errorCode = resultCode
        } ?: return

        // Update the message in the native ContentProvider
        context.contentResolver.update(
            message.getUri(),
            contentValuesOf(
                Sms.STATUS to Sms.STATUS_FAILED,
                Sms.DATE_SENT to System.currentTimeMillis(),
                Sms.READ to true,
                Sms.ERROR_CODE to resultCode,
            ),
            null,
            null
        )
    }

    override fun deleteMessages(messageIds: Collection<Long>) =
        hardDelete(messageIds.toList().chunked(900).flatMap(messageDao::get))

    /**
     * Permanently deletes [messages] from this app's database and from the phone's message store.
     * Every permanent delete goes through here, so they all skip messages with no store entry and
     * keep going past one that fails.
     */
    private fun hardDelete(messages: List<Message>) {
        if (messages.isEmpty()) return
        // Uri.EMPTY means the message has no entry in the phone's store; deleting it would throw
        val uris = messages.map { it.getUri() }.filter { it != Uri.EMPTY }
        // Deleting by id is a no-op for rows already gone: picking a retention period both runs a
        // delete and schedules the daily job, which Android can start at the same moment.
        database.runInTransaction {
            messages.map { it.id }.chunked(900).forEach(messageDao::delete)
            messages.filter { it.isMms() }.map { it.contentId }.chunked(900).forEach(partDao::deleteForMessages)
            val reactionIds = messages.map { it.id }.chunked(900).flatMap { chunk ->
                database.emojiReactionDao().forTargets(chunk) + database.emojiReactionDao().forReactionMessages(chunk)
            }.map { it.id }.distinct()
            reactionIds.chunked(900).forEach(database.emojiReactionDao()::delete)
        }
        uris.forEach { uri -> tryOrNull { context.contentResolver.delete(uri, null, null) } }
    }

    override fun trashMessages(messageIds: Collection<Long>) {
        val at = now()
        database.runInTransaction { messageIds.toList().chunked(900).forEach { messageDao.setDeletedAt(it, at) } }
    }

    override fun restoreMessages(messageIds: Collection<Long>) {
        database.runInTransaction { messageIds.toList().chunked(900).forEach { messageDao.setDeletedAt(it, null) } }
    }

    override fun getDeletedMessages(): Flow<List<Message>> =
        observeMessages { messageDao.trash() }

    override fun purgeTrash(maxAgeDays: Int) =
        deleteTrash(trashedBefore = now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong()))

    override fun emptyTrash() = deleteTrash(trashedBefore = Long.MAX_VALUE)

    private fun deleteTrash(trashedBefore: Long) = hardDelete(messageDao.trashedBefore(trashedBefore))

    override fun getOldMessageCounts(maxAgeDays: Int) =
        messageDao.olderThan(now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong()))
            .groupingBy { message -> message.threadId }
            .eachCount()

    override fun deleteOldMessages(maxAgeDays: Int) =
        hardDelete(messageDao.olderThan(now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong())))

    override fun getOldOtpCounts(maxAgeDays: Int) =
        otpRetentionCandidates(maxAgeDays)
            .groupingBy { message -> message.threadId }
            .eachCount()

    override fun deleteOldOtps(maxAgeDays: Int) = hardDelete(otpRetentionCandidates(maxAgeDays))

    /**
     * OTP-tagged messages old enough for retention to delete permanently. Tagging is a loose keyword
     * match ("code"/"pin"/... plus any 4-8 digit number), so it also catches ordinary texts like a
     * friend's "door code is 4821" or "pin it, zip 94110". Deletion is therefore limited to what can
     * only be a received code: incoming, not starred or locked, and not from a saved contact. This is
     * applied here, at deletion, so it also covers messages tagged under the older, looser rules.
     */
    private fun otpRetentionCandidates(maxAgeDays: Int): List<Message> {
        val isContact = HashMap<String, Boolean>()
        return messageDao.otpOlderThan(now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong()))
            .filter { message ->
                OtpRetentionRule.isDeletable(
                    isIncoming = !message.isMe(),
                    isStarred = message.isStarred,
                    isLocked = message.locked,
                    // One cross-process contacts lookup per sender, not per message.
                    isFromContact = { isContact.getOrPut(message.address) { contactRepo.isContact(message.address) } }
                )
            }
    }

    override fun deduplicateMessages(): Flowable<MessageRepository.DeduplicationResult> =
        Flowable.fromCallable {
            val duplicates = findDuplicateMessages()
            if (duplicates.isEmpty()) {
                MessageRepository.DeduplicationResult.NoDuplicates
            } else {
                deduplicationProgress.tryEmit(MessageRepository.DeduplicationProgress.Running(0, 0, true))
                // To Trash rather than deleted outright: a false match is then recoverable for 30
                // days, and the provider rows are removed by the normal Trash purge afterwards.
                trashMessages(duplicates.keys)
                MessageRepository.DeduplicationResult.Success(duplicates.size, duplicates.values.toSet())
            }
        }
        .onErrorReturn { MessageRepository.DeduplicationResult.Failure(it) }
        .doFinally {
            deduplicationProgress.tryEmit(MessageRepository.DeduplicationProgress.Idle)
        }

    /** Duplicate message ids mapped to their thread ids. The first (lowest-id) copy is kept. */
    private fun findDuplicateMessages(): Map<Long, Long> {
        val seenSignatures = HashSet<String>()
        val duplicates = HashMap<Long, Long>()
        val allMessages = relations.messages(messageDao.allVisibleById())
        val max = allMessages.size
        var progress = 0
        allMessages.forEach { message ->
            ++progress
            tryOrNull {
                if (progress % 100 == 0 || progress == max) {
                    deduplicationProgress.tryEmit(
                        MessageRepository.DeduplicationProgress.Running(max, progress, false)
                    )
                }
                val signature = messageHash(message)
                if (!seenSignatures.add(signature)) {
                    duplicates[message.id] = message.threadId
                }
            }
        }
        return duplicates
    }

    /** See [DuplicateSignature] for what duplicates must share, and why. */
    private fun messageHash(message: Message): String = sha256(
        DuplicateSignature.of(
            type = message.type,
            threadId = message.threadId,
            address = message.address,
            boxId = message.boxId,
            dateSent = message.dateSent,
            date = message.date,
            body = message.body,
            subject = message.subject,
            isMms = message.isMms(),
            parts = message.parts.map { DuplicateSignature.Part(it.seq, it.type, it.name, it.text) }
        )
    )

    override fun categorizeUnclassifiedMessages(
        categorize: (address: String, body: String, threadId: Long) -> String,
        includeAlreadyCategorized: Boolean,
        onProgress: (processed: Int, total: Int) -> Unit
    ): Int {
        // Sent messages are left to inheritReplyCategories.
        val pending = (if (includeAlreadyCategorized) messageDao.all() else messageDao.uncategorized())
            .filter { message -> !message.isMe() }
        val total = pending.size
        var processed = 0
        pending.chunked(500).forEach { chunk ->
            // Categorizing reaches the contacts provider, so resolve the whole chunk before
            // opening the write transaction rather than holding the write lock across
            // cross-process queries. getText needs the parts of MMS messages.
            relations.messages(chunk)
            val categorized = chunk.map { message ->
                message to categorize(message.address, message.getText(), message.threadId)
            }
            setCategories(categorized)
            processed += chunk.size
            onProgress(processed, total)
        }
        return total
    }

    /** Writes each message's new category; only that column changes. */
    private fun setCategories(changes: List<Pair<Message, String?>>) {
        if (changes.isEmpty()) return
        database.runInTransaction { changes.forEach { (message, category) -> messageDao.setCategory(message.id, category) } }
    }

    override fun inheritReplyCategories(threadIds: Collection<Long>?): Int {
        val messages = if (threadIds == null) messageDao.allOldestFirst()
            else threadIds.toList().chunked(900).flatMap(messageDao::inThreadsOldestFirst).sortedBy { it.date }
        val updates = messages.groupBy { it.threadId }.values.flatMap { conversation ->
            ReplyCategories.updates(conversation, isSent = { it.isMe() }, category = { it.category })
        }
        setCategories(updates)
        return updates.size
    }

    override fun getThreadIdsWithSentMessages(threadIds: Collection<Long>?): Set<Long> =
        if (threadIds == null) messageDao.threadsWithSent().toHashSet()
        else threadIds.toList().chunked(900).flatMapTo(HashSet(), messageDao::threadsWithSent)

    override fun recategorizeLatestReceived(
        threadIds: Collection<Long>,
        categorize: (address: String, body: String, threadId: Long) -> String
    ) {
        // Resolve outside the write transaction: categorizing can query the contacts provider.
        val changes = threadIds.mapNotNull { threadId ->
            messageDao.visibleNewestFirst(threadId)
                .firstOrNull { !it.isMe() }
                ?.let { latest -> relations.message(latest)!! }
                ?.let { latest -> latest to categorize(latest.address, latest.getText(), threadId) }
                ?.takeIf { (latest, category) -> latest.category != category }
        }
        setCategories(changes)
    }

    override fun tagOtpMessages(isOtp: (String) -> Boolean): Int {
        // Codes are only ever received, so don't tag anything the user sent.
        val matches = relations.messages(messageDao.notOtp().filter { !it.isMe() })
            .filter { isOtp(it.getText()) }
        if (matches.isNotEmpty()) {
            database.runInTransaction { matches.map { it.id }.chunked(900).forEach(messageDao::markOtp) }
        }
        return matches.size
    }

    override fun updateReceivedSorting(messageId: Long, category: String?, isOtp: Boolean) {
        editMessage(messageId) {
            category?.let { this.category = it }
            if (isOtp) this.isOtp = true
        }
    }

    override fun setStarred(messageId: Long, starred: Boolean) {
        editMessage(messageId) { isStarred = starred }
    }

    /** Applies [change] to message [messageId], if it still exists, in one write; returns the changed message. */
    private fun editMessage(messageId: Long, change: Message.() -> Unit): Message? =
        database.runInTransaction<Message?> {
            messageDao.get(messageId)?.also { message ->
                message.change()
                messageDao.update(message)
            }
        }

    override fun getStarredMessages(): Flow<List<Message>> =
        observeMessages { messageDao.starred() }

    override fun getStarredCount(): Long = messageDao.starredCount()
}
