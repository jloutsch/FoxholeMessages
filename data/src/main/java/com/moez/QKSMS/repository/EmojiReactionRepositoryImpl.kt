/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

import javax.inject.Singleton
import android.content.Context
import com.squareup.moshi.Moshi
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.util.EmojiPatternStrings
import dev.octoshrimpy.quik.db.FoxholeDatabase
import dev.octoshrimpy.quik.db.Relations
import io.reactivex.Completable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

@Singleton
class EmojiReactionRepositoryImpl @Inject constructor(
    private val context: Context,
    private val keyManager: KeyManager,
    private val moshi: Moshi,
    private val database: FoxholeDatabase,
    private val relations: Relations,
) : EmojiReactionRepository {
    // Pattern table and matching live in EmojiReactionPatterns (unit-tested against the real
    // per-language files); this class only feeds it the files from the app's assets.
    private val patterns = EmojiReactionPatterns()

    init {
        val assetEntries = loadEmojiPatternEntriesFromAssets()
        assetEntries.forEach { (localeTag, strings) ->
            try {
                patterns.addLocale(strings)
                Timber.d("Loaded emoji regex patterns for $localeTag from assets")
            } catch (e: Exception) {
                Timber.w(e, "Failed to load asset patterns for locale: $localeTag")
            }
        }
        // The pattern counts matter more than the locale list: a locale whose fields all came back
        // null still reports as loaded, so only the totals show whether anything usable landed.
        Timber.i(
            "Loaded emoji reaction patterns for locales: ${assetEntries.map { it.first }} - " +
                "${patterns.reactionCount} reaction, ${patterns.removalCount} removal patterns"
        )
    }

    private fun loadEmojiPatternEntriesFromAssets(): List<Pair<String, EmojiPatternStrings>> {
        val dir = "emojis"
        val files = context.assets.list(dir) ?: emptyArray()
        return files.filter { it.endsWith(".json", ignoreCase = true) }
            .mapNotNull { filename ->
                val localeTag = filename.removeSuffix(".json")
                try {
                    val json = context.assets.open("$dir/$filename")
                        .bufferedReader().use {
                            it.readText()
                        }
                    val data = EmojiReactionPatterns.parsePatternsJson(moshi, json)
                    localeTag to data
                } catch (e: Exception) {
                    Timber.w(e, "Failed parsing emoji patterns asset: $filename")
                    null
                }
            }
    }

    override fun parseEmojiReaction(body: String): ParsedEmojiReaction? {
        val result = patterns.parse(body)
        if (result != null) {
            Timber.d("${if (result.isRemoval) "Removal" else "Reaction"} found with ${result.emoji}")
            return result
        }

        // Previously silent, which made "nothing was recognised" and "this isn't a reaction"
        // indistinguishable in a log. Deliberately logs no message content - length and pattern
        // counts are enough to tell an unmatched reaction from an empty pattern set.
        Timber.d(
            "No reaction pattern matched (body length=${body.length}, " +
                "reaction patterns=${patterns.reactionCount}, removal patterns=${patterns.removalCount})"
        )
        return null
    }

    private fun parseTruncatedMessages(originalMessageText: String): Regex {
        val reactionText = originalMessageText.trim()

        val delimiter = "\u2026"
        val index = reactionText.lastIndexOf(delimiter)
        val regexPattern = if (index == -1) {
            Regex.escape(reactionText)
        } else {
            val before = reactionText.take(index)
            Regex.escape(before) + ".*"
        }
        return Regex("^$regexPattern$", RegexOption.DOT_MATCHES_ALL)
    }

    /**
     * Search for messages in the same thread with matching text content
     * We'll search recent messages first
     */
    fun findTargetMessage(reactionMessage: Message, reaction: ParsedEmojiReaction): Message? {
        if (reaction.targetsAttachment) return findAttachmentTarget(reactionMessage)

        val threadId = reactionMessage.threadId
        val originalMessageText = reaction.originalMessage
        val startTime = System.currentTimeMillis()
        val messages = relations.threadMessages(threadId, database.messageDao().inThreadNewestFirst(threadId))
        val endTime = System.currentTimeMillis()
        Timber.d("Found ${messages.size} messages as potential emoji targets in ${endTime - startTime}ms")

        val originalMessageRegex = parseTruncatedMessages(originalMessageText)
        val match = messages.find { message ->
            originalMessageRegex.matches(message.getText(false).trim())
        }
        if (match != null) {
            Timber.d("Found match for reaction target: message ID ${match.id}")
            return match
        }

        // Length only: the in-app log goes to public Downloads, so it must not contain message text.
        Timber.w("No target message found for reaction (quoted text length=${originalMessageText.length})")
        return null
    }

    /**
     * A reaction to a picture or video arrives as "Loved an image" - iOS names the kind of
     * attachment but never says *which* one, so there is nothing to match against the way text
     * reactions match on their quoted body. The closest available signal is the most recent
     * attachment in the thread at the time the reaction arrived, which is what the sender was
     * almost certainly looking at. Bounded by the reaction's own date so that re-parsing an old
     * reaction doesn't attach it to a picture sent later.
     */
    private fun findAttachmentTarget(reactionMessage: Message): Message? {
        val match = database.messageDao()
            .latestWithAttachment(reactionMessage.threadId, reactionMessage.id, reactionMessage.date)

        if (match == null) Timber.w("No attachment found in thread to attach reaction to")
        else Timber.d("Found attachment reaction target: message ID ${match.id}")

        return match
    }

    private fun removeEmojiReaction(reactionMessage: Message, reaction: ParsedEmojiReaction, targetMessage: Message?) {
        if (targetMessage == null) {
            Timber.w("Cannot remove emoji reaction '${reaction.emoji}': no target message found")
            return
        }

        val existingReaction = database.emojiReactionDao().forTargets(listOf(targetMessage.id)).find { candidate ->
            candidate.senderAddress == reactionMessage.address && candidate.emoji == reaction.emoji
        }

        if (existingReaction != null) {
            database.emojiReactionDao().delete(listOf(existingReaction.id))
            Timber.d("Removed emoji reaction: ${reaction.emoji} to message ${targetMessage.id}")
        } else {
            Timber.w("No existing emoji reaction found to remove: ${reaction.emoji} to message ${targetMessage.id}")
        }

        reactionMessage.isEmojiReaction = true
        database.messageDao().upsert(reactionMessage)
    }

    /** Saves (or removes) a parsed reaction. Writes [reactionMessage] too; call inside a transaction. */
    fun saveEmojiReaction(reactionMessage: Message, parsedReaction: ParsedEmojiReaction, targetMessage: Message?) {
        if (parsedReaction.isRemoval) {
            removeEmojiReaction(reactionMessage, parsedReaction, targetMessage)
            return
        }

        // Only kept when attached: a reaction row is only ever read for a message marked as one
        if (targetMessage != null) {
            val reaction = EmojiReaction().apply {
                id = keyManager.newId()
                reactionMessageId = reactionMessage.id
                senderAddress = reactionMessage.address
                emoji = parsedReaction.emoji
                originalMessageText = parsedReaction.originalMessage
                threadId = reactionMessage.threadId
                targetMessageId = targetMessage.id
            }

            // Overwrite any previous reaction from this sender for this target
            val priorFromSender = database.emojiReactionDao().forTargets(listOf(targetMessage.id))
                .filter { it.senderAddress == reaction.senderAddress }
            database.emojiReactionDao().delete(priorFromSender.map { it.id })
            database.emojiReactionDao().upsert(reaction)

            reactionMessage.isEmojiReaction = true
            database.messageDao().upsert(reactionMessage)

            Timber.i("Saved emoji reaction: ${reaction.emoji} to message ${targetMessage.id}")
        } else {
            Timber.w("No target message, cannot save emoji reaction: ${parsedReaction.emoji}")
        }
    }

    /** Clears every reaction and parses them all again from message text, in one transaction. */
    fun deleteAndReparseAllEmojiReactions(onProgress: (SyncRepository.SyncProgress) -> Unit) = database.runInTransaction {
        val startTime = System.currentTimeMillis()

        database.emojiReactionDao().clear()
        database.messageDao().clearEmojiReactionFlags()

        // parse oldest to newest to handle reactions & removals properly
        val allMessages = relations.messages(database.messageDao().reactionCandidates())

        val max = allMessages.size
        var progress = 0
        var reactionsParsed = 0
        var targetsFound = 0

        allMessages.forEach { message ->
            val text = message.getText(false)
            val parsedReaction = parseEmojiReaction(text)
            if (parsedReaction != null) {
                reactionsParsed++
                val targetMessage = findTargetMessage(message, parsedReaction)
                if (targetMessage != null) targetsFound++
                saveEmojiReaction(message, parsedReaction, targetMessage)
            }
            // Counts every message scanned, since max does; counting only reactions left the bar
            // near the start. Updated every 25 messages and at the end, so the UI isn't flooded.
            progress++
            if (progress % 25 == 0 || progress == max) {
                onProgress(
                    SyncRepository.SyncProgress.ParsingEmojis(
                        max = max,
                        progress = progress,
                        indeterminate = false
                    )
                )
            }
        }

        val endTime = System.currentTimeMillis()
        // The three counts are what distinguish the failure modes: nothing parsed means the
        // patterns aren't matching at all, parsed-but-no-targets means recognition works and the
        // target lookup is what's failing.
        Timber.i(
            "Reparsed emoji reactions in ${endTime - startTime}ms - scanned $max messages, " +
                "parsed $reactionsParsed reactions, found targets for $targetsFound of them"
        )
    }

    override fun reparseIfFlagged() {
        Completable.fromAction {
            if (database.emojiSyncNeededDao().count() > 0) {
                deleteAndReparseAllEmojiReactions { /* No progress UI here */ }
                database.emojiSyncNeededDao().clear()
            }
        }.subscribeOn(Schedulers.io()).subscribe({}, { error -> Timber.e(error, "Emoji reparse failed") })
    }

    override fun reparseAll(onDone: () -> Unit, onError: (Throwable) -> Unit) {
        Completable.fromAction { deleteAndReparseAllEmojiReactions { /* No progress UI here */ } }
            .subscribeOn(Schedulers.io())
            .subscribe({ onDone() }, { error -> onError(error) })
    }

}
