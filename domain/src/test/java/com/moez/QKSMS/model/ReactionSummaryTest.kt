package dev.octoshrimpy.quik.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Notifications lead a reaction with its emoji, the same way the inbox preview does. */
class ReactionSummaryTest {

    private fun reaction(text: String) = Message().apply { type = "sms"; body = text; isEmojiReaction = true }

    @Test
    fun reactionToAPictureLeadsWithItsEmoji() {
        assertEquals("❤️ Loved an image", reaction("Loved an image").summaryWithReaction("❤️"))
    }

    @Test
    fun emojiAlreadyInTheTextIsNotRepeated() {
        assertEquals("Reacted 🎉 to “hi”", reaction("Reacted 🎉 to “hi”").summaryWithReaction("🎉"))
    }

    @Test
    fun withoutAnEmojiTheTextIsUnchanged() {
        assertEquals("Loved an image", reaction("Loved an image").summaryWithReaction(null))
    }
}
