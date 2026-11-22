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
package dev.octoshrimpy.quik.common.widget

import dev.octoshrimpy.quik.common.util.extensions.events
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.google.android.mms.ContentType
import dev.octoshrimpy.quik.common.util.TextViewStyler
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.util.tryOrNull
import javax.inject.Inject


/**
 * Custom implementation of EditText to allow for dynamic text colors
 *
 * Beware of updating to extend AppCompatTextView, as this inexplicably breaks the view in
 * the contacts chip view
 */
class QkEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
    : AppCompatEditText(context, attrs) {

    @Inject lateinit var textViewStyler: TextViewStyler

    val backspaces = events<Unit>()
    val inputContentSelected = events<InputContentInfoCompat>()
    var supportsInputContent: Boolean = false

    init {
        if (!isInEditMode) {
            appComponent.inject(this)
            textViewStyler.applyAttributes(this, attrs)
        } else {
            TextViewStyler.applyEditModeAttributes(this, attrs)
        }
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection {

        val inputConnection = object : InputConnectionWrapper(super.onCreateInputConnection(editorInfo), true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    backspaces.tryEmit(Unit)
                }
                return super.sendKeyEvent(event)
            }


            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0) {
                    backspaces.tryEmit(Unit)
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }

        if (supportsInputContent) {
            EditorInfoCompat.setContentMimeTypes(editorInfo, arrayOf(
                    ContentType.IMAGE_JPEG,
                    ContentType.IMAGE_JPG,
                    ContentType.IMAGE_PNG,
                    ContentType.IMAGE_GIF))
        }

        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
            val grantReadPermission = flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && grantReadPermission) {
                return@OnCommitContentListener tryOrNull {
                    inputContentInfo.requestPermission()
                    inputContentSelected.tryEmit(inputContentInfo)
                    true
                } ?: false

            }

            true
        }

        // The replacement routes keyboard images through OnReceiveContentListener, which also
        // takes over paste; kept as is so pasting and keyboard GIFs behave as they always have
        @Suppress("DEPRECATION")
        return InputConnectionCompat.createWrapper(inputConnection, editorInfo, callback)
    }

}