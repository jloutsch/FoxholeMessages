package dev.octoshrimpy.quik.common.base

import kotlinx.coroutines.CoroutineScope

/**
 * A screen on Flow (#34). [bindScope] is where its presenter or view model collects: an
 * activity's lifecycleScope (until destroy), or a controller's attach scope (attach to detach).
 */
interface QkFlowView<in State> {

    fun render(state: State)

    val bindScope: CoroutineScope

}
