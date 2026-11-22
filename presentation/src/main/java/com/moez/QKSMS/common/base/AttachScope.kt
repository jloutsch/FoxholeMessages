package dev.octoshrimpy.quik.common.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A scope that lives from [start] to [stop]. Conductor controllers bind in onAttach, and their
 * subscriptions used to end at detach, with binding running again on each re-attach; a fresh scope
 * per attach keeps that, so handlers don't stack up when the user comes back from a child screen.
 */
class AttachScope {

    private var current: CoroutineScope? = null

    val scope: CoroutineScope
        get() = checkNotNull(current) { "Only available between attach and detach" }

    fun start() {
        current?.cancel()
        // Main.immediate: collectors subscribe synchronously when bindIntents launches them, as
        // RxJava's subscribe did. With plain Main they started a frame later, and an event emitted
        // in between (a picked file arriving as the screen re-attaches) was lost.
        current = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    fun stop() {
        current?.cancel()
        current = null
    }

}
