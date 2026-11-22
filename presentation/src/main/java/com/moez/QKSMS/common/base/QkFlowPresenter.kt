package dev.octoshrimpy.quik.common.base

import androidx.annotation.CallSuper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Flow version of [QkPresenter] for Conductor screens (#34). */
abstract class QkFlowPresenter<in V : QkFlowView<S>, S : Any>(initialState: S) {

    /** Lives until [onCleared], for work started before a view binds (what `disposables` held). */
    protected val presenterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screenState = ScreenState(initialState, presenterScope)

    protected val state: StateFlow<S> = screenState.state

    protected fun newState(reducer: S.() -> S) = screenState.newState(reducer)

    @CallSuper
    open fun bindIntents(view: V) {
        view.bindScope.launch { state.collect(view::render) }
    }

    open fun onCleared() = presenterScope.cancel()

}
