package dev.octoshrimpy.quik.feature.compose

import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveThreadTest {

    private val conversations = BehaviorSubject.create<Conversation>()
    private val messageLists = mutableMapOf<Long, PublishSubject<List<Message>>>()
    private val observer = liveThread(conversations) { id -> messageLists.getOrPut(id) { PublishSubject.create() } }.test()

    private fun thread(id: Long, name: String) = Conversation(id = id, name = name)
    private fun messages(threadId: Long) = listOf(Message().apply { this.threadId = threadId; id = threadId * 10 })

    @Test
    fun aRenamedConversationReachesTheOpenThread() {
        // #81: a contact renamed while their thread is open; the list kept the old conversation
        conversations.onNext(thread(1, "Old name"))
        val list = messages(1)
        messageLists.getValue(1).onNext(list)
        conversations.onNext(thread(1, "New name"))

        val latest = observer.values().last()
        assertEquals("New name", latest.first.name)
        assertSame("the same messages, not reloaded", list, latest.second)
    }

    @Test
    fun aNewThreadIsNeverPairedWithThePreviousThreadsMessages() {
        conversations.onNext(thread(1, "A"))
        messageLists.getValue(1).onNext(messages(1))
        conversations.onNext(thread(2, "B"))
        messageLists.getValue(2).onNext(messages(2))

        observer.values().forEach { (conversation, list) ->
            assertEquals(conversation.id, list.first().threadId)
        }
        assertEquals(2L, observer.values().last().first.id)
    }
}
