package dev.octoshrimpy.quik.repository

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Emoji reactions arrive as plain SMS text and are recognised by per-language patterns shipped in
 * src/main/assets/emojis. In v2.3.1 every language file silently contributed zero patterns, so
 * iPhone reactions showed up as "Liked …" text for weeks. These tests load the real files.
 */
class EmojiReactionPatternsTest {

    private val assetDir = File("src/main/assets/emojis")
    private val moshi = Moshi.Builder().build()

    private val patterns = EmojiReactionPatterns().apply {
        assetDir.listFiles { f -> f.name.endsWith(".json") }!!.sorted().forEach { file ->
            addLocale(EmojiReactionPatterns.parsePatternsJson(moshi, file.readText()))
        }
    }

    private fun parse(body: String) = patterns.parse(body)

    // --- The outage: every language file must actually contribute patterns ---

    @Test
    fun everyTranslatedLanguageFileContributesPatterns() {
        val files = assetDir.listFiles { f -> f.name.endsWith(".json") }!!
        assertTrue("expected the shipped language files, found ${files.size}", files.size >= 12)
        val untranslated = mutableListOf<String>()
        files.forEach { file ->
            val entries = moshi.adapter(Map::class.java).fromJson(file.readText())!!
            // An empty file is an untranslated placeholder created by the translation service
            // (ckb.json is one); that's expected. A file WITH entries that adds no patterns is the
            // v2.3.1 outage, where the entries weren't being read.
            if (entries.isEmpty()) {
                untranslated += file.name
                return@forEach
            }
            val single = EmojiReactionPatterns()
            val before = single.reactionCount
            single.addLocale(EmojiReactionPatterns.parsePatternsJson(moshi, file.readText()))
            assertTrue("${file.name} has entries but added no reaction patterns", single.reactionCount > before)
            assertTrue("${file.name} has entries but added no removal patterns", single.removalCount > 1)
        }
        // These shipped as empty placeholders from upstream; any OTHER file being empty means a
        // translation was lost.
        // ckb stays empty on purpose: Central Kurdish isn't an iOS system language, so no iPhone
        // sends tapbacks in it.
        val knownPlaceholders = setOf("ckb.json")
        assertTrue(
            "unexpected empty emoji pattern files: ${untranslated - knownPlaceholders}",
            knownPlaceholders.containsAll(untranslated)
        )
    }

    @Test
    fun englishHasEveryPattern() {
        val entries = moshi.adapter(Map::class.java).fromJson(File(assetDir, "en.json").readText())!!
        assertEquals(14, entries.size)
    }

    // --- iPhone tapbacks on text (English) ---

    @Test
    fun tapbacksOnText() {
        val expected = mapOf(
            "Loved" to "❤️", "Liked" to "👍", "Disliked" to "👎",
            "Laughed at" to "😂", "Emphasized" to "‼️", "Questioned" to "❓"
        )
        expected.forEach { (verb, emoji) ->
            assertEquals(verb, ParsedEmojiReaction(emoji, "see you soon"), parse("$verb “see you soon”"))
        }
    }

    @Test
    fun quotedMessageCanSpanLines() =
        assertEquals(ParsedEmojiReaction("❤️", "line one\nline two"), parse("Loved “line one\nline two”"))

    @Test
    fun genericEmojiReaction() =
        assertEquals(ParsedEmojiReaction("🎉", "we won"), parse("Reacted 🎉 to “we won”"))

    // --- Removals are recognised as removals, with the right emoji ---

    @Test
    fun tapbackRemoval() =
        assertEquals(ParsedEmojiReaction("❤️", "see you soon", isRemoval = true), parse("Removed a heart from “see you soon”"))

    @Test
    fun genericRemoval() =
        assertEquals(ParsedEmojiReaction("🎉", "we won", isRemoval = true), parse("Removed 🎉 from “we won”"))

    // --- Reactions to pictures and other attachments (nothing quoted) ---

    @Test
    fun reactionsToAttachments() {
        assertEquals(ParsedEmojiReaction("❤️", "", targetsAttachment = true), parse("Loved an image"))
        assertEquals(ParsedEmojiReaction("👍", "", targetsAttachment = true), parse("Liked a video"))
        assertEquals(ParsedEmojiReaction("😂", "", targetsAttachment = true), parse("Laughed at a sticker"))
        assertEquals(ParsedEmojiReaction("❤️", "", isRemoval = true, targetsAttachment = true), parse("Removed a heart from an image"))
    }

