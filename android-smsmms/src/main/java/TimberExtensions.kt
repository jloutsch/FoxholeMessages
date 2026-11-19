package com.klinker.android.timberworkarounds

@Suppress("UNUSED_PARAMETER") // same shape as Log.isLoggable, which the callers were written against
fun Timber_isLoggable(tag: String, level: Int): Boolean = true

