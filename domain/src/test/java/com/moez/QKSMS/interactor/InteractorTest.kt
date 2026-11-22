package dev.octoshrimpy.quik.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * execute()'s callbacks are what receivers and jobs rely on (#34): onComplete only on success,
 * onTerminate always (a receiver's goAsync finish() lives there), both on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InteractorTest {

    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "main-test") }
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "io-test") }
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val terminated = CountDownLatch(1)

    @Before fun setUp() = Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        mainExecutor.shutdown()
        ioExecutor.shutdown()
    }

    private fun <P> Interactor<P>.start(params: P) {
        ioDispatcher = ioExecutor.asCoroutineDispatcher()
        execute(params,
            onComplete = { events += "complete@${threadName()}" },
            onTerminate = { events += "terminate@${threadName()}"; terminated.countDown() })
    }

    // Coroutine debug mode appends " @coroutine#N" to thread names in tests
    private fun threadName() = Thread.currentThread().name.substringBefore(" @")

    private fun awaitTerminate() = assertTrue("onTerminate never ran", terminated.await(5, TimeUnit.SECONDS))

    @Test
    fun successRunsOnCompleteThenOnTerminate() {
        object : Interactor<Unit>() { override suspend fun perform(params: Unit) = Unit }.start(Unit)
        awaitTerminate()
        assertEquals(listOf("complete@main-test", "terminate@main-test"), events)
    }

    @Test
    fun failureSkipsOnCompleteStillTerminatesAndDoesNotThrow() {
        object : Interactor<Unit>() { override suspend fun perform(params: Unit): Any = throw IllegalStateException("boom") }.start(Unit)
        awaitTerminate()
        assertEquals(listOf("terminate@main-test"), events)
    }

    @Test
    fun disposeCancelsAndStillTerminates() {
        val started = CountDownLatch(1)
        val interactor = object : Interactor<Unit>() {
            override suspend fun perform(params: Unit): Any { started.countDown(); awaitCancellation() }
        }
        interactor.start(Unit)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        interactor.dispose()
        awaitTerminate()
        assertEquals(listOf("terminate@main-test"), events)
    }

    @Test
    fun workRunsOnTheIoDispatcher() {
        var workThread = ""
        object : Interactor<Unit>() {
            override suspend fun perform(params: Unit) { workThread = threadName() }
        }.start(Unit)
        awaitTerminate()
        assertEquals("io-test", workThread)
    }
}
