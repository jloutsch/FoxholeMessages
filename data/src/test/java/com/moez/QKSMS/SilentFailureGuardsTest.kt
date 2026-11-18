package dev.octoshrimpy.quik

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings whose loss breaks a feature with no error: the app builds, runs and logs normally, and
 * the feature just stops working. Each was found the hard way; these read the source so a
 * well-meant cleanup can't quietly undo them.
 */
class SilentFailureGuardsTest {

    private fun source(path: String) = File("..", path).readText()

    @Test
    fun deliveryReportPendingIntentStaysMutable() {
        // Android puts the report's outcome in this intent; an immutable one drops it, and every
        // delivery report then reads as "Delivered" (DeliveryReportStatus).
        val code = source("data/src/main/java/com/moez/QKSMS/manager/QkTransaction.kt")
        val deliveryPendingIntent = Regex("""deliveryIntent\.setData\(messageUri\),\s*PendingIntent\.FLAG_UPDATE_CURRENT or PendingIntent\.FLAG_(\w+)""")
            .find(code)?.groupValues?.get(1)
        assertTrue("delivery PendingIntent not found in QkTransaction.kt", deliveryPendingIntent != null)
        assertTrue("delivery PendingIntent must be FLAG_MUTABLE, is FLAG_$deliveryPendingIntent", deliveryPendingIntent == "MUTABLE")
    }

    @Test
    fun mmsDownloadResultGoesToTheManifestReceiver() {
        // A receiver registered at runtime doesn't get the result on Android 14+ while the app is
        // cached, so pictures never arrived.
        val code = source("android-smsmms/src/main/java/com/android/mms/transaction/DownloadManager.java")
        assertTrue("download result must target MmsReceivedReceiver.MMS_RECEIVED",
            code.contains("new Intent(MmsReceivedReceiver.MMS_RECEIVED)"))
        assertTrue("download result intent must name the manifest receiver (BroadcastUtils.addClassName)",
            code.contains("BroadcastUtils.addClassName(context, download, MmsReceivedReceiver.MMS_RECEIVED)"))
        assertFalse("DownloadManager must not register a runtime receiver for the result",
            code.contains("registerReceiver("))
    }

    @Test
    fun wapPushBroadcastIsKeptAliveUntilHandled() {
        val code = source("android-smsmms/src/main/java/com/android/mms/transaction/PushReceiver.java")
        assertTrue("PushReceiver must pass goAsync() to ReceivePushTask",
            code.contains("new ReceivePushTask(context, goAsync())"))
    }
}
