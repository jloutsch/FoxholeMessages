/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.interactor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A background action. Subclasses implement [perform] (#34); [execute] runs it for callers that
 * want callbacks rather than a coroutine.
 */
abstract class Interactor<in Params> {

    /** Where [execute] runs [perform]; replaced in tests. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val scope = CoroutineScope(SupervisorJob())

    /** Does the work and returns its result, if callers use one. */
    abstract suspend fun perform(params: Params): Any?

    /**
     * [onComplete] runs only on success. [onTerminate] runs once the work ends either way, so a
     * receiver's finish() or a job's jobFinished() belongs there: if it were in [onComplete], a
     * failure would leave the broadcast or job open until Android times it out. Both run on the
     * main thread; errors are logged, never thrown to the caller.
     */
    fun execute(params: Params, onComplete: () -> Unit = {}, onTerminate: () -> Unit = {}) {
        // ATOMIC: even when already disposed, the body starts, so onTerminate still runs
        scope.launch(ioDispatcher, start = CoroutineStart.ATOMIC) {
            try {
                ensureActive()
                perform(params)
                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { onTerminate() }
            }
        }
    }

    /** Cancels running work; [execute] calls made afterwards only call their onTerminate. */
    fun dispose() = scope.cancel()

    fun isDisposed(): Boolean = !scope.isActive

}