    // --- Another language, where the quote comes first ---

    @Test
    fun germanHeart() =
        assertEquals(ParsedEmojiReaction("❤️", "bis bald"), parse("„bis bald“ ein Herz hinzugefügt"))

    // --- Google Messages' marker-wrapped format ---

    @Test
    fun googleMessagesReactionAndRemoval() {
        val hair = " "
        assertEquals(
            ParsedEmojiReaction("👍", "Hello there"),
            parse("${hair}Reacted ​👍​ to ${hair}Hello there${hair}$hair")
        )
        assertEquals(
            ParsedEmojiReaction("👍", "Hello there", isRemoval = true),
            parse("${hair}Removed ‌👍‌ from ${hair}Hello there${hair}$hair")
        )
    }

    // --- Real messages must never be taken for reactions (a match hides the message) ---

    @Test
    fun ordinaryTextIsNotAReaction() {
        listOf(
            "Loved that movie!",
            "I loved an image of that",
            "Liked it a lot",
            "Loved “unterminated quote",
            "Loved \"straight quotes\"",
            "Reacted badly to the news",
            "hello",
            ""
        ).forEach { body -> assertNull("taken for a reaction: $body", parse(body)) }
    }

    // --- Bulgarian and Malayalam (Apple's iOS wording, IMSharedUtilities.loctable, iOS 27) ---

    @Test
    fun bulgarianTapbacks() {
        val bg = EmojiReactionPatterns().apply { addLocale(EmojiReactionPatterns.parsePatternsJson(moshi, File(assetDir, "bg.json").readText())) }
        mapOf(
            "Реагира със сърце на „Здравей“" to "❤️", "Хареса „Здравей“" to "👍", "Не хареса „Здравей“" to "👎",
            "Реагира със смях на „Здравей“" to "😂", "Реагира с удивителна на „Здравей“" to "‼️",
            "Реагира с въпросителна на „Здравей“" to "❓", "Реакция: 🎉 на „Здравей“" to "🎉"
        ).forEach { (body, emoji) ->
            val r = bg.parse(body)
            assertEquals(body, emoji, r?.emoji)
            assertEquals(body, "Здравей", r?.originalMessage)
            assertEquals(body, false, r?.isRemoval)
        }
        val removed = bg.parse("Премахна сърце от „Здравей“")
        assertEquals("❤️", removed?.emoji)
        assertEquals(true, removed?.isRemoval)
        // ordinary Bulgarian text must not be hidden as a reaction
        assertNull(bg.parse("Хареса ми филмът"))
        assertNull(bg.parse("Хареса „Здравей“ и още нещо"))
    }

    @Test
    fun malayalamTapbacks() {
        val ml = EmojiReactionPatterns().apply { addLocale(EmojiReactionPatterns.parsePatternsJson(moshi, File(assetDir, "ml.json").readText())) }
        val heart = ml.parse("‘നമസ്കാരം’ എന്നതിൽ സ്നേഹം പ്രകടിപ്പിച്ചു")
        assertEquals("❤️", heart?.emoji)
        assertEquals("നമസ്കാരം", heart?.originalMessage)
        assertEquals("👎", ml.parse("‘നമസ്കാരം’ എന്നതിനെ ഡിസ്\u200Cലൈക്ക് ചെയ്തു")?.emoji)
        val removed = ml.parse("‘നമസ്കാരം’ എന്നതിൽ നിന്ന് ലൈക്ക് നീക്കം ചെയ്തു")
        assertEquals("👍", removed?.emoji)
        assertEquals(true, removed?.isRemoval)
        assertNull(ml.parse("നമസ്കാരം"))
    }

    @Test
    fun blankPatternsAreIgnored() {
        // Untranslated entries arrive as "" from translation services; Regex("") would match everything.
        val p = EmojiReactionPatterns().apply {
            addLocale(EmojiReactionPatterns.parsePatternsJson(moshi, """{"emoji_reaction_ios_heart_added": "", "emoji_reaction_ios_generic_added": " "}"""))
        }
        assertNull(p.parse("just a normal message"))
    }

