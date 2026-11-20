package com.example.companion.ui

import android.content.Context
import android.content.Intent
import com.example.companion.bluetooth.BluetoothDiagnostics
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.ClientCallController
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import com.example.companion.protocol.CallState

/**
 * Main Phone only: opens [InCallActivity] when the SIM Phone reports a call that is ringing, being placed or connected.
 *
 * Opens the screen only while this app is visible (Android blocks background activity starts, and this stage adds no
 * notification). It fires on the change "no live call" -> "live call", and again when the app comes to the foreground
 * while a call is still RINGING, so an incoming call is not missed behind another screen of this app.
 * It holds no call state of its own beyond that one edge flag; [ClientCallController] stays the source of truth.
 */
class CallScreenLauncher(
    private val context: Context,
    private val prefs: AppPrefs,
    private val calls: ClientCallController,
    private val isAppVisible: () -> Boolean
) : ClientCallController.Listener {

    /** Last KNOWN state. Not reset when the link drops (snapshot null), so a reconnect re-reporting the same call does not reopen the screen. */
    private var lastKnown: CallState? = null
    /** Stage 27: session id of that last known report (null = none / older SIM Phone). */
    private var lastKnownId: String? = null

    override fun onCallUiChanged() {
        val snap = calls.snapshot ?: return                  // unknown (link down / not synced yet): nothing changed that we know of
        val current = snap.state
        val started = CallScreenState.shouldOpen(lastKnown, current, lastKnownId, snap.callId)
        lastKnown = current; lastKnownId = snap.callId
        if (started) open()
    }

    /** Called by [com.example.companion.app.CompanionApp] when the first activity of the app becomes visible. */
    fun onAppVisible() {
        if (calls.snapshot?.state == CallState.RINGING) open()
    }

    private fun open() {
        if (!CallFeatures.isCallingEnabled()) return   // Stage 7: the call screen is never opened by a call report
        if (prefs.role != Role.MAIN_PHONE || !isAppVisible()) return
        // CLEAR_TOP + SINGLE_TOP: reuse one call screen instead of stacking a second one.
        // Stage 30: this runs inside a call-report callback; a refused activity start must not end the app.
        try {
            context.startActivity(
                Intent(context, InCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LISTENER, "open call screen", e)
        }
    }
}
