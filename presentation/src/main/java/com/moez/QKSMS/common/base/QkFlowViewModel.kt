package dev.octoshrimpy.quik.common.base

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
        view.bindScope.launch { state.collect(view::render) }
    }

}
