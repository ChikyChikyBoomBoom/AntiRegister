package com.example.companion.ui

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.protocol.CallState

/** The two buttons on the incoming-call notification. */
enum class CallNotificationAction { ANSWER, REJECT }

/** Outcome of [CallNotificationState.decide]. Everything except [SHOW] means: no notification may exist right now. */
enum class CallNotifyDecision {
    SHOW,
    HIDE_NOT_MAIN_PHONE,
    /** The SIM Phone cannot be reached, so the real call state is unknown: a stale "ringing" notification would be a lie. */
    HIDE_LINK_DOWN,
    /** Anything but RINGING: answered, rejected, disconnected, idle, or still unknown (no report yet). */
    HIDE_NOT_RINGING,
    /** The app UI is visible: the call screen (or the app) already shows the call. */
    HIDE_APP_VISIBLE,
    HIDE_NOTIFICATIONS_OFF
}

/** Everything the notification displays. Two equal values mean "nothing changed", so no new post is needed. */
data class CallNotificationContent(
    /** Contact name when known, otherwise the number, otherwise "Unknown number". */
    val caller: String,
    /** The number, shown next to the caller only when [caller] is a name. Empty otherwise. */
    val secondary: String,
    /** The button the user pressed and the SIM Phone has not answered yet; the buttons are hidden meanwhile. */
    val pending: CallNotificationAction?,
    /** Short failure text for the last Answer / Reject press, empty when there is none. */
    val failure: String,
    /** Stage 27: the call session this notification is about. A different session is different content even if caller and text match. */
    val sessionId: String? = null
)

/** Pure functions (no Android types) so the notification rules can be unit-tested on a plain JVM. */
object CallNotificationState {

    const val PUBLIC_TEXT = "Incoming call"             // what a locked screen may show: no number, no name
    const val NO_RESPONSE = "No response from the SIM Phone. Try again."
    const val RESPONSE_TIMEOUT_MS = 5_000L              // same wait as the call screen's buttons
    /** Leaving the last visible screen for a moment (rotation, screen change) must not flash a notification. */
    const val HIDDEN_GRACE_MS = 600L
    const val UNKNOWN_NUMBER = "Unknown number"         // same wording as the call screen

    /**
     * Whether an incoming-call notification should exist now. [state] is the SIM Phone's last report
     * ([com.example.companion.calls.ClientCallController.snapshot]); null = unknown.
     */
    fun decide(
        isMainPhone: Boolean,
        linkUp: Boolean,
        state: CallState?,
        appVisible: Boolean,
        access: NotificationAccessStatus
    ): CallNotifyDecision = when {
        !isMainPhone -> CallNotifyDecision.HIDE_NOT_MAIN_PHONE
        !linkUp -> CallNotifyDecision.HIDE_LINK_DOWN
        state != CallState.RINGING -> CallNotifyDecision.HIDE_NOT_RINGING
        appVisible -> CallNotifyDecision.HIDE_APP_VISIBLE
        access != NotificationAccessStatus.ON -> CallNotifyDecision.HIDE_NOTIFICATIONS_OFF
        else -> CallNotifyDecision.SHOW
    }

    /**
     * Whether a press on Answer / Reject may be sent. Only for a call the SIM Phone currently reports as ringing, over a
     * live link, and not while the previous press is still waiting for its answer. A press on an outdated notification
     * therefore sends nothing.
     */
    fun actionAllowed(
        state: CallState?, linkUp: Boolean, alreadyPending: Boolean,
        /** Stage 27: the session the pressed notification belonged to, and the session ringing now (null = unknown). */
        pressedFor: String? = null, current: String? = null
    ): Boolean =
        linkUp && state == CallState.RINGING && !alreadyPending && !isOtherSession(pressedFor, current)

    /** Stage 27: true only when BOTH ids are known and differ. An unknown id never blocks anything (older SIM Phone build). */
    fun isOtherSession(a: String?, b: String?): Boolean = a != null && b != null && a != b

    /** Name when known (same seam as the Messages list), otherwise the number exactly as reported, otherwise a placeholder. */
    fun caller(number: String?, nameFor: (String) -> String?): String = caller(number, AppLanguage.ENGLISH, nameFor)

