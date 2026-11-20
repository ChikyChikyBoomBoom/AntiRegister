package com.example.companion.ui

import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.ClientCallController
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role

/**
 * Main Phone: shows an Android notification while the SIM Phone reports a RINGING call and the app UI is not visible.
 *
 * It is only a listener of the existing [ClientCallController]. That controller already mirrors the SIM Phone's call state
 * received over the one Bluetooth connection and already sends Answer / Reject; this class adds no message, no transport and
 * no connection, and keeps no call state of its own. Every decision is recomputed from the controller by [sync]
 * ([CallNotificationState.decide]), so the notification cannot disagree with the call screen:
 *  - RINGING + link up + app not visible -> one notification (fixed id, so it is updated, never stacked);
 *  - anything else (answered, rejected, disconnected, idle, link lost, app shown) -> it is removed.
 *
 * Answer / Reject send the same commands as the call screen's buttons. After a press the buttons are replaced by
 * "Answering…" / "Rejecting…" until the SIM Phone reports the change, reports an error, or [CallNotificationState.RESPONSE_TIMEOUT_MS]
 * pass; the notification itself is removed only when the SIM Phone's report says the call is no longer ringing.
 * Tapping the notification opens [InCallActivity].
 *
 * Background rules: nothing here starts an activity or a service. The notification is posted from the live app process
 * (the Bluetooth callbacks that already run there); the user's tap is what opens the screen. Never logs numbers or names.
 * All methods run on the main thread (the controller, the app lifecycle callbacks and broadcast receivers all call there).
 */
