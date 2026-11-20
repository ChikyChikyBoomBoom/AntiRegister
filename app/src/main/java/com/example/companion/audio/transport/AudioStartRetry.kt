package com.example.companion.audio.transport

/**
 * Stage 33B: how one AUDIO_START attempt ended, as seen by the sender. Android-free.
 * [AudioTransport] emits the first five; [AudioStartRetry] itself adds [Rejected] and [Inactive].
 */
sealed class AudioStartOutcome {
    /** The receiver answered AUDIO_READY and the local source started: the stream is running. */
    object Ready : AudioStartOutcome()
    /** The receiver was ready, but the LOCAL source failed to initialize / start ([reason] from `AudioSource.start`). The only outcome that is a genuine capture failure. */
    class SourceFailed(val reason: String) : AudioStartOutcome()
    /** The receiver answered AUDIO_STOP while negotiating. [reason] null = no text. [AudioTransportMessages.NOT_LISTENING] = "not ready yet". */
    class Refused(val reason: String?) : AudioStartOutcome()
    /** The receiver did not answer within the transport's negotiate timeout. Not a refusal, not retried here. */
    object TimedOut : AudioStartOutcome()
    /** Bluetooth went down. */
    object LinkDown : AudioStartOutcome()
    /** The transport refused to send AUDIO_START at all (e.g. "Already sending", "No role selected"). */
    class Rejected(val reason: String) : AudioStartOutcome()
    /** The owner said the backend is no longer active. */
    object Inactive : AudioStartOutcome()

    /**
     * What this outcome means for the Stage 33A [CellularConfigFallback]; null = it says nothing about the configuration.
     * Only [SourceFailed] is an initialization failure. [Refused] (NOT_LISTENING included) is never one.
     */
    fun toConfigEvent(): CellularConfigEvent? = when (this) {
        is Ready -> CellularConfigEvent.Started
        is SourceFailed -> CellularConfigEvent.InitFailed(reason)
        is Refused -> CellularConfigEvent.ReceiverRefused(reason)
        else -> null
    }
}

/** What is sent for one attempt. The SAME object is used for every readiness retry, so source and configuration never change. */
data class AudioStartRequest(val source: AudioSource, val config: AudioConfig)

/** Cancellable one-shot timer. */
fun interface RetryTimer { fun cancel() }

/** Runs [task] once after [delayMs] on the thread that owns the retry (main thread in production). Fakeable in JVM tests. */
fun interface RetryScheduler { fun postDelayed(delayMs: Long, task: Runnable): RetryTimer }

/**
 * Stage 33B: "AUDIO_START was answered with NOT_LISTENING -> send the exact same request again after [retryDelayMs]".
 *
 * - Retries ONLY on a [AudioStartOutcome.Refused] whose reason satisfies [AudioTransportMessages.isNotListening]. Every other outcome
 *   (Ready, SourceFailed, other refusal, timeout, link down, rejected, inactive) ends the run and is reported once to the listener.
 * - Never changes the request: no other sample rate, no other source. Fallback (Stage 33A) is not touched; see [AudioStartOutcome.toConfigEvent].
 * - No cap on the number of retries: it goes on while [isActive] and [isLinkUp] stay true and nobody cancels.
 * - At most ONE run at a time: [begin] returns false while one is running.
 * - [cancel] is immediate: the pending timer is cancelled, an unanswered attempt is aborted with [abortAttempt], and NO listener callback follows.
 *   A stale timer or a late outcome of a cancelled / finished run is ignored.
 * - No threads, no coroutines: one injected timer. Main thread only (like AudioTransport); not thread-safe.
 *
 * Feed it outcomes with [onOutcome] (see [AudioTransport.StartListener]).
 */
class AudioStartRetry(
    private val scheduler: RetryScheduler,
    private val isLinkUp: () -> Boolean,
    private val isActive: () -> Boolean,
    /** Sends AUDIO_START for the request. Returns null when negotiation started, otherwise why not (as `AudioTransport.startSending`). */
    private val startAttempt: (AudioStartRequest) -> String?,
    /** Called by [cancel] when an attempt is still waiting for the receiver's answer (must tear that negotiation down). */
    private val abortAttempt: () -> Unit = {},
    val retryDelayMs: Long = RETRY_DELAY_MS
) {
    interface Listener {
        /** The receiver said NOT_LISTENING; attempt number [afterAttempt] (1-based) will be repeated after the delay. */
        fun onRetryScheduled(afterAttempt: Int, delayMs: Long) {}
        /** The run ended; called exactly once, never after [cancel]. The run is already over, so [begin] may be called again from here. */
        fun onFinished(outcome: AudioStartOutcome, attempts: Int)
    }

    private class Run(val request: AudioStartRequest, val listener: Listener) {
        var attempts = 0
        var awaiting = false
        var timer: RetryTimer? = null
    }

    private var current: Run? = null

    val isRunning: Boolean get() = current != null
    /** Attempts sent by the current run (0 when none). */
    val attempts: Int get() = current?.attempts ?: 0

    /** Starts a run and sends the first AUDIO_START now. False (and nothing happens) when a run is already in progress. May call the listener before returning (e.g. link already down). */
    fun begin(request: AudioStartRequest, listener: Listener): Boolean {
        if (current != null) return false
        val run = Run(request, listener)
        current = run
        attempt(run)
        return true
    }

    /** Report how the transport's current attempt ended. Ignored when no run is waiting for an answer (cancelled, finished, or waiting for the retry timer). */
    fun onOutcome(outcome: AudioStartOutcome) {
        val run = current ?: return
        if (outcome is AudioStartOutcome.LinkDown) { finish(run, outcome); return }   // also while waiting for the timer
        if (!run.awaiting) return
        run.awaiting = false
        if (outcome is AudioStartOutcome.Refused && AudioTransportMessages.isNotListening(outcome.reason)) schedule(run) else finish(run, outcome)
    }

    /** Stops the run now. Safe to call at any time, repeatedly. No callbacks afterwards. A stream that already started is NOT stopped (the owner does that). */
    fun cancel() {
        val run = current ?: return
        current = null
        run.timer?.cancel(); run.timer = null
        if (run.awaiting) { run.awaiting = false; runCatching { abortAttempt() } }
    }

    private fun attempt(run: Run) {
        if (current !== run) return
        if (!isActive()) return finish(run, AudioStartOutcome.Inactive)
        if (!isLinkUp()) return finish(run, AudioStartOutcome.LinkDown)
        run.attempts++
        run.awaiting = true                       // before sending, so an answer delivered inside startAttempt is not lost
        val err = try { startAttempt(run.request) } catch (t: Throwable) { "${t.javaClass.simpleName}: ${t.message}" }
        if (err != null && current === run && run.awaiting) { run.awaiting = false; finish(run, AudioStartOutcome.Rejected(err)) }
    }

    private fun schedule(run: Run) {
        val n = run.attempts
        run.timer = scheduler.postDelayed(retryDelayMs) {
            run.timer = null
            attempt(run)                          // no-op when the run was cancelled or finished meanwhile
        }
        if (current === run) run.listener.onRetryScheduled(n, retryDelayMs)
    }

    private fun finish(run: Run, outcome: AudioStartOutcome) {
        if (current !== run) return
        current = null
        run.timer?.cancel(); run.timer = null
        run.awaiting = false
        run.listener.onFinished(outcome, run.attempts)
    }

    companion object {
        const val RETRY_DELAY_MS = 500L
    }
}
