package com.example.companion.calls

import com.example.companion.protocol.CallState

/**
 * Stage 28. When the Main Phone asks the SIM Phone for its current call state. Pure, so it can be unit-tested on a plain JVM.
 *
 *  - Right after Bluetooth (re)connects the Main Phone asks once. The SIM Phone also pushes its state by itself, so normally the
 *    first report is already there; the request covers a push that was lost or sent before the Main Phone was listening.
 *  - Until some report has arrived it asks again after [RETRY_DELAYS_MS], then stops (an older SIM Phone build ignores the request).
 *  - While a call is live and the link is up it asks every [REFRESH_MS]: a cheap check that bounds how long a missed change can
 *    stay on screen and re-bases the call timer on the SIM Phone's own duration.
 *  - With no call (or the link down) it does not ask at all.
 */
object CallSyncPolicy {
    val RETRY_DELAYS_MS = longArrayOf(1_500, 3_000, 6_000)
    const val REFRESH_MS = 20_000L

    /** [retries] = how many repeat requests were already sent since the connect. Null = do not schedule another request. */
    fun nextDelayMs(linkUp: Boolean, reportedSinceConnect: Boolean, retries: Int, state: CallState?): Long? = when {
        !linkUp -> null
        !reportedSinceConnect -> RETRY_DELAYS_MS.getOrNull(retries)
        isLive(state) -> REFRESH_MS
        else -> null
    }

    private fun isLive(state: CallState?) = when (state) {
        CallState.RINGING, CallState.CONNECTING, CallState.SELECT_ACCOUNT,
        CallState.DIALING, CallState.ACTIVE, CallState.HOLDING, CallState.DISCONNECTING -> true
        else -> false
    }
}
