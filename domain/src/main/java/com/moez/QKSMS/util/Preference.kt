package dev.octoshrimpy.quik.util

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One stored setting. Replaces rx-preferences 2.0.0-RC3 (#34) and stores each type exactly as it
 * did (putBoolean/putInt/putString/putFloat/putLong/putStringSet, objects as converter strings),
 * so settings saved by earlier versions read back unchanged.
 */
class Preference<T : Any> internal constructor(
    private val prefs: SharedPreferences,
    val key: String,
    val defaultValue: T,
    private val adapter: Adapter<T>,
) {

    internal interface Adapter<T> {
        fun get(key: String, prefs: SharedPreferences): T
        fun set(key: String, value: T, editor: SharedPreferences.Editor)
    }

    interface Converter<T> {
        fun deserialize(serialized: String): T
        fun serialize(value: T): String
    }

    fun get(): T = if (prefs.contains(key)) adapter.get(key, prefs) else defaultValue

    fun set(value: T) = prefs.edit().also { adapter.set(key, value, it) }.apply()

    fun delete() = prefs.edit().remove(key).apply()

    val isSet: Boolean get() = prefs.contains(key)

    /** The current value, then the value after each change (the default after [delete]). */
    fun asFlow(): Flow<T> = prefs.keyChanges().filter { it == key }.map { get() }.onStart { emit(get()) }
}

/** Keys of settings as they change. Android calls the listener on the main thread. */
fun SharedPreferences.keyChanges(): Flow<String> = callbackFlow {
    // Held in a local so it isn't garbage collected: SharedPreferences keeps only a weak reference
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) trySend(key) }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}

/** Builds [Preference]s over the app's settings file; the replacement for RxSharedPreferences. */
@Singleton
class PreferenceStore @Inject constructor(private val prefs: SharedPreferences) {

    fun getBoolean(key: String, default: Boolean = false) = Preference(prefs, key, default, BooleanAdapter)
    fun getInteger(key: String, default: Int = 0) = Preference(prefs, key, default, IntAdapter)
    fun getString(key: String, default: String = "") = Preference(prefs, key, default, StringAdapter)
    fun getFloat(key: String, default: Float = 0f) = Preference(prefs, key, default, FloatAdapter)
    fun getLong(key: String, default: Long = 0L) = Preference(prefs, key, default, LongAdapter)
    fun getStringSet(key: String, default: Set<String> = emptySet()) = Preference(prefs, key, default, StringSetAdapter)
    fun <T : Any> getObject(key: String, default: T, converter: Preference.Converter<T>) =
        Preference(prefs, key, default, ConverterAdapter(converter))

    private object BooleanAdapter : Preference.Adapter<Boolean> {
        override fun get(key: String, prefs: SharedPreferences) = prefs.getBoolean(key, false)
        override fun set(key: String, value: Boolean, editor: SharedPreferences.Editor) { editor.putBoolean(key, value) }
    }

    private object IntAdapter : Preference.Adapter<Int> {
        override fun get(key: String, prefs: SharedPreferences) = prefs.getInt(key, 0)
        override fun set(key: String, value: Int, editor: SharedPreferences.Editor) { editor.putInt(key, value) }
    }

    private object StringAdapter : Preference.Adapter<String> {
        override fun get(key: String, prefs: SharedPreferences) = prefs.getString(key, null) ?: ""
        override fun set(key: String, value: String, editor: SharedPreferences.Editor) { editor.putString(key, value) }
    }

    private object FloatAdapter : Preference.Adapter<Float> {
        override fun get(key: String, prefs: SharedPreferences) = prefs.getFloat(key, 0f)
        override fun set(key: String, value: Float, editor: SharedPreferences.Editor) { editor.putFloat(key, value) }
    }

    private object LongAdapter : Preference.Adapter<Long> {
        override fun get(key: String, prefs: SharedPreferences) = prefs.getLong(key, 0L)
        override fun set(key: String, value: Long, editor: SharedPreferences.Editor) { editor.putLong(key, value) }
    }

    private object StringSetAdapter : Preference.Adapter<Set<String>> {
        // Copied: Android forbids changing the set it returns
        override fun get(key: String, prefs: SharedPreferences): Set<String> = prefs.getStringSet(key, null)?.toSet() ?: emptySet()
        override fun set(key: String, value: Set<String>, editor: SharedPreferences.Editor) { editor.putStringSet(key, value) }
    }

    private class ConverterAdapter<T : Any>(private val converter: Preference.Converter<T>) : Preference.Adapter<T> {
        override fun get(key: String, prefs: SharedPreferences): T = converter.deserialize(prefs.getString(key, null)!!)
        override fun set(key: String, value: T, editor: SharedPreferences.Editor) { editor.putString(key, converter.serialize(value)) }
    }
}
