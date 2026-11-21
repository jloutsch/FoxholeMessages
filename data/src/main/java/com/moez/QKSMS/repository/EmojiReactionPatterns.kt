/*
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

import java.util.regex.Matcher
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dev.octoshrimpy.quik.util.EmojiPatternStrings

/**
 * The text patterns that recognise emoji reactions sent as plain SMS (iPhone tapbacks such as
 * `Loved “see you soon”`, and Google Messages' marker-wrapped form), and the matching itself.
 *
 * Kept free of Android so it can be unit-tested against the real per-language pattern files;
 * EmojiReactionRepositoryImpl reads those files from the app's assets and adds them here.
 */
internal class EmojiReactionPatterns {

    // Ordered maps: iPhone tapbacks must be tried before the generic "Reacted X to" patterns,
    // since the regexes can overlap.
    private val reactionPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf(
        Regex( // Google Messages
            "(?s)^ [^​ ]*​([^​]*)​[^​ ]* (.*) [^​ ]* \\Z"
        ) to { match ->
            ParsedEmojiReaction(
                match.groupValues[1], match.groupValues[2]
            )
        }
    )
    private val removalPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf(
        Regex( // Google Messages
            "(?s)^ [^‌ ]*‌([^‌]*)‌[^‌ ]* (.*) [^‌ ]* \\Z"
        ) to { match ->
            ParsedEmojiReaction(
                match.groupValues[1], match.groupValues[2], isRemoval = true
            )
        }
    )

    val reactionCount: Int get() = reactionPatterns.size
    val removalCount: Int get() = removalPatterns.size

    fun addLocale(strings: EmojiPatternStrings) {
        // iOS tapbacks (important to add these before generic emoji patterns as the regexes may overlap)
        listOf(
            Triple("❤️", strings.iosHeartAdded, strings.iosHeartRemoved),
            Triple("👍", strings.iosLikeAdded, strings.iosLikeRemoved),
            Triple("👎", strings.iosDislikeAdded, strings.iosDislikeRemoved),
            Triple("😂", strings.iosLaughAdded, strings.iosLaughRemoved),
            Triple("‼️", strings.iosExclamationAdded, strings.iosExclamationRemoved),
            Triple("❓", strings.iosQuestionMarkAdded, strings.iosQuestionMarkRemoved)
        ).forEach { (emoji, added, removed) ->
            added?.let {
                reactionPatterns[Regex(it)] = { match ->
                    val original = matchedGroups(match).firstOrNull().orEmpty()
                    ParsedEmojiReaction(emoji, original, targetsAttachment = original.isEmpty())
                }
            }
            removed?.let {
                removalPatterns[Regex(it)] = { match ->
                    val original = matchedGroups(match).firstOrNull().orEmpty()
                    ParsedEmojiReaction(
                        emoji, original, isRemoval = true, targetsAttachment = original.isEmpty()
                    )
                }
            }
        }

        // Generic iOS emoji patterns
        strings.iosGenericAdded?.let { pattern ->
            reactionPatterns[Regex(pattern)] = { match ->
                val groups = matchedGroups(match)
                if (groups.firstOrNull() == "with a sticker") null // TODO: localize "with a sticker"
                else {
                    val original = groups.getOrNull(1).orEmpty()
                    ParsedEmojiReaction(
                        groups.first(), original, targetsAttachment = original.isEmpty()
                    )
                }
            }
        }
        strings.iosGenericRemoved?.let { pattern ->
            removalPatterns[Regex(pattern)] = { match ->
                val groups = matchedGroups(match)
                val original = groups.getOrNull(1).orEmpty()
                ParsedEmojiReaction(
                    groups.first(), original, isRemoval = true,
                    targetsAttachment = original.isEmpty()
                )
            }
        }
    }

