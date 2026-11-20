package com.example.companion.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.companion.app.CompanionApp
import com.example.companion.data.Role
import com.example.companion.protocol.SmsMessages
import com.example.companion.protocol.SmsReceived

/**
 * Android's SMS_RECEIVED broadcast (needs RECEIVE_SMS; delivered to a normal, non-default SMS app). It is informational:
 * it cannot be aborted and the SMS stays in the SIM Phone's normal messaging app. Only the SIM Phone acts on it, and
 * nothing here logs the sender or the text. The broadcast is protected (only the system can send it), so no extra
 * sender permission is needed on the receiver.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Stage 26: a broadcast receiver that throws crashes the whole app process (and the SIM Phone then stops forwarding). A bad
        // PDU, a missing app object or a storage problem is therefore caught here and kept for developers; the SMS itself always
        // stays in the phone's normal messaging app, so nothing is lost for the user.
        try {
            handle(context, intent)
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_RECEIVE, "incoming sms processing failed", e)
        }
    }

    private fun handle(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as? CompanionApp ?: return
        if (app.prefs.role != Role.SIM_PHONE) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) { SmsDiagnostics.record(SmsDiagnostics.Area.SIM_RECEIVE, "dropped: no message parts"); return }
        val sender = parts[0].originatingAddress?.takeIf { it.isNotEmpty() }      // no sender: nothing legitimate to forward
            ?: run { SmsDiagnostics.record(SmsDiagnostics.Area.SIM_RECEIVE, "dropped: no sender address"); return }
        val body = parts.joinToString("") { it.messageBody ?: "" }                         // multipart SMS arrive as several PDUs
        if (body.isEmpty()) { SmsDiagnostics.record(SmsDiagnostics.Area.SIM_RECEIVE, "dropped: empty body"); return }
        val ts = parts[0].timestampMillis.takeIf { it > 0 }
        app.hostSms.onSmsReceived(SmsReceived(SmsMessages.receivedId(sender, body, ts), sender, body, ts, subscriptionId(intent)))
    }

    /** Best effort: the framework adds the receiving subscription to this broadcast as an int extra. Absent -> not reported. */
    private fun subscriptionId(intent: Intent): Int? {
        for (key in SUBSCRIPTION_EXTRAS) {
            if (intent.hasExtra(key)) return intent.getIntExtra(key, -1).takeIf { it >= 0 }
        }
        return null
    }

    private companion object {
        val SUBSCRIPTION_EXTRAS = arrayOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX")
    }
}
