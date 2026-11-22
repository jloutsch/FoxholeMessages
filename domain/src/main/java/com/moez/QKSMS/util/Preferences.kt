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
package dev.octoshrimpy.quik.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.octoshrimpy.quik.common.util.extensions.versionCode
import io.reactivex.Observable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.rx2.asObservable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Preferences @Inject constructor(
    context: Context,
    private val store: PreferenceStore,
    private val sharedPrefs: SharedPreferences
) {

    companion object {
        const val NIGHT_MODE_SYSTEM = 0
        const val NIGHT_MODE_OFF = 1
        const val NIGHT_MODE_ON = 2
        const val NIGHT_MODE_AUTO = 3

        const val TEXT_SIZE_SMALL = 0
        const val TEXT_SIZE_NORMAL = 1
        const val TEXT_SIZE_LARGE = 2
        const val TEXT_SIZE_LARGER = 3
        const val TEXT_SIZE_SUPER = 4

        const val NOTIFICATION_PREVIEWS_ALL = 0
        const val NOTIFICATION_PREVIEWS_NAME = 1
        const val NOTIFICATION_PREVIEWS_NONE = 2

        const val NOTIFICATION_ACTION_NONE = 0
        const val NOTIFICATION_ACTION_ARCHIVE = 1
        const val NOTIFICATION_ACTION_DELETE = 2
        const val NOTIFICATION_ACTION_BLOCK = 3
        const val NOTIFICATION_ACTION_CALL = 4
        const val NOTIFICATION_ACTION_READ = 5
        const val NOTIFICATION_ACTION_REPLY = 6
        const val NOTIFICATION_ACTION_SPEAK = 7

        const val SEND_DELAY_NONE = 0
        const val SEND_DELAY_SHORT = 1
        const val SEND_DELAY_MEDIUM = 2
        const val SEND_DELAY_LONG = 3

        const val SWIPE_ACTION_NONE = 0
        const val SWIPE_ACTION_ARCHIVE = 1
        const val SWIPE_ACTION_DELETE = 2
        const val SWIPE_ACTION_BLOCK = 3
        const val SWIPE_ACTION_CALL = 4
        const val SWIPE_ACTION_READ = 5
        const val SWIPE_ACTION_UNREAD = 6
        const val SWIPE_ACTION_SPEAK = 7

        const val BLOCKING_MANAGER_QKSMS = 0
        const val BLOCKING_MANAGER_CC = 1
        const val BLOCKING_MANAGER_SIA = 2
        const val BLOCKING_MANAGER_CB = 3

        const val MESSAGE_LINK_HANDLING_BLOCK = 0
        const val MESSAGE_LINK_HANDLING_ALLOW = 1
        const val MESSAGE_LINK_HANDLING_ASK = 2

        const val OTP_RETENTION_NEVER = 0
        const val OTP_RETENTION_1_DAY = 1
        const val OTP_RETENTION_7_DAYS = 7
        const val OTP_RETENTION_30_DAYS = 30

        const val OTP_NOTIFICATION_OFF = 0
        const val OTP_NOTIFICATION_LARGE = 1
        const val OTP_NOTIFICATION_LARGER = 2
        const val OTP_NOTIFICATION_HUGE = 3
    }

    // Internal
    val night = store.getBoolean("night", false)
    val canUseSubId = store.getBoolean("canUseSubId", true)
    val version = store.getInteger("version", context.versionCode)
    val changelogVersion = store.getInteger("changelogVersion", context.versionCode)
    val hasAskedForNotificationPermission = store.getBoolean("hasAskedForNotificationPermission", false)
    val backupDirectory = store.getObject("backupDirectory", Uri.EMPTY, UriPreferenceConverter())
    @Deprecated("This should only be accessed when migrating to @blockingManager")
    val sia = store.getBoolean("sia", false)

    // User configurable
    val sendAsGroup = store.getBoolean("sendAsGroup", true)
    val nightMode = store.getInteger("nightMode", when (Build.VERSION.SDK_INT >= 29) {
        true -> NIGHT_MODE_SYSTEM
        false -> NIGHT_MODE_OFF
    })
    val nightStart = store.getString("nightStart", "18:00")
    val nightEnd = store.getString("nightEnd", "6:00")
    val black = store.getBoolean("black", false)
    val autoColor = store.getBoolean("autoColor", true)
    // Android 12+: use the wallpaper accent instead of the chosen theme colour (fork issue #42)
    val systemColors = store.getBoolean("fh_systemColors", false)
    val systemFont = store.getBoolean("systemFont", false)
    val showStt = store.getBoolean("showStt", true)
    val showSttOffsetX = store.getFloat("showSttOffsetX", Float.MIN_VALUE)
    val showSttOffsetY = store.getFloat("showSttOffsetY", Float.MIN_VALUE)
    val textSize = store.getInteger("textSize", TEXT_SIZE_NORMAL)
    val pinchToZoom = store.getBoolean("pinchToZoom", false)
    val messageTextScale = store.getFloat("messageTextScale", 1f)
    val blockingManager = store.getInteger("blockingManager", BLOCKING_MANAGER_QKSMS)
    val drop = store.getBoolean("drop", false)
    val silentNotContact = store.getBoolean("silentNotContact", false)
    val notifAction1 = store.getInteger("notifAction1", NOTIFICATION_ACTION_READ)
    val notifAction2 = store.getInteger("notifAction2", NOTIFICATION_ACTION_REPLY)
    val notifAction3 = store.getInteger("notifAction3", NOTIFICATION_ACTION_NONE)
    val qkreply = store.getBoolean("qkreply", Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
    val qkreplyTapDismiss = store.getBoolean("qkreplyTapDismiss", true)
    val sendDelay = store.getInteger("sendDelay", SEND_DELAY_NONE)
    val swipeRight = store.getInteger("swipeRight", SWIPE_ACTION_ARCHIVE)
    val swipeLeft = store.getInteger("swipeLeft", SWIPE_ACTION_ARCHIVE)
    // Both swipe directions default to Archive, so a stray/accidental swipe archives a
    // conversation immediately with no prompt today - defaults to true (on) since this is
    // specifically meant to prevent that accident, not an opt-in convenience.
    val confirmArchive = store.getBoolean("confirmArchive", true)
    val autoEmoji = store.getBoolean("autoEmoji", true)
    val delivery = store.getBoolean("delivery", false)
    val signature = store.getString("signature", "")
    val autoDelete = store.getInteger("autoDelete", 0)
    val longAsMms = store.getBoolean("longAsMms", false)
    val mmsSize = store.getInteger("mmsSize", -1)
    val messageLinkHandling = store.getInteger("messageLinkHandling", MESSAGE_LINK_HANDLING_ASK)
    val logging = store.getBoolean("logging", false)
    // Set by 2.8.0's one-time Realm → Room import (#44); the old Realm files are deleted once it's set
    val roomImportVersion = store.getInteger("fh_roomImportVersion", 0)
    val unreadAtTop = store.getBoolean("unreadAtTop", false)

    val autoDeduplicate = store.getBoolean("autoDeduplicateMessages", false)

    val autoSortEnabled = store.getBoolean("fh_autoSortEnabled", true)
    // Which version of the sorting rules the stored categories were made with; see
    // MessageCategoryBackfill.CLASSIFIER_VERSION. 0 = before versioning existed.
    val classifierVersion = store.getInteger("fh_classifierVersion", 0)

    // Bumping this constant re-runs the emoji reaction reparse once on the next launch, for when
    // reactions that should have been recognised are sitting in threads as plain "Liked ..." text.
    // A preference rather than a schema-migration trigger (which is how the original reparse was
    // driven) so that rolling back to an earlier build stays possible - a Realm schema bump would
    // make the database unopenable by the previous version.
    val emojiReparseVersion = store.getInteger("fh_emojiReparseVersion", 0)
    val otpRetentionDays = store.getInteger("fh_otpRetentionDays", OTP_RETENTION_NEVER)
    // How large a one-time code is drawn in its notification; Off keeps the normal notification
    val otpNotificationSize = store.getInteger("fh_otpNotificationSize", OTP_NOTIFICATION_LARGE)
    // The last code message copied to the clipboard on arrival, so a rebuilt notification doesn't
    // copy it again over something the user has copied since
    val lastAutoCopiedCodeMessageId = store.getLong("fh_lastAutoCopiedCodeMessageId", 0L)

    /** Inbox tabs (category names, see InboxTab) whose conversations the home-screen widget lists. */
    val widgetTabs = store.getStringSet("fh_widgetTabs", setOf("PERSONAL", "TRANSACTIONAL", "PROMOTIONAL"))

    fun categoryNotifications(category: String): Preference<Boolean> =
        store.getBoolean("fh_notifications_$category", true)

    fun categoryPreviews(category: String): Preference<Boolean> =
        store.getBoolean("fh_previews_$category", true)

    fun categoryVibration(category: String): Preference<Boolean> =
        store.getBoolean("fh_vibration_$category", false)

    fun categoryRingtone(category: String): Preference<String> =
        store.getString("fh_ringtone_$category", Settings.System.DEFAULT_NOTIFICATION_URI.toString())

    init {
        // Migrate from old night mode preference to new one, now that we support android Q night mode
        val nightModeSummary = store.getInteger("nightModeSummary")
        if (nightModeSummary.isSet) {
            nightMode.set(when (nightModeSummary.get()) {
                0 -> NIGHT_MODE_OFF
                1 -> NIGHT_MODE_ON
                2 -> NIGHT_MODE_AUTO
                else -> NIGHT_MODE_OFF
            })
            nightModeSummary.delete()
        }
    }

    /** Keys of settings as they change (emitted on the main thread). */
    val keyChanges: Flow<String> = sharedPrefs.keyChanges()

    @Deprecated("Rx bridge for screens not yet on Flow; removed in #34 Task 29", ReplaceWith("keyChanges"))
    val keyChangesObservable: Observable<String> = keyChanges.asObservable().share()

    fun theme(
        recipientId: Long = 0,
        default: Int = store.getInteger("theme", 0xFF0097A7.toInt()).get()
    ): Preference<Int> {
        return when (recipientId) {
            0L -> store.getInteger("theme", 0xFF0097A7.toInt())
            else -> store.getInteger("theme_$recipientId", default)
        }
    }

    fun notifications(threadId: Long = 0): Preference<Boolean> {
        val default = store.getBoolean("notifications", true)

        return when (threadId) {
            0L -> default
            else -> store.getBoolean("notifications_$threadId", default.get())
        }
    }

    fun notificationPreviews(threadId: Long = 0): Preference<Int> {
        val default = store.getInteger("notification_previews", 0)

        return when (threadId) {
            0L -> default
            else -> store.getInteger("notification_previews_$threadId", default.get())
        }
    }

    fun wakeScreen(threadId: Long = 0): Preference<Boolean> {
        val default = store.getBoolean("wake", false)

        return when (threadId) {
            0L -> default
            else -> store.getBoolean("wake_$threadId", default.get())
        }
    }

    fun vibration(threadId: Long = 0): Preference<Boolean> {
        val default = store.getBoolean("vibration", true)

        return when (threadId) {
            0L -> default
            else -> store.getBoolean("vibration$threadId", default.get())
        }
    }

    fun ringtone(threadId: Long = 0): Preference<String> {
        val default = store.getString("ringtone", Settings.System.DEFAULT_NOTIFICATION_URI.toString())

        return when (threadId) {
            0L -> default
            else -> store.getString("ringtone_$threadId", default.get())
        }
    }

    fun backgroundUri(threadId: Long = 0): Preference<String> {
        val default = store.getString("backgroundUri", "")

        return when (threadId) {
            0L -> default
            else -> store.getString("backgroundUri_$threadId", default.get())
        }
    }

    /**
     * User-chosen display name for an inbox tab (Personal/Transactions/Promotions/Starred),
     * keyed by the Tab enum's name. Empty means "use the built-in default title" - keyed by
     * name rather than the Tab type itself since Tab lives in the presentation module.
     */
    fun tabTitle(tabName: String): Preference<String> = store.getString("tabTitle_$tabName", "")
}
