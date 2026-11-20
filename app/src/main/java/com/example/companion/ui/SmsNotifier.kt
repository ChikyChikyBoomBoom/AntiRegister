package com.example.companion.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.tr
import com.example.companion.sms.ClientSmsController
import com.example.companion.sms.SmsDiagnostics
import com.example.companion.sms.SmsEntry
import com.example.companion.sms.threadKey

/** Reads what Android currently allows. The decision itself lives in [SmsNotificationState.access]. */
object NotificationAccess {
    /** Below Android 13 there is no runtime permission, so this is true. */
    fun permissionGranted(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun status(c: Context, nm: NotificationManager, channelId: String): NotificationAccessStatus =
        SmsNotificationState.access(
            permissionGranted = permissionGranted(c),
            appEnabled = nm.areNotificationsEnabled(),
            channelBlocked = nm.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE
        )

    /** The system page where the user can turn notifications (and the Messages category) on. */
    fun settingsIntent(c: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
}

/**
 * Main Phone: shows an Android notification for a received SMS while the app UI is not visible.
 *
 * It is only a listener of the existing [ClientSmsController]. That controller already receives every SMS over the one
 * Bluetooth connection, stores it (de-duplicated by transfer id) and acknowledges it; it calls [onIncomingSms] only for a
 * message stored for the first time. So this class adds no transport, opens no second connection and keeps no list of
 * seen messages of its own. A second guard against duplicates: there is one notification per conversation (tag = conversation
 * key, fixed id), so posting again for the same conversation replaces it instead of adding another.
 *
 * Shows: sender (the contact's name and photo when the number is a contact, Stage 19; otherwise the number) and a one-line preview; "N new messages" when
 * several are unread. Tapping opens that conversation ([SmsThreadActivity], which marks it read; the notification is
 * dismissed then, see [onConversationRead]). The lock screen shows only "New message".
 * Never logs numbers or text.
 */
class SmsNotifier(
    private val context: Context,
    private val prefs: AppPrefs,
    private val isAppVisible: () -> Boolean,
    /** Stage 19: contact name for a number (null = not a contact / Contacts off). Wired to the app's single contact lookup. */
    private val nameFor: (String) -> String? = { null },
    /** Stage 19: the contact's round photo for a number, or null. Used only for the private (unlocked) version of the notification. */
    private val photoFor: (String) -> Bitmap? = { null }
) : ClientSmsController.Listener {

    private val nm = context.getSystemService(NotificationManager::class.java)

    override fun onSmsChanged() {}

    override fun onIncomingSms(entry: SmsEntry, unread: Int) {
        // Stage 26: the message is already stored and acknowledged when this runs. A notification that cannot be shown (permission
        // revoked meanwhile, a broken photo, the system refusing) must never affect that, so it is caught and kept for developers.
        // Nothing is lost: the message is in the Messages list; it just does not make a sound.
        try {
            val decision = SmsNotificationState.decide(
                isMainPhone = prefs.role == Role.MAIN_PHONE,
                appVisible = isAppVisible(),
                access = access()
            )
            if (decision == NotifyDecision.NOTIFY) post(entry, unread)
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_NOTIFY, "notification for incoming sms failed", e)
        }
    }

    /** The conversation was opened (messages marked read): its notification is no longer needed. */
    override fun onConversationRead(key: String) = cancel(key)

    fun cancel(key: String) {
        try { nm.cancel(key, NOTIFICATION_ID) } catch (e: Exception) { SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_NOTIFY, "cancel notification failed", e) }
    }

    /** Current state of notification permission / switches. Creates the channel first so a blocked channel can be detected. */
    fun access(): NotificationAccessStatus {
        ensureChannel()
        return NotificationAccess.status(context, nm, CHANNEL)
    }

    private fun ensureChannel() {
        // Creating an existing channel is a no-op, and never overrides what the user changed in system settings.
        nm.createNotificationChannel(
            // Stage 13: name and description follow the selected language (Android lets the same channel id update both; the
            // user's own importance / sound choices for the channel are kept).
            NotificationChannel(CHANNEL, tr(StringKey.SMS_NOTIF_CHANNEL_NAME), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr(StringKey.SMS_NOTIF_CHANNEL_DESC)
            }
        )
    }

    private fun post(e: SmsEntry, unread: Int) {
        val key = threadKey(e.address)
        val language = AppLocale.current       // Stage 13: read when the notification is built, so a background post uses the current choice
        val preview = SmsNotificationState.preview(e.body, language = language)
        val unreadLabel = SmsNotificationState.unreadLabel(unread, language)

        // Opens the conversation. The data URI makes the PendingIntent unique per conversation (extras alone do not).
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, SmsThreadActivity::class.java)
                .setData(Uri.fromParts("companion-sms", key, null))
                .putExtra(SmsThreadActivity.EXTRA_ADDRESS, e.address)
                .putExtra(SmsThreadActivity.EXTRA_FROM_NOTIFICATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // What a locked screen may show: no sender, no text.
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(SmsNotificationState.publicTitle(language))
            .build()

        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(com.example.companion.i18n.TextDirection.isolate(SmsNotificationState.senderTitle(e.address, nameFor, language), language))   // Stage 21: a number keeps its order in a Persian notification
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setWhen(e.timestamp).setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (unreadLabel.isNotEmpty()) b.setSubText(unreadLabel)
        photoFor(e.address)?.let { b.setLargeIcon(it) }
        nm.notify(key, NOTIFICATION_ID, b.build())
    }

    private companion object {
        const val CHANNEL = "sms_messages"
        const val NOTIFICATION_ID = 2001      // CallNotifier uses 1001; per-conversation notifications are told apart by tag
    }
}
