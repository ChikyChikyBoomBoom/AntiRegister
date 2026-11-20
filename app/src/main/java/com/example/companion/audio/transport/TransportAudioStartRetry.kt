package com.example.companion.audio.transport

/**
 * Stage 33B: binds an [AudioStartRetry] to the ONE shared [AudioTransport] (`audioTest.transport`). Creates no link, no listener on the
 * link and no second transport. Nothing calls it yet (Stage 33C backend).
 *
 * It takes the transport's single [AudioTransport.startListener] slot, so create at most one per transport.
 * The caller decides [isActive] (e.g. "the call backend is still running") and calls `begin` / `cancel` on the result.
 */
object TransportAudioStartRetry {
    fun create(transport: AudioTransport, isActive: () -> Boolean, scheduler: RetryScheduler = HandlerRetryScheduler()): AudioStartRetry {
        val retry = AudioStartRetry(
            scheduler = scheduler,
            isLinkUp = { transport.isLinkUp },
            isActive = isActive,
            startAttempt = { transport.startSending(it.source, it.config) },
            abortAttempt = { transport.stopSending(null) }          // drop an unanswered negotiation and clear its automatic resume
        )
        transport.startListener = object : AudioTransport.StartListener {
            override fun onStartOutcome(outcome: AudioStartOutcome) = retry.onOutcome(outcome)
        }
        return retry
    }
}
