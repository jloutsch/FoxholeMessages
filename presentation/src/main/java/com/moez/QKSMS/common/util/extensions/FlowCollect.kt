package dev.octoshrimpy.quik.common.util.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * For events a view emits itself (what PublishSubject was). No replay; tryEmit always succeeds,
 * even while a collector is busy, so a tap is never dropped.
 */
fun <T> events(): MutableSharedFlow<T> = MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)

/** Collects in [scope] (a view's bindScope); replaces autoDisposable(...).subscribe { }. */
fun <T> Flow<T>.collectIn(scope: CoroutineScope, action: suspend (T) -> Unit): Job =
    scope.launch { collect { action(it) } }
