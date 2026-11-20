package dev.octoshrimpy.quik.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inbox preview leads a reaction with its emoji. Since screens get unmanaged copies (Room
 * migration Phase 1), the emoji has to travel with the copy instead of being queried from Realm.
 */
class ConversationSnippetTest {

    private fun reaction() = Message().apply {
        type = "sms"
        body = "Loved “On my way!”"
        isEmojiReaction = true
    }

    @Test
    fun copiedConversationKeepsTheReactionEmoji() {
        val conversation = Conversation().apply { lastMessage = reaction(); lastReactionEmoji = "❤️" }
        assertEquals("❤️ Loved “On my way!”", conversation.snippet)
    }

    @Test
    fun emojiAlreadyInTheTextIsNotRepeated() {
        val message = reaction().apply { body = "Reacted 🎉 to “hi”" }
        val conversation = Conversation().apply { lastMessage = message; lastReactionEmoji = "🎉" }
        assertEquals("Reacted 🎉 to “hi”", conversation.snippet)
    }

    @Test
    fun plainMessageHasNoEmoji() {
        val message = Message().apply { type = "sms"; body = "On my way!" }
        assertEquals("On my way!", Conversation().apply { lastMessage = message }.snippet)
    }
}
