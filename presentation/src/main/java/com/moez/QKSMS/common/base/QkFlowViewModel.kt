package dev.octoshrimpy.quik.common.base

import kotlinx.coroutines.Dispatchers
import androidx.annotation.CallSuper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Flow version of [QkViewModel] for activity screens (#34). */
abstract class QkFlowViewModel<in V : QkFlowView<S>, S : Any>(initialState: S) : ViewModel() {

    private val screenState = ScreenState(initialState, viewModelScope)

    protected val state: StateFlow<S> = screenState.state

    protected fun newState(reducer: S.() -> S) = screenState.newState(reducer)

    @CallSuper
    open fun bindView(view: V) {
        // Dispatched, not immediate: renders run after the caller (onCreate/onAttach) returns, as
        // RxJava's observeOn(mainThread) did, so render never sees half-built views
        view.bindScope.launch(Dispatchers.Main) { state.collect(view::render) }
    }

}