    /** Stage 19: same, with the placeholder in [language]. The lambda stays last so `caller(n) { ... }` call sites keep compiling. */
    fun caller(number: String?, language: AppLanguage, nameFor: (String) -> String?): String {
        if (number == null || number.isBlank()) return Strings.text(StringKey.NOTIF_CALL_UNKNOWN_NUMBER, language)
        return nameFor(number)?.trim()?.takeIf { it.isNotEmpty() } ?: number
    }

    /** The number as a second line, only when a name is shown as the caller. */
    fun secondary(number: String?, nameFor: (String) -> String?): String {
        if (number == null || number.isBlank()) return ""
        val name = nameFor(number)?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        return if (number != name) number else ""
    }

    fun content(
        number: String?,
        nameFor: (String) -> String?,
        pending: CallNotificationAction?,
        failure: String,
        sessionId: String? = null,
        language: AppLanguage = AppLanguage.ENGLISH
    ) = CallNotificationContent(caller(number, language, nameFor), secondary(number, nameFor), pending, failure, sessionId)

    /** Stage 19: the lock-screen text and the "no response" failure in [language] (the constants above are unchanged English). */
    fun publicText(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.NOTIF_CALL_INCOMING, language)
    fun noResponse(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.NOTIF_CALL_NO_RESPONSE, language)

    /** Text under the caller: the state of a pending press, a failure, or the plain ringing text. */
    fun statusText(c: CallNotificationContent, language: AppLanguage = AppLanguage.ENGLISH): String = when {
        c.pending == CallNotificationAction.ANSWER -> Strings.text(StringKey.NOTIF_CALL_ANSWERING, language)
        c.pending == CallNotificationAction.REJECT -> Strings.text(StringKey.NOTIF_CALL_REJECTING, language)
        c.failure.isNotEmpty() -> c.failure
        else -> Strings.text(StringKey.NOTIF_CALL_INCOMING, language)
    }

    /** Stage 27: extra on a button's broadcast naming the session the notification was posted for. */
    const val EXTRA_CALL_ID = "com.example.companion.extra.CALL_ID"

    private const val ACTION_ANSWER = "com.example.companion.action.CALL_NOTIFICATION_ANSWER"
    private const val ACTION_REJECT = "com.example.companion.action.CALL_NOTIFICATION_REJECT"

    /** Intent action of a button's broadcast. Anything else (including null) is not ours: [actionFrom] returns null. */
    fun actionName(a: CallNotificationAction) = when (a) {
        CallNotificationAction.ANSWER -> ACTION_ANSWER
        CallNotificationAction.REJECT -> ACTION_REJECT
    }

    fun actionFrom(name: String?): CallNotificationAction? = when (name) {
        ACTION_ANSWER -> CallNotificationAction.ANSWER
        ACTION_REJECT -> CallNotificationAction.REJECT
        else -> null
    }

    /** Stage 17: what the notifier remembers about a ringing notification that was removed only because the link dropped. */
    data class RingMemory(val number: String?, val removedAt: Long)

    /** A ringing call reported again after a reconnect within this time is treated as the SAME ring (no second alert). */
    const val RING_RESUME_WINDOW_MS = 2 * 60 * 1000L

    /**
     * Stage 17: is this ring the one the user was already alerted about before the link dropped? The wire protocol carries no
     * call id, so "same" means: same number (or both withheld) and back within [RING_RESUME_WINDOW_MS]. A resumed ring is
     * posted silently; anything else alerts as usual.
     */
    fun isResumedRing(memory: RingMemory?, number: String?, now: Long): Boolean =
        memory != null && memory.number == number && now - memory.removedAt in 0..RING_RESUME_WINDOW_MS

    /**
     * Stage 27: must posting this ring stay silent (no sound, no heads-up)? With a session id: yes exactly when this session was
     * already announced ([alertedSessionId]), whatever happened in between (link drop, app shown and hidden again). Without an id
     * (older SIM Phone build): the Stage 17 rule, [isResumedRing].
     */
    fun isRepeatAlert(sessionId: String?, alertedSessionId: String?, memory: RingMemory?, number: String?, now: Long): Boolean =
        if (sessionId != null) sessionId == alertedSessionId else isResumedRing(memory, number, now)

    /** The buttons are offered only while no press is waiting for the SIM Phone. */
    fun showButtons(c: CallNotificationContent): Boolean = c.pending == null
}
