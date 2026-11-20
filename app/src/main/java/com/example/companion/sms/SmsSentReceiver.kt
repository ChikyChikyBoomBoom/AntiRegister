package com.example.companion.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.companion.app.CompanionApp

/** Receives Android's per-part "sent" result for messages the SIM Phone submitted. Not exported: only our PendingIntents reach it. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Stage 26: never let an exception leave a receiver (it would crash the app). The controller guards its own work; this also
        // covers reading the intent and reaching the app object.
        try {
            if (intent.action != ACTION) return
            val id = intent.getStringExtra(EXTRA_ID)
            if (id == null) { SmsDiagnostics.record(SmsDiagnostics.Area.SIM_SENT_RESULT, "dropped: result without id"); return }
            val app = context.applicationContext as? CompanionApp ?: return
            app.hostSms.onPartResult(id, resultCode)
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_SENT_RESULT, "sent result processing failed", e)
        }
    }

    companion object {
        const val ACTION = "com.example.companion.SMS_SENT"
        const val EXTRA_ID = "id"
        const val EXTRA_TOTAL = "total"
    }
}
