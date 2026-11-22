package dev.octoshrimpy.quik.common.base

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import dev.octoshrimpy.quik.common.util.extensions.clicks
import dev.octoshrimpy.quik.common.util.extensions.collectIn
import dev.octoshrimpy.quik.common.util.extensions.events
import dev.octoshrimpy.quik.common.util.extensions.textChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The pieces every screen moves onto in #34: state, lifetimes, and view events as Flows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlowScreenKitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class CounterViewModel : QkFlowViewModel<CounterView, Int>(0) {
        fun change(reducer: Int.() -> Int) = newState(reducer)
    }

    private class CounterView(override val bindScope: CoroutineScope) : QkFlowView<Int> {
        val rendered = mutableListOf<Int>()
        override fun render(state: Int) { rendered += state }
    }

    @Test
    fun reducersApplyInCallOrder() {
        val viewModel = CounterViewModel()
        val view = CounterView(scope)
        viewModel.bindView(view)
        Thread {
            viewModel.change { this + 1 }
            viewModel.change { this * 10 }
            viewModel.change { this + 3 }
        }.apply { start(); join() }
        idle()
        assertEquals(13, view.rendered.last())
    }

    @Test
    fun renderStopsWhenBindScopeIsCancelled() {
        val viewModel = CounterViewModel()
        val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val view = CounterView(viewScope)
        viewModel.bindView(view)
        idle()
        viewScope.cancel()
        viewModel.change { this + 5 }
        idle()
        assertEquals(listOf(0), view.rendered)
    }

    @Test
    fun controllerScopeEndsAtDetach() {
        val attach = AttachScope()
        val taps = events<Unit>() // unlike a view's single click listener, a flow can have two collectors
        var handled = 0

        attach.start()
        val first = taps.collectIn(attach.scope) { handled++ }
        idle()
        attach.stop()
        attach.start() // back from a child screen: bindIntents runs again
        taps.collectIn(attach.scope) { handled++ }
        idle()

        taps.tryEmit(Unit)
        idle()
        assertTrue(first.isCancelled)
        assertEquals("a tap after re-attaching must act once", 1, handled)
    }

    @Test
    fun clicksEmitAndUnregister() {
        val button = View(context)
        var clicks = 0
        val job = button.clicks().collectIn(scope) { clicks++ }
        idle()
        button.performClick()
        button.performClick()
        idle()
        assertEquals(2, clicks)
        job.cancel()
        idle()
        assertFalse(button.hasOnClickListeners())
    }

    @Test
    fun textChangesStartsWithCurrentText() {
        val field = EditText(context).apply { setText("a") }
        val seen = mutableListOf<String>()
        field.textChanges().collectIn(scope) { seen += it.toString() }
        idle()
        field.setText("ab")
        idle()
        assertEquals(listOf("a", "ab"), seen)
    }

    @Test
    fun eventsNeverDropFromMain() {
        val taps = events<Int>()
        taps.collectIn(scope) { awaitCancellation() } // a collector stuck on its first item
        idle()
        assertTrue((1..100).all { taps.tryEmit(it) })
    }
}
