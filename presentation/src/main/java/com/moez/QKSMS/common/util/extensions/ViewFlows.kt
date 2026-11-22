package dev.octoshrimpy.quik.common.util.extensions

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// Flow replacements for the RxBinding calls screens use (#34). Each removes its listener when
// collection stops.

fun View.clicks(): Flow<Unit> = callbackFlow {
    setOnClickListener { trySend(Unit) }
    awaitClose { setOnClickListener(null) }
}

/** Consumes the long press, as RxBinding's longClicks() did. */
fun View.longClicks(): Flow<Unit> = callbackFlow {
    setOnLongClickListener { trySend(Unit); true }
    awaitClose { setOnLongClickListener(null) }
}

/** The current text first, then the text after each change, as RxBinding's textChanges() did. */
fun TextView.textChanges(): Flow<CharSequence> = callbackFlow {
    val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) { trySend(s) }
        override fun afterTextChanged(s: Editable) = Unit
    }
    trySend(text)
    addTextChangedListener(watcher)
    awaitClose { removeTextChangedListener(watcher) }
}

/** Editor action ids; consumes each action, as RxBinding's editorActions() did. */
fun TextView.editorActions(): Flow<Int> = callbackFlow {
    setOnEditorActionListener { _, actionId, _ -> trySend(actionId); true }
    awaitClose { setOnEditorActionListener(null) }
}

/** Whether the drawer at [gravity] is open: the current state first, then each open and close, as RxDrawerLayout's drawerOpen() did. */
fun DrawerLayout.drawerOpens(gravity: Int): Flow<Boolean> = callbackFlow {
    fun matches(drawerView: View) = (drawerView.layoutParams as DrawerLayout.LayoutParams).gravity == gravity
    val listener = object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerOpened(drawerView: View) { if (matches(drawerView)) trySend(true) }
        override fun onDrawerClosed(drawerView: View) { if (matches(drawerView)) trySend(false) }
    }
    trySend(isDrawerOpen(gravity))
    addDrawerListener(listener)
    awaitClose { removeDrawerListener(listener) }
}
