package dev.octoshrimpy.quik.feature.compose

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.moez.QKSMS.contentproviders.MmsPartProvider
import dev.octoshrimpy.quik.extensions.isSafeToAttachFromOtherApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What another app can make Compose attach. The app reads a shared link with its own permissions
 * (SMS, photos), and Android doesn't check the sending app when the receiver can already read it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedAttachmentSafetyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun safe(uri: String) = Uri.parse(uri).isSafeToAttachFromOtherApp(context)

    @Test
    fun anotherAppsFilesCanBeAttached() {
        assertTrue(safe("content://com.example.files/shared/photo.jpg"))
    }

    @Test
    fun thisAppsOwnProvidersAreRefused() {
        assertFalse(safe("content://${context.packageName}.mmspart/part/1/photo.png"))
        assertFalse(safe("file:///data/data/${context.packageName}/databases/foxhole.db"))
    }

    @Test
    fun aUserIdPrefixDoesNotHideThisAppsProvider() {
        // "0@authority" opens the same provider for user 0, but didn't match the owner lookup
        assertFalse(safe("content://0@${context.packageName}.mmspart/part/1/photo.png"))
    }

    @Test
    fun picturesFromOtherConversationsAreRefused() {
        // Readable only with READ_SMS, which this app holds and the sending app may not
        assertFalse(safe("content://mms/part/42"))
        assertFalse(safe("content://sms/1"))
        assertFalse(safe("content://mms-sms/conversations"))
    }

    @Test
    fun anAttachmentNameCannotPointAtAnotherPart() {
        // The name comes from whoever sent the MMS; "1234/x.pdf" used to make the link serve part 1234
        val uri = MmsPartProvider().getUriForMmsPartId(context, 5, "1234/report.pdf")
        assertEquals(listOf("part", "5", "1234/report.pdf"), uri.pathSegments)
    }
}
