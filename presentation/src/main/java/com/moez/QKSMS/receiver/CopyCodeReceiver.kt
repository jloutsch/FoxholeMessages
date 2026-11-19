package dev.octoshrimpy.quik.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.content.getSystemService
import dev.octoshrimpy.quik.R

/** The "Copy code" button on a one-time-code notification. */
class CopyCodeReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_CODE = "code"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val clip = ClipData.newPlainText("Code", code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keeps the code out of the clipboard's on-screen preview
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService<ClipboardManager>()?.setPrimaryClip(clip)

        // Android 13+ shows its own "Copied" confirmation
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.toast_code_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
