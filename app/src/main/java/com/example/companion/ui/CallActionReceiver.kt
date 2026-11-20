package com.example.companion.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.companion.app.CompanionApp
import com.example.companion.bluetooth.BluetoothDiagnostics
import com.example.companion.calls.CallFeatures

/**
 * Receives the Answer / Reject buttons of the incoming-call notification (explicit, non-exported; only our own PendingIntents
 * reach it). It holds no logic: it hands the press (and the call-session id the notification was posted for) to [IncomingCallNotifier], which re-checks the real call state and then uses
 * the existing [com.example.companion.calls.ClientCallController]. Runs on the main thread and finishes immediately.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!CallFeatures.isCallingEnabled()) return   // Stage 7: Answer / Reject presses are ignored
        // Stage 30: an exception that leaves a receiver ends the whole app; a failed button press is only recorded for developers.
        try {
            val action = CallNotificationState.actionFrom(intent.action) ?: return
            (context.applicationContext as? CompanionApp)?.let { it.incomingCallNotifier.onAction(action, intent.getStringExtra(CallNotificationState.EXTRA_CALL_ID)) }
        } catch (e: Exception) {
            BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LISTENER, "call notification button", e)
        }
    }
}
