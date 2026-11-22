package dev.octoshrimpy.quik.common.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A screen's state. Reducers queue from any thread and apply in call order on the main thread,
 * never inline, as the RxJava base classes did (they observed the reducers on the main thread).
 */
internal class ScreenState<S : Any>(initialState: S, scope: CoroutineScope) {

    private val reducers = Channel<S.() -> S>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(initialState)

    val state: StateFlow<S> = mutable.asStateFlow()

    init {
        scope.launch(Dispatchers.Main) {
            for (reducer in reducers) mutable.value = mutable.value.reducer()
        }
    }

    fun newState(reducer: S.() -> S) {
        reducers.trySend(reducer)
    }

}
