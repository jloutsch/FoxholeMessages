package dev.octoshrimpy.quik.extensions

import io.realm.Realm
import io.realm.RealmModel
import io.realm.RealmQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow

/**
 * The query's results as plain (unmanaged) lists, re-emitted whenever they change. Call on a
 * Looper thread (the main thread), like the findAllAsync() calls it replaces. The copies are safe
 * to pass to any thread, and nothing downstream holds a live Realm object.
 *
 * Phase 1 of the Room migration: screens stop holding RealmResults while Realm is still the store.
 */
fun <T : RealmModel> RealmQuery<T>.asLiveList(realm: Realm): Flow<List<T>> =
    findAllAsync()
        .asFlowable()
        .filter { results -> results.isLoaded && results.isValid }
        .map { results -> realm.copyFromRealm(results) as List<T> }
        .onBackpressureLatest()
        .asFlow()
