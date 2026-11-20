package com.example.companion.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

sealed class GatewayResult {
    /** Handed to Android's SMS API. This is NOT "sent": the real result arrives later through [SmsSentReceiver]. */
    object Accepted : GatewayResult()
    data class Error(val code: String, val detail: String?) : GatewayResult()
}

/**
 * Stage 26: thrown by the caller's [SmsGateway.send] `onPartsKnown` callback when the "this id is being sent" record could not be
 * saved. [AndroidSmsGateway] turns it into [SmsError.STORAGE_ERROR] and does NOT send: the record is what makes a re-send harmless.
 */
class SendRecordNotSavedException : RuntimeException("send record not saved")

/** The only place that calls Android's SMS API. An interface so the SIM-side controller does not depend on it directly. */
interface SmsGateway {
    /** [onPartsKnown] is called with the number of SMS parts right BEFORE anything is dispatched (so the caller can persist first). */
    fun send(id: String, to: String, body: String, onPartsKnown: (Int) -> Unit): GatewayResult
}

class AndroidSmsGateway(private val context: Context) : SmsGateway {

    override fun send(id: String, to: String, body: String, onPartsKnown: (Int) -> Unit): GatewayResult {
        return try {
            // Stage 26: looking the manager up is inside the try as well (it reads subscription state, which can throw on some devices).
            val sms = manager() ?: return GatewayResult.Error(SmsError.NO_TELEPHONY, null)
            val parts = sms.divideMessage(body)
            val n = parts.size
            if (n == 0) return GatewayResult.Error(SmsError.INVALID_REQUEST, "empty message")
            val sentIntents = ArrayList<PendingIntent>(n)
            for (i in 0 until n) sentIntents.add(sentIntent(id, i, n))
            onPartsKnown(n)
            if (n == 1) sms.sendTextMessage(to, null, parts[0], sentIntents[0], null)
            else sms.sendMultipartTextMessage(to, null, parts, sentIntents, null)
            GatewayResult.Accepted
        } catch (e: SendRecordNotSavedException) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_GATEWAY, "not sent: send record not saved", e)
            GatewayResult.Error(SmsError.STORAGE_ERROR, null)
        } catch (e: SecurityException) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_GATEWAY, "send refused: permission", e)
            GatewayResult.Error(SmsError.PERMISSION_DENIED, null)
        } catch (e: IllegalArgumentException) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_GATEWAY, "send refused: recipient or text", e)
            GatewayResult.Error(SmsError.INVALID_REQUEST, "Android rejected the recipient or text")
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_GATEWAY, "sms api failed", e)
            GatewayResult.Error(SmsError.SMS_API_ERROR, e.javaClass.simpleName)   // class name only: never the message
        }
    }

    /** Uses the system's default SMS subscription (the SIM the user chose for SMS). */
    private fun manager(): SmsManager? {
        val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        val valid = SubscriptionManager.isValidSubscriptionId(sub)
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (valid) base?.createForSubscriptionId(sub) else base
        } else {
            @Suppress("DEPRECATION")
            if (valid) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
        }
    }

    /** One explicit, immutable PendingIntent per part; the data URI keeps them distinct so none replaces another. */
    private fun sentIntent(id: String, part: Int, total: Int): PendingIntent {
        val i = Intent(context, SmsSentReceiver::class.java)
            .setAction(SmsSentReceiver.ACTION)
            .setData(Uri.parse("companion-sms://sent/$id/$part"))
            .putExtra(SmsSentReceiver.EXTRA_ID, id)
            .putExtra(SmsSentReceiver.EXTRA_TOTAL, total)
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }
}
