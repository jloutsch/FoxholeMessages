
package com.android.mms.transaction;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.mms.MmsConfig;
import timber.log.Timber;

import com.klinker.android.send_message.BroadcastUtils;
import com.klinker.android.send_message.MmsReceivedReceiver;
import com.klinker.android.send_message.SmsManagerFactory;

import java.io.File;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In order to avoid downloading duplicate MMS.
 * We should manage to call SMSManager.downloadMultimediaMessage().
 */
public class DownloadManager {
    private static DownloadManager ourInstance = new DownloadManager();
    // Downloads in progress, by content location, so a duplicate notification doesn't start a second.
    private static final ConcurrentHashMap<String, Boolean> mMap = new ConcurrentHashMap<>();

    public static DownloadManager getInstance() {
        return ourInstance;
    }

    private DownloadManager() {

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void downloadMultimediaMessage(final Context context, final String location, Uri uri, boolean byPush, int subscriptionId) {
        if (location == null || mMap.get(location) != null) {
            return;
        }

        mMap.put(location, true);

        Timber.v("receiving with system method");
        final String fileName = "download." + Math.abs(new Random().nextLong()) + ".dat";
        File mDownloadFile = new File(context.getCacheDir(), fileName);
        Uri contentUri = (new Uri.Builder())
                .authority(context.getPackageName() + ".MmsFileProvider")
                .path(fileName)
                .scheme(ContentResolver.SCHEME_CONTENT)
                .build();
        // The result goes straight to the app's manifest MmsReceivedReceiver. It used to go to a
        // receiver registered at runtime, which then re-broadcast it: on Android 14+ broadcasts to
        // runtime receivers are held back while the app is cached, and lost if the process is
        // killed meanwhile, so the picture never arrived. A manifest receiver is delivered even if
        // Android has to start the app. (The forwarding also dropped the result code.)
        Intent download = new Intent(MmsReceivedReceiver.MMS_RECEIVED);
        BroadcastUtils.addClassName(context, download, MmsReceivedReceiver.MMS_RECEIVED);
        // Unique data per download, so two downloads in flight don't share (and cancel) one
        // PendingIntent; the receiver doesn't read it.
        download.setData(Uri.parse("mms-download:" + UUID.randomUUID()));
        download.putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, mDownloadFile.getPath());
        download.putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, location);
        download.putExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, byPush);
        download.putExtra(MmsReceivedReceiver.EXTRA_URI, uri);
        download.putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, subscriptionId);
        // Mutable so the system can add the HTTP status; explicit (component set), as Android 14+
        // requires for mutable PendingIntents.
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, download, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);

        final SmsManager smsManager = SmsManagerFactory.createSmsManager(subscriptionId);

        Bundle configOverrides = new Bundle();
        String httpParams = MmsConfig.getHttpParams();
        if (!TextUtils.isEmpty(httpParams)) {
            configOverrides.putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, httpParams);
        } else {
            // this doesn't seem to always work...
            // configOverrides = smsManager.getCarrierConfigValues();
        }

        grantUriPermission(context, contentUri);
        smsManager.downloadMultimediaMessage(context, location, contentUri, configOverrides, pendingIntent);
    }

    private void grantUriPermission(Context context, Uri contentUri) {
        context.grantUriPermission(context.getPackageName() + ".MmsFileProvider",
                contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    public static void finishDownload(String location) {
        if (location != null) {
            mMap.remove(location);
        }
    }
}