class IncomingCallNotifier(
    private val context: Context,
    private val prefs: AppPrefs,
    private val calls: ClientCallController,
    private val isAppVisible: () -> Boolean,
    /** Stage 19: contact name for a number (null = not a contact / Contacts off). Wired to the app's single contact lookup. */
    private val nameFor: (String) -> String? = { null },
    /** Stage 19: the contact's round photo for a number, or null. Used only for the private (unlocked) version of the notification. */
    private val photoFor: (String) -> Bitmap? = { null }
) : ClientCallController.Listener {

    private val nm = context.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var pending: CallNotificationAction? = null
    private var failure: String = ""
    /** What is on screen now (null = nothing). Only used to skip a re-post of identical content. */
    private var posted: CallNotificationContent? = null
    /** Stage 17: set when the notification was removed ONLY because the link dropped, so the same ring is not announced twice after a reconnect. */
    private var ringMemory: CallNotificationState.RingMemory? = null
    /** Stage 27: the call session whose ring has already been announced (sound / heads-up). Survives a link drop and an app show / hide. */
    private var alertedSessionId: String? = null
    /** Stage 27: the session a pending Answer / Reject press was made for. */
    private var pendingSessionId: String? = null

    private val responseTimeout = Runnable {
        if (pending != null) { failure = CallNotificationState.noResponse(AppLocale.current); pending = null; sync() }
    }
    private val appHiddenSync = Runnable { sync() }

    init {
        ensureChannel()
        // A fresh process knows nothing about any call (no report yet). A notification left by a previous process
        // would claim a state nobody can confirm; the SIM Phone pushes a new report after the link is back, which posts it again.
        nm.cancel(NOTIFICATION_ID)
    }

    // ---------------------------------------------------------------- inputs

    /** The SIM Phone reported a call change, or the Bluetooth link changed (both arrive here). */
    override fun onCallUiChanged() = sync()

    /** [com.example.companion.app.CompanionApp] tells when the first activity became visible / the last one was stopped. */
    fun onAppVisibilityChanged(visible: Boolean) {
        main.removeCallbacks(appHiddenSync)
        if (visible) sync()
        else main.postDelayed(appHiddenSync, CallNotificationState.HIDDEN_GRACE_MS)   // rotation / screen change is not "backgrounded"
    }

    /**
     * A press on one of the notification's buttons (see [CallActionReceiver]). [pressedFor] = the call session that notification
     * was posted for (null = unknown). A press on a notification of an earlier call never acts on the call ringing now.
     */
    fun onAction(action: CallNotificationAction, pressedFor: String? = null) {
        // Stage 7: a press on a stale Answer / Reject button (left on screen by an older build) does nothing and removes the notification.
        if (!CallFeatures.isCallingEnabled()) { resetPress(); remove(); return }
        val current = calls.snapshot?.callId
        if (prefs.role == Role.MAIN_PHONE &&
            CallNotificationState.actionAllowed(
                calls.snapshot?.state, calls.linkUp, alreadyPending = pending != null, pressedFor = pressedFor, current = current
            )
        ) {
            pending = action; failure = ""; pendingSessionId = current
            main.removeCallbacks(responseTimeout); main.postDelayed(responseTimeout, CallNotificationState.RESPONSE_TIMEOUT_MS)
            when (action) {                       // the same two commands the call screen uses
                CallNotificationAction.ANSWER -> calls.answer()
                CallNotificationAction.REJECT -> calls.reject()
            }
        }
        sync()    // an outdated press (call already gone, link down) just removes the stale notification
    }

    // ---------------------------------------------------------------- state -> notification

    private fun sync() {
        if (!CallFeatures.isCallingEnabled()) { resetPress(); remove(); return }   // Stage 7: never posts an incoming-call notification
        val decision = CallNotificationState.decide(
            isMainPhone = prefs.role == Role.MAIN_PHONE,
            linkUp = calls.linkUp,
            state = calls.snapshot?.state,
            appVisible = isAppVisible(),
            access = access()
        )
        if (decision != CallNotifyDecision.SHOW) {
            if (decision == CallNotifyDecision.HIDE_LINK_DOWN) {
                if (posted != null) ringMemory = CallNotificationState.RingMemory(calls.lastRingNumber, SystemClock.elapsedRealtime())
            } else if (decision == CallNotifyDecision.HIDE_NOT_RINGING && calls.linkUp && calls.snapshot != null) {
                ringMemory = null                               // the SIM Phone says the ring is over: the next ring alerts normally
            }
            resetPress()
            remove()
            return
        }
        val sessionId = calls.snapshot?.callId
        // A different call is ringing now: a press made for the earlier one is over, its "Answering…" must not carry across.
        if (pending != null && CallNotificationState.isOtherSession(pendingSessionId, sessionId)) resetPress()
        val error = calls.lastError
        if (pending != null && error != null) {
            // The SIM Phone refused the command (send() cleared the old error before sending, so this one belongs to the press).
            val verb = if (pending == CallNotificationAction.ANSWER) "Answer" else "Reject"
            failure = "$verb failed: ${CallScreenState.describeError(error)}"
            pending = null; main.removeCallbacks(responseTimeout)
        }
        val content = CallNotificationState.content(calls.snapshot?.number, nameFor, pending, failure, sessionId, AppLocale.current)
        if (content == posted) return
        // An update of the notification already on screen ("Answering…", a name that became known) keeps its channel; onlyAlertOnce
        // keeps it quiet. A DIFFERENT call session replaces it (cancel first, otherwise onlyAlertOnce would swallow the alert).
        val update = posted != null && !CallNotificationState.isOtherSession(posted?.sessionId, sessionId)
        if (posted != null && !update) nm.cancel(NOTIFICATION_ID)
        // Same ring as one already announced (link dropped and came back, or the app was shown and hidden again): show it, but
        // without a second sound / heads-up.
        val silent = !update && CallNotificationState.isRepeatAlert(
            sessionId, alertedSessionId, ringMemory, calls.snapshot?.number, SystemClock.elapsedRealtime()
        )
        post(content, silent = silent)
        if (!update && !silent && sessionId != null) alertedSessionId = sessionId
        posted = content
        ringMemory = null
    }

    private fun resetPress() { pending = null; pendingSessionId = null; failure = ""; main.removeCallbacks(responseTimeout) }

    private fun remove() {
        if (posted != null) nm.cancel(NOTIFICATION_ID)
        posted = null
    }

    // ---------------------------------------------------------------- Android

    private fun access(): NotificationAccessStatus {
        ensureChannel()
        return NotificationAccess.status(context, nm, CHANNEL)
    }

    private fun ensureChannel() {
        // Creating an existing channel is a no-op, and never overrides what the user changed in system settings.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr(StringKey.NOTIF_CALL_CHANNEL_INCOMING), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr(StringKey.NOTIF_CALL_CHANNEL_INCOMING_DESC)
            }
        )
        // Stage 17: same ring shown again after a Bluetooth reconnect. Silent and without a heads-up, so one ring is announced once.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESUMED, tr(StringKey.NOTIF_CALL_CHANNEL_RESUMED), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr(StringKey.NOTIF_CALL_CHANNEL_RESUMED_DESC)
                setSound(null, null); enableVibration(false)
            }
        )
    }

    private fun post(c: CallNotificationContent, silent: Boolean = false) {
        val open = PendingIntent.getActivity(
            context, REQ_OPEN,
            Intent(context, InCallActivity::class.java)
                .putExtra(InCallActivity.EXTRA_FROM_NOTIFICATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // What a locked screen may show: no number, no name.
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(CallNotificationState.publicText(AppLocale.current))
            .build()

        val b = Notification.Builder(context, if (silent) CHANNEL_RESUMED else CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(com.example.companion.i18n.TextDirection.isolate(c.caller, AppLocale.current))   // Stage 21: same isolation as the SMS notification title
            .setContentText(CallNotificationState.statusText(c, AppLocale.current))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(true)              // a ringing call is not dismissed by a swipe; it goes away when the call state changes
            .setAutoCancel(false)          // removal is driven by the SIM Phone's report, not by a tap
            .setOnlyAlertOnce(true)        // "Answering…" / a changed name must not make it ring again
            .setShowWhen(false)
            .setContentIntent(open)
        if (c.secondary.isNotEmpty()) b.setSubText(com.example.companion.i18n.TextDirection.isolate(c.secondary, AppLocale.current))        // the number, when a name is shown as the caller
        // Photo only for a number that is a contact (c.secondary is non-empty exactly then); never on the lock screen's public version.
        if (c.secondary.isNotEmpty()) calls.snapshot?.number?.let { n -> photoFor(n)?.let { b.setLargeIcon(it) } }
        if (CallNotificationState.showButtons(c)) {
            b.addAction(action(tr(StringKey.SIMCALL_REJECT), android.R.drawable.ic_menu_close_clear_cancel, CallNotificationAction.REJECT, REQ_REJECT, c.sessionId))
            b.addAction(action(tr(StringKey.SIMCALL_ANSWER), android.R.drawable.sym_action_call, CallNotificationAction.ANSWER, REQ_ANSWER, c.sessionId))
        }
        nm.notify(NOTIFICATION_ID, b.build())
    }

    /** A broadcast to our own non-exported receiver. It starts no activity (Android 12+ forbids that from a notification trampoline). */
    private fun action(label: String, iconRes: Int, a: CallNotificationAction, request: Int, sessionId: String?): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            context, request,
            // FLAG_UPDATE_CURRENT replaces the extra when the same notification is re-posted for another call session.
            Intent(context, CallActionReceiver::class.java).setAction(CallNotificationState.actionName(a))
                .apply { if (sessionId != null) putExtra(CallNotificationState.EXTRA_CALL_ID, sessionId) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(context, iconRes), label, pi).build()
    }

    companion object {
        private const val CHANNEL = "incoming_calls"
        private const val CHANNEL_RESUMED = "incoming_calls_resumed"
        private const val NOTIFICATION_ID = 3001     // SIM Phone call notification uses 1001, SMS notifications 2001 (+ tag)
        private const val REQ_OPEN = 0
        private const val REQ_ANSWER = 1
        private const val REQ_REJECT = 2
    }
}