    // --- German: iOS 27 reworded every "added" tapback and iOS 26 the generic removal; older
    // iPhones keep sending the old text, so both must match (Apple's IMSharedUtilities.loctable) ---

    private val german by lazy {
        EmojiReactionPatterns().apply { addLocale(EmojiReactionPatterns.parsePatternsJson(moshi, File(assetDir, "de.json").readText())) }
    }

    private fun assertReaction(body: String, emoji: String, message: String, removal: Boolean = false) {
        val r = german.parse(body)
        assertEquals(body, emoji, r?.emoji)
        assertEquals(body, message, r?.originalMessage)
        assertEquals(body, removal, r?.isRemoval)
        assertEquals(body, message.isEmpty(), r?.targetsAttachment)
    }

    @Test
    fun germanTapbacksFromIos17To26() {
        assertReaction("„Hallo“ ein Herz hinzugefügt", "❤️", "Hallo")
        assertReaction("Gefällt: „Hallo“", "👍", "Hallo")
        assertReaction("Gefällt nicht: „Hallo“", "👎", "Hallo")
        assertReaction("„Hallo“ ein Lachen hinzugefügt", "😂", "Hallo")
        assertReaction("„Hallo“ ein Ausrufezeichen hinzugefügt", "‼️", "Hallo")
        assertReaction("„Hallo“ ein Fragezeichen hinzugefügt", "❓", "Hallo")
        assertReaction("Hat mit 🎉 auf „Hallo“ reagiert", "🎉", "Hallo")
        assertReaction("🎉 aus „Hallo“ entfernt", "🎉", "Hallo", removal = true)   // iOS 18
    }

    @Test
    fun germanTapbacksFromIos27() {
        assertReaction("Hat auf „Hallo“ mit einem Herz reagiert", "❤️", "Hallo")
        assertReaction("Jemandem gefällt „Hallo“", "👍", "Hallo")
        assertReaction("Jemandem gefällt „Hallo“ nicht", "👎", "Hallo")
        assertReaction("Jemand hat auf „Hallo“ mit einem Lachen reagiert", "😂", "Hallo")
        assertReaction("Hat „Hallo“ mit einem Ausrufezeichen kommentiert", "‼️", "Hallo")
        assertReaction("Jemand hat auf „Hallo“ mit Fragezeichen reagiert", "❓", "Hallo")
        assertReaction("Hat 🎉 von „Hallo“ entfernt", "🎉", "Hallo", removal = true)   // iOS 26+
    }

    @Test
    fun germanRemovalsUnchangedAcrossVersions() {
        assertReaction("Herz von „Hallo“ entfernt", "❤️", "Hallo", removal = true)
        assertReaction("„Gefällt mir“ von „Hallo“ entfernt", "👍", "Hallo", removal = true)
        assertReaction("„Gefällt mir nicht“ von „Hallo“ entfernt", "👎", "Hallo", removal = true)
        assertReaction("Fragezeichen von „Hallo“ entfernt", "❓", "Hallo", removal = true)
    }

    @Test
    fun germanPictureReactions() {
        assertReaction("Einem Bild ein Herz hinzugefügt", "❤️", "")
        assertReaction("Gefällt: Bild", "👍", "")
        assertReaction("Hat auf ein Bild mit einem Herz reagiert", "❤️", "")
        assertReaction("Hat auf ein Audionachricht mit einem Herz reagiert", "❤️", "")  // Apple's typo, verbatim
        assertReaction("Jemandem gefällt ein Film", "👍", "")
        assertReaction("Lachen von einer Audionachricht entfernt", "😂", "", removal = true)
        assertReaction("Hat mit 🎉 auf ein Bild reagiert.", "🎉", "")
        assertReaction("Hat 🎉 von einem Film entfernt.", "🎉", "", removal = true)
    }

    @Test
    fun ordinaryGermanTextIsNotAReaction() {
        // A false match hides a real message, so near-misses must stay plain text.
        listOf(
            "Gefällt mir sehr gut", "Jemandem gefällt das bestimmt", "Hat auf dich gewartet",
            "Gefällt: dein neues Auto", "„Hallo“ ein Herz hinzugefügt und mehr", "Jemand hat auf mich reagiert"
        ).forEach { assertNull(it, german.parse(it)) }
    }
}
