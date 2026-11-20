package com.example.companion.calls

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.example.companion.bluetooth.BluetoothDiagnostics
import com.example.companion.protocol.CallCommand
import com.example.companion.protocol.CallDirection
import com.example.companion.protocol.CallError
import com.example.companion.protocol.CallSessionId
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SIM-phone call logic on top of android.telecom. Knows nothing about Bluetooth or UI.
 *
 * Calls arrive from [CompanionInCallService] (only possible while we hold the dialer role). Everything runs on the
 * main thread (InCallService callbacks and Call callbacks are delivered there).
 *
 * Telecom's answer / reject / disconnect are fire-and-forget: they return nothing and failures are silent. So a command
 * is reported as failed when we can see up front that it cannot work (no role, no such call, bad number), or when Android
 * does not report the expected state change within a timeout ([CallError.NOT_CONFIRMED]). Success is only ever
 * reported through the real state change.
 */
class CallManager(private val context: Context) {

    interface Listener {
        fun onSnapshotChanged(snapshot: CallSnapshot) {}
        fun onCommandFailed(command: String, error: String, snapshot: CallSnapshot) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val calls = ArrayList<Call>()
    private val directions = HashMap<Call, CallDirection>()
    /** Stage 27: one stable id per Telecom call, created when the call is added and dropped when it is removed. */
    private val sessionIds = HashMap<Call, String>()
    private var lastCallAddedAt = 0L
    /** elapsedRealtime when we saw a call become ACTIVE; fallback for [activeDurationMs] when Telecom gave no connect time. */
    private val activeAt = HashMap<Call, Long>()
    /** Until this elapsedRealtime a DIAL we already handed to Telecom has not shown up as a call yet; a second DIAL is refused. */
    private var dialInFlightUntil = 0L

    /** Current call as seen by Android; [CallSnapshot.IDLE] when there is none (including after an app restart). */
    @Volatile var snapshot: CallSnapshot = CallSnapshot.IDLE; private set

