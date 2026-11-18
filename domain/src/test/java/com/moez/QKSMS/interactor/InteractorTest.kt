package dev.octoshrimpy.quik.interactor

import io.reactivex.Flowable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class InteractorTest {

    private class Work(private val fail: Boolean) : Interactor<Unit>() {
        override fun buildObservable(params: Unit): Flowable<*> =
            if (fail) Flowable.error<Unit>(RuntimeException("boom")) else Flowable.just(Unit)
    }

    @Test
    fun onTerminateRunsWhenTheWorkFails() {
        var completed = 0
        var terminated = 0
        Work(fail = true).execute(Unit, onComplete = { completed++ }, onTerminate = { terminated++ })
        assertEquals("onComplete is success-only", 0, completed)
        assertEquals("onTerminate must run so receivers and jobs get finished", 1, terminated)
    }

    @Test
    fun bothRunOnceOnSuccess() {
        var completed = 0
        var terminated = 0
        Work(fail = false).execute(Unit, onComplete = { completed++ }, onTerminate = { terminated++ })
        assertEquals(1, completed)
        assertEquals(1, terminated)
    }

    companion object {
        @BeforeClass @JvmStatic
        fun runSynchronously() {
            RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
            RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
            RxJavaPlugins.setErrorHandler { }
        }

        @AfterClass @JvmStatic
        fun reset() {
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
        }
    }
}
