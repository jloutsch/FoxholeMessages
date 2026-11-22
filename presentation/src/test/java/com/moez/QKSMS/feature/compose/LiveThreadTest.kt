package dev.octoshrimpy.quik.feature.compose

import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveThreadTest {

    private val conversations = MutableSharedFlow<Conversation>(replay = 1)
    private val messageLists = mutableMapOf<Long, MutableSharedFlow<List<Message>>>()
    private val values = mutableListOf<Pair<Conversation, List<Message>>>()

    // Unconfined: every emission reaches the collector before tryEmit returns
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    init {
        liveThread(conversations) { id -> messageLists.getOrPut(id) { MutableSharedFlow(replay = 1) } }
            .onEach { values += it }
            .launchIn(scope)
    }

    @After fun tearDown() = scope.cancel()

    private fun thread(id: Long, name: String) = Conversation(id = id, name = name)
    private fun messages(threadId: Long) = listOf(Message().apply { this.threadId = threadId; id = threadId * 10 })

    @Test
    fun aRenamedConversationReachesTheOpenThread() {
        // #81: a contact renamed while their thread is open; the list kept the old conversation
        conversations.tryEmit(thread(1, "Old name"))
        val list = messages(1)
        messageLists.getValue(1).tryEmit(list)
        conversations.tryEmit(thread(1, "New name"))

        val latest = values.last()
        assertEquals("New name", latest.first.name)
        assertSame("the same messages, not reloaded", list, latest.second)
    }

    @Test
    fun aNewThreadIsNeverPairedWithThePreviousThreadsMessages() {
        conversations.tryEmit(thread(1, "A"))
        messageLists.getValue(1).tryEmit(messages(1))
        conversations.tryEmit(thread(2, "B"))
        messageLists.getValue(2).tryEmit(messages(2))

        values.forEach { (conversation, list) ->
            assertEquals(conversation.id, list.first().threadId)
        }
        assertEquals(2L, values.last().first.id)
    }
}