    /** True when Android will actually give us calls (we hold the default phone app role). */
    val controlAvailable: Boolean get() = CallRole.isHeld(context)

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (directions[call] == null) detectDirection(call)?.let { directions[call] = it }
            if (state == Call.STATE_ACTIVE && activeAt[call] == null) activeAt[call] = SystemClock.elapsedRealtime()
            recompute()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) = recompute()
    }

    // ------------------------------------------------------------------ from InCallService

    fun onCallAdded(call: Call) {
        if (call in calls) return
        calls.add(call)
        sessionIds[call] = CallSessionId.generate()
        lastCallAddedAt = SystemClock.elapsedRealtime()
        dialInFlightUntil = 0L                      // Telecom has the call now; calls.any{} guards a second DIAL from here on
        detectDirection(call)?.let { directions[call] = it }
        call.registerCallback(callback)
        recompute()
    }

    fun onCallRemoved(call: Call) {
        val wasPrimary = primary() == call
        val last = toSnapshot(call)
        call.unregisterCallback(callback)
        calls.remove(call); directions.remove(call); activeAt.remove(call); sessionIds.remove(call)
        // Make sure observers always see DISCONNECTED before IDLE, even if Telecom skipped the state callback.
        if (wasPrimary && snapshot.state != CallState.DISCONNECTED && snapshot.state != CallState.IDLE)
            emit(last.copy(state = CallState.DISCONNECTED))
        recompute()
    }

    /**
     * Stage 17: how long the reported call has been ACTIVE, for the Main Phone's timer after a Bluetooth reconnect.
     * Telecom's own connect time wins (it survives an app restart); otherwise the moment this process saw the call become
     * ACTIVE; null when the call is not ACTIVE or neither is known. Never guessed.
     */
    fun activeDurationMs(): Long? {
        val c = primary()?.takeIf { it.state == Call.STATE_ACTIVE } ?: return null
        val connectedAt = c.details?.connectTimeMillis ?: 0L
        if (connectedAt > 0) (System.currentTimeMillis() - connectedAt).takeIf { it >= 0 }?.let { return it }
        return activeAt[c]?.let { SystemClock.elapsedRealtime() - it }
    }

    // ------------------------------------------------------------------ commands

    fun answer() {
        if (!CallFeatures.isCallingEnabled()) return   // Stage 7: calling is off; nothing reaches Telecom
        if (!requireRole(CallCommand.ANSWER)) return
        val c = calls.firstOrNull { isRinging(it.state) } ?: return fail(CallCommand.ANSWER, CallError.NO_INCOMING_CALL)
        try { c.answer(VideoProfile.STATE_AUDIO_ONLY) } catch (e: Exception) { return fail(CallCommand.ANSWER, CallError.TELECOM_ERROR) }
        confirm(CallCommand.ANSWER, 5_000) { c !in calls || !isRinging(c.state) }
    }

    fun reject() {
        if (!CallFeatures.isCallingEnabled()) return   // Stage 7
        if (!requireRole(CallCommand.REJECT)) return
        val c = calls.firstOrNull { isRinging(it.state) } ?: return fail(CallCommand.REJECT, CallError.NO_INCOMING_CALL)
        try { c.reject(false, null) } catch (e: Exception) { return fail(CallCommand.REJECT, CallError.TELECOM_ERROR) }
        confirm(CallCommand.REJECT, 5_000) { c !in calls || !isRinging(c.state) }
    }

    /** Disconnects the current non-ringing call (active, dialing, connecting or held). */
    fun hangup() {
        if (!CallFeatures.isCallingEnabled()) return   // Stage 7
        if (!requireRole(CallCommand.HANGUP)) return
        val c = calls.filter { !isRinging(it.state) && it.state != Call.STATE_DISCONNECTED }
            .minByOrNull { rank(it.state) } ?: return fail(CallCommand.HANGUP, CallError.NO_ACTIVE_CALL)
        try { c.disconnect() } catch (e: Exception) { return fail(CallCommand.HANGUP, CallError.TELECOM_ERROR) }
        confirm(CallCommand.HANGUP, 5_000) {
            c !in calls || c.state == Call.STATE_DISCONNECTING || c.state == Call.STATE_DISCONNECTED
        }
    }

    /** Places a cellular call to exactly [number]. Rejects (never edits) numbers it will not dial. */
    fun dial(number: String) {
        if (!CallFeatures.isCallingEnabled()) return   // Stage 7: never reaches TelecomManager.placeCall
        if (!requireRole(CallCommand.DIAL)) return
        if (!DIALABLE.matches(number)) return fail(CallCommand.DIAL, CallError.INVALID_NUMBER)
        if (calls.any { it.state != Call.STATE_DISCONNECTED }) return fail(CallCommand.DIAL, CallError.CALL_IN_PROGRESS)
        // Stage 17: a repeated DIAL (double tap, or re-sent around a Bluetooth drop) must not place a second cellular call.
        if (SystemClock.elapsedRealtime() < dialInFlightUntil) return fail(CallCommand.DIAL, CallError.CALL_IN_PROGRESS)
        if (!CallPermissions.callPhoneGranted(context)) return fail(CallCommand.DIAL, CallError.PERMISSION_DENIED)
        val issuedAt = SystemClock.elapsedRealtime()
        try {
            context.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), Bundle())
        } catch (e: SecurityException) { return fail(CallCommand.DIAL, CallError.PERMISSION_DENIED)
        } catch (e: Exception) { return fail(CallCommand.DIAL, CallError.TELECOM_ERROR) }
        dialInFlightUntil = issuedAt + 10_000
        confirm(CallCommand.DIAL, 10_000) { lastCallAddedAt >= issuedAt }
    }

    // ------------------------------------------------------------------ internals

    private fun requireRole(command: String): Boolean {
        if (controlAvailable) return true
        fail(command, CallError.NO_ROLE); return false
    }

    private fun fail(command: String, error: String) =
        // Stage 30: one listener that throws must not stop the others (this also runs from a main-thread timer).
        for (l in listeners) {
            try { l.onCommandFailed(command, error, snapshot) } catch (e: Exception) { BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LISTENER, "call command-failed listener", e, command) }
        }

    private fun confirm(command: String, timeoutMs: Long, done: () -> Boolean) {
        main.postDelayed({ if (!done()) fail(command, CallError.NOT_CONFIRMED) }, timeoutMs)
    }

    private fun primary(): Call? = calls.minByOrNull { rank(it.state) }

    private fun recompute() = emit(primary()?.let { toSnapshot(it) } ?: CallSnapshot.IDLE)

    private fun emit(s: CallSnapshot) {
        if (s == snapshot) return
        snapshot = s
        // Stage 30: this runs inside Telecom's callbacks (InCallService); an exception from any listener would end the app.
        for (l in listeners) {
            try { l.onSnapshotChanged(s) } catch (e: Exception) { BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LISTENER, "call snapshot listener", e) }
        }
    }

    private fun toSnapshot(call: Call): CallSnapshot {
        val state = mapState(call.state)
        val d = call.details
        return CallSnapshot(
            state = state,
            number = numberOf(d),
            direction = directions[call] ?: detectDirection(call),
            disconnectCause = if (state == CallState.DISCONNECTED) causeName(d?.disconnectCause) else null,
            callId = sessionIds[call]
        )
    }

    /** Only a real tel: number that Android allows us to see; null otherwise. Never guessed. */
    private fun numberOf(d: Call.Details?): String? {
        val handle = d?.handle ?: return null
        if (d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED) return null
        if (handle.scheme != PhoneAccount.SCHEME_TEL) return null
        return handle.schemeSpecificPart?.takeIf { it.isNotEmpty() }
    }

    private fun detectDirection(call: Call): CallDirection? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (call.details?.callDirection) {
                Call.Details.DIRECTION_INCOMING -> return CallDirection.INCOMING
                Call.Details.DIRECTION_OUTGOING -> return CallDirection.OUTGOING
            }
        }
        // API 26-28 (or direction unknown): infer only from a state that implies it.
        return when (call.state) {
            Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallDirection.INCOMING
            Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_DIALING, Call.STATE_SELECT_PHONE_ACCOUNT -> CallDirection.OUTGOING
            else -> null
        }
    }

    private fun isRinging(state: Int) = state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING

    private fun mapState(state: Int): CallState = when (state) {
        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallState.RINGING
        Call.STATE_DIALING -> CallState.DIALING
        Call.STATE_ACTIVE -> CallState.ACTIVE
        Call.STATE_HOLDING -> CallState.HOLDING
        Call.STATE_DISCONNECTING -> CallState.DISCONNECTING
        Call.STATE_DISCONNECTED -> CallState.DISCONNECTED
        Call.STATE_SELECT_PHONE_ACCOUNT -> CallState.SELECT_ACCOUNT
        else -> CallState.CONNECTING   // NEW, CONNECTING, PULLING_CALL, AUDIO_PROCESSING
    }

    /** Which call to report when several exist (call waiting): ringing first, then active, dialing, held. */
    private fun rank(state: Int) = when (state) {
        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> 0
        Call.STATE_ACTIVE -> 1
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_NEW -> 2
        Call.STATE_HOLDING -> 3
        Call.STATE_DISCONNECTING -> 4
        Call.STATE_DISCONNECTED -> 5
        else -> 6
    }

    private fun causeName(c: DisconnectCause?): String? = when (c?.code) {
        null -> null
        DisconnectCause.LOCAL -> "LOCAL"
        DisconnectCause.REMOTE -> "REMOTE"
        DisconnectCause.REJECTED -> "REJECTED"
        DisconnectCause.MISSED -> "MISSED"
        DisconnectCause.BUSY -> "BUSY"
        DisconnectCause.CANCELED -> "CANCELED"
        DisconnectCause.ERROR -> "ERROR"
        DisconnectCause.RESTRICTED -> "RESTRICTED"
        DisconnectCause.ANSWERED_ELSEWHERE -> "ANSWERED_ELSEWHERE"
        DisconnectCause.OTHER -> "OTHER"
        else -> "UNKNOWN"
    }

    private companion object {
        /** Optional leading +, then digits. USSD/MMI codes (* and #) and pause/wait characters are refused, not rewritten. */
        val DIALABLE = Regex("^\\+?[0-9]{1,32}$")
    }
}
