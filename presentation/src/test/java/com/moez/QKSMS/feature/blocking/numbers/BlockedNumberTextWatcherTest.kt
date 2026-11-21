package dev.octoshrimpy.quik.feature.blocking.numbers

import android.content.Context
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlockedNumberTextWatcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun formatsWhileTypingWithoutReplacingTheText() {
        val input = EditText(context)
        BlockedNumberTextWatcher(input, PhoneNumberUtils(context))
        val text = input.text

        "2125551234".forEach { digit -> input.append(digit.toString()) }

        // setText() swaps in a new text object, and Android then restarts the keyboard, which
        // drops Samsung's keyboard from its number page back to letters (#69)
        assertSame("the text was replaced instead of edited in place", text, input.text)
        assertNotEquals("the number should still be formatted", "2125551234", input.text.toString())
        assertEquals("the cursor should stay at the end", input.text.length, input.selectionEnd)
    }
}