    /**
     * The values of the capture groups that took part in the match, in order. A pattern can list
     * several wordings as alternatives (iOS has reworded tapbacks between versions, and older
     * iPhones keep sending the old text), each with its own groups; only the alternative that
     * matched contributes. Specific patterns yield [message]; generic ones [emoji, message]. A
     * picture reaction ("Loved an image") captures no message, which marks it as an attachment.
     */
    private fun matchedGroups(match: MatchResult): List<String> =
        match.groups.drop(1).mapNotNull { it?.value }

    // One reused matcher per pattern for the yes/no check. A new Regex.find() per pattern per
    // message cost about 1 ms a message on a phone (~140 patterns; Android's regex is native ICU):
    // recognising a full sync's 13,101 messages took 13.5 s on the Note 10, 4.3 s with reuse (#72).
    // A matcher isn't thread-safe and receiving can run alongside a sync, hence @Synchronized.
    private val matchers = HashMap<Regex, Matcher>()

    /** The reaction [body] expresses, a removal first if it's one, or null if it isn't a reaction. */
    @Synchronized
    fun parse(body: String): ParsedEmojiReaction? =
        firstMatch(removalPatterns, body) ?: firstMatch(reactionPatterns, body)

    private fun firstMatch(
        patterns: Map<Regex, (MatchResult) -> ParsedEmojiReaction?>,
        body: String
    ): ParsedEmojiReaction? {
        for ((pattern, parser) in patterns) {
            val matcher = matchers.getOrPut(pattern) { pattern.toPattern().matcher("") }
            if (!matcher.reset(body).find()) continue
            // A hit is rare (a reaction), so the full Kotlin match is only built then
            val match = pattern.find(body) ?: continue
            return parser(match) ?: continue
        }
        return null
    }

    companion object {
        /**
         * Read a per-language pattern file as a plain string map keyed by the literal JSON names,
         * rather than by handing the class to Moshi and letting it reflect over the property names.
         *
         * The reflective route failed silently in release builds: every property on
         * [EmojiPatternStrings] is nullable and every use of it is null-safe, so unresolved names
         * produced a fully-null object, and all twelve locales reported loading successfully while
         * contributing no patterns whatsoever - leaving only the one hardcoded Google Messages
         * pattern and no way for an iPhone tapback to ever match. String literals can't be renamed,
         * so this cannot fail that way again regardless of what R8 does to the class.
         */
        fun parsePatternsJson(moshi: Moshi, json: String): EmojiPatternStrings {
            val mapType = Types.newParameterizedType(
                Map::class.java, String::class.java, String::class.java
            )
            // Blank values are dropped: translation services write "" for untranslated entries,
            // and Regex("") matches every message, after which reading its capture group throws.
            val values = requireNotNull(
                moshi.adapter<Map<String, String>>(mapType).fromJson(json)
            ) { "Invalid emoji patterns JSON" }.filterValues { it.isNotBlank() }

            return EmojiPatternStrings(
                iosGenericAdded = values["emoji_reaction_ios_generic_added"],
                iosGenericRemoved = values["emoji_reaction_ios_generic_removed"],
                iosHeartAdded = values["emoji_reaction_ios_heart_added"],
                iosHeartRemoved = values["emoji_reaction_ios_heart_removed"],
                iosLikeAdded = values["emoji_reaction_ios_like_added"],
                iosLikeRemoved = values["emoji_reaction_ios_like_removed"],
                iosDislikeAdded = values["emoji_reaction_ios_dislike_added"],
                iosDislikeRemoved = values["emoji_reaction_ios_dislike_removed"],
                iosLaughAdded = values["emoji_reaction_ios_laugh_added"],
                iosLaughRemoved = values["emoji_reaction_ios_laugh_removed"],
                iosExclamationAdded = values["emoji_reaction_ios_exclamation_added"],
                iosExclamationRemoved = values["emoji_reaction_ios_exclamation_removed"],
                iosQuestionMarkAdded = values["emoji_reaction_ios_question_mark_added"],
                iosQuestionMarkRemoved = values["emoji_reaction_ios_question_mark_removed"]
            )
        }
    }
}
