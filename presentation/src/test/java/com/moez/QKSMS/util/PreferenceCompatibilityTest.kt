package dev.octoshrimpy.quik.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * Settings saved by rx-preferences 2.0.0-RC3 must read back unchanged after it's replaced (#34):
 * same file, same keys, same stored formats.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferenceCompatibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val raw: SharedPreferences = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    private fun prefs() = Preferences(context, PreferenceStore(raw), raw)

    @Test
    fun valuesStoredByRxPreferencesReadBack() {
        raw.edit()
            .putBoolean("black", true)
            .putInt("nightMode", 2)
            .putString("nightStart", "19:30")
            .putFloat("messageTextScale", 1.5f)
            .putLong("fh_lastAutoCopiedCodeMessageId", 42L)
            .putStringSet("fh_widgetTabs", setOf("PERSONAL"))
            .putString("backupDirectory", "content://x/y")
            .commit()
        val prefs = prefs()
        assertEquals(true, prefs.black.get())
        assertEquals(2, prefs.nightMode.get())
        assertEquals("19:30", prefs.nightStart.get())
        assertEquals(1.5f, prefs.messageTextScale.get())
        assertEquals(42L, prefs.lastAutoCopiedCodeMessageId.get())
        assertEquals(setOf("PERSONAL"), prefs.widgetTabs.get())
        assertEquals(Uri.parse("content://x/y"), prefs.backupDirectory.get())
    }

    @Test
    fun setWritesTheSameFormat() {
        val prefs = prefs()
        prefs.nightMode.set(3)
        prefs.backupDirectory.set(Uri.parse("content://a"))
        assertEquals(3, raw.getInt("nightMode", -1))
        assertEquals("content://a", raw.getString("backupDirectory", null))
    }

    @Test
    fun unsetReturnsDefaultAndDeleteRestoresIt() {
        val prefs = prefs()
        assertEquals(false, prefs.black.get())
        assertFalse(prefs.black.isSet)
        prefs.black.set(true)
        prefs.black.delete()
        assertEquals(false, prefs.black.get())
        assertFalse(prefs.black.isSet)
    }

    @Test
    fun asFlowEmitsCurrentThenChanges() = runBlocking {
        val prefs = prefs()
        val values = mutableListOf<Boolean>()
        val job = launch(Dispatchers.Unconfined) { prefs.black.asFlow().take(3).toList(values) }
        prefs.black.set(true)
        shadowOf(Looper.getMainLooper()).idle()
        prefs.black.set(false)
        shadowOf(Looper.getMainLooper()).idle()
        job.join()
        assertEquals(listOf(false, true, false), values)
    }
}
