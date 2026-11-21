package dev.octoshrimpy.quik.common.util.extensions

import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.rx2.asObservable

/**
 * A database Flow as an Rx stream that delivers on the main thread. Room's live lists send updates
 * from a background thread; Realm sent them on the main thread, and screens rely on that (2.8.0–2.9.1
 * crashed in group threads when the conversation theme tinted toolbar icons off the main thread).
 * MainThreadDatabaseUpdatesTest makes screens use this rather than asObservable().
 */
fun <T : Any> Flow<T>.asMainObservable(): Observable<T> = asObservable().observeOn(AndroidSchedulers.mainThread())
