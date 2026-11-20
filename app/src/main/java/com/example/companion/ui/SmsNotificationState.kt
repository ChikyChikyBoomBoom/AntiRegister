package com.example.companion.ui

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection

/** Why notifications can or cannot be shown right now. Read from Android by [NotificationAccess]; decided here. */
enum class NotificationAccessStatus {
    ON,
    /** Android 13+: the POST_NOTIFICATIONS runtime permission is not granted. */
    PERMISSION_DENIED,
    /** The user switched notifications off for the whole app in system settings. */
    APP_DISABLED,
    /** The user switched off the "Messages" category (channel) in system settings. */
    CHANNEL_BLOCKED
}

/** Outcome of [SmsNotificationState.decide]. Everything except [NOTIFY] means: store the message (as before), show nothing. */
enum class NotifyDecision { NOTIFY, SKIP_NOT_MAIN_PHONE, SKIP_APP_VISIBLE, SKIP_NOTIFICATIONS_OFF }

/** Pure functions (no Android types) so the notification rules can be unit-tested on a plain JVM. */
object SmsNotificationState {

    const val MAX_PREVIEW = 300
    const val MAX_UNREAD_LABEL = 99
    const val EMPTY_BODY_TEXT = "(empty message)"
    const val UNKNOWN_SENDER = "Unknown sender"
    const val PUBLIC_TITLE = "New message"

    private val WHITESPACE = Regex("\\s+")

    /**
     * Whether a newly stored SMS should raise a notification. The caller only asks for a message that was stored for
     * the FIRST time (a re-delivery after a Bluetooth reconnect never gets here), so this is not a duplicate check.
     * While the app UI is visible the user sees the message in the app (unread badge / open conversation) instead.
     */
    fun decide(isMainPhone: Boolean, appVisible: Boolean, access: NotificationAccessStatus): NotifyDecision = when {
        !isMainPhone -> NotifyDecision.SKIP_NOT_MAIN_PHONE
        appVisible -> NotifyDecision.SKIP_APP_VISIBLE
        access != NotificationAccessStatus.ON -> NotifyDecision.SKIP_NOTIFICATIONS_OFF
        else -> NotifyDecision.NOTIFY
    }

    /** Permission first (it is what the user can fix with one tap on Android 13+), then the app switch, then the category. */
    fun access(permissionGranted: Boolean, appEnabled: Boolean, channelBlocked: Boolean): NotificationAccessStatus = when {
        !permissionGranted -> NotificationAccessStatus.PERMISSION_DENIED
        !appEnabled -> NotificationAccessStatus.APP_DISABLED
        channelBlocked -> NotificationAccessStatus.CHANNEL_BLOCKED
        else -> NotificationAccessStatus.ON
    }

    /** Text for the Messages screen when notifications cannot be shown; empty when they can. */
    fun accessNotice(status: NotificationAccessStatus, language: AppLanguage = AppLanguage.ENGLISH): String = when (status) {
        NotificationAccessStatus.ON -> ""
        NotificationAccessStatus.PERMISSION_DENIED -> Strings.text(StringKey.SMS_NOTICE_PERMISSION, language)
        NotificationAccessStatus.APP_DISABLED -> Strings.text(StringKey.SMS_NOTICE_APP_DISABLED, language)
        NotificationAccessStatus.CHANNEL_BLOCKED -> Strings.text(StringKey.SMS_NOTICE_CHANNEL_BLOCKED, language)
    }

    /** The contact name when one is known (same seam as the Messages list), otherwise the number / sender as received. */
    fun senderTitle(address: String, nameFor: (String) -> String?, language: AppLanguage = AppLanguage.ENGLISH): String =
        SmsConversationsState.title(address, nameFor).trim().ifEmpty { Strings.text(StringKey.SMS_NOTIF_UNKNOWN_SENDER, language) }

    /**
     * One line of the message text: line breaks and runs of spaces collapse to one space, an empty text gets a placeholder,
     * and a long text is cut to at most [max] characters ending in an ellipsis (never in the middle of an emoji).
     */
    fun preview(body: String, max: Int = MAX_PREVIEW, language: AppLanguage = AppLanguage.ENGLISH): String {
        val oneLine = body.trim().replace(WHITESPACE, " ")
        if (oneLine.isEmpty()) return Strings.text(StringKey.SMS_NOTIF_EMPTY_BODY, language)
        if (oneLine.length <= max) return oneLine
        var end = (max - 1).coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(oneLine[end - 1])) end--      // do not split a surrogate pair
        return oneLine.substring(0, end).trimEnd() + "…"
    }

    /** Second line of the notification when several messages of the conversation are unread; empty for one (or none). */
    fun unreadLabel(unread: Int, language: AppLanguage = AppLanguage.ENGLISH): String = when {
        unread <= 1 -> ""
        unread > MAX_UNREAD_LABEL ->
            Strings.text(StringKey.SMS_NOTIF_NEW_MESSAGES, language, TextDirection.localizeDigits("$MAX_UNREAD_LABEL+", language))
        else -> Strings.text(StringKey.SMS_NOTIF_NEW_MESSAGES, language, TextDirection.number(unread, language))
    }

    /** Title of the version shown on a locked screen (no sender, no text). */
    fun publicTitle(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.SMS_NOTIF_PUBLIC_TITLE, language)
}
