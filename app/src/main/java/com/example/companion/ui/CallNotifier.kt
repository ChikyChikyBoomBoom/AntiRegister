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
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.CallManager
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState

/**
 * Android expects the default phone app to present call UI. This shows a minimal notification (with a full-screen
 * intent for ringing calls) that opens [SimCallActivity]. The system plays the ringtone; this channel is silent.
 */
class CallNotifier(private val context: Context) : CallManager.Listener {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(StringKey.NOTIF_CALL_CHANNEL), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        // Stage 30: a fresh process knows no call yet. A call notification left by the previous process may describe a call that ended
        // while the app was not running; if a call is still going, Telecom re-binds the call service and [onSnapshotChanged] posts it again.
        nm.cancel(ID)
    }

    override fun onSnapshotChanged(snapshot: CallSnapshot) {
        if (!CallFeatures.isCallingEnabled()) { nm.cancel(ID); return }   // Stage 7: no call notification, no full-screen call intent
        when (snapshot.state) {
            CallState.IDLE, CallState.DISCONNECTED -> nm.cancel(ID)
            else -> post(snapshot)
        }
    }

    private fun post(s: CallSnapshot) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, SimCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ringing = s.state == CallState.RINGING
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(if (ringing) android.R.drawable.sym_call_incoming else android.R.drawable.sym_call_outgoing)
            .setContentTitle(if (ringing) tr(StringKey.NOTIF_CALL_INCOMING) else tr(StringKey.NOTIF_CALL_STATE, CallStateText.name(s.state, AppLocale.current)))
            .setContentText(s.number ?: tr(StringKey.NOTIF_CALL_NUMBER_NA))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)     // Stage 27: later updates of the same call (number arrives, state changes) never alert again
            .setContentIntent(open)
        if (ringing) b.setFullScreenIntent(open, true)
        nm.notify(ID, b.build())
    }

    private companion object { const val CHANNEL = "calls"; const val ID = 1001 }
}
