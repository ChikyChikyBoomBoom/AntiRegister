package com.example.companion.audio.transport

/**
 * Stage 33A: one cellular capture configuration = which platform source + which sample rate.
 * Pure data, Android-free, no Bluetooth. [toAudioConfig] gives the [AudioConfig] (PCM16 mono) to hand to [CellularDownlinkSource].
 */
data class CellularAudioConfiguration(val source: CellularCaptureSource, val sampleRate: Int) {
    val label: String get() = "${source.name} @ $sampleRate Hz"
    fun toAudioConfig(frameMs: Int = AudioConfig.DEFAULT_FRAME_MS): AudioConfig = AudioConfig(sampleRate = sampleRate, frameMs = frameMs)

    companion object {
        /**
         * The fixed attempt order (Stage 33A). Each entry is tried only after the previous one genuinely failed to initialize / start:
         * 1. VOICE_DOWNLINK 16 kHz, 2. VOICE_CALL 16 kHz, 3. VOICE_DOWNLINK 8 kHz, 4. VOICE_CALL 8 kHz.
         * Whether any of them works on the POCO F3 / Xiaomi firmware is UNVERIFIED.
         */
        val ORDER: List<CellularAudioConfiguration> = listOf(
            CellularAudioConfiguration(CellularCaptureSource.VOICE_DOWNLINK, 16_000),
            CellularAudioConfiguration(CellularCaptureSource.VOICE_CALL, 16_000),
            CellularAudioConfiguration(CellularCaptureSource.VOICE_DOWNLINK, 8_000),
            CellularAudioConfiguration(CellularCaptureSource.VOICE_CALL, 8_000)
        )
    }
}

/** What happened to the configuration currently being tried. Only [InitFailed] can cause a fallback. */
sealed class CellularConfigEvent {
    /** The configuration initialized and started (source `start()` returned null). */
    object Started : CellularConfigEvent()
    /** The configuration genuinely failed to initialize or start locally ([reason] = text returned by the source). */
    class InitFailed(val reason: String) : CellularConfigEvent()
    /**
     * The receiving phone refused or did not accept the stream (AUDIO_STOP reason). This is receiver readiness / protocol,
     * never an audio-quality failure, so it never changes the configuration. [AudioTransportMessages.NOT_LISTENING] is the usual case.
     */
    class ReceiverRefused(val reason: String?) : CellularConfigEvent()
}

sealed class CellularConfigDecision {
    /** Keep using [config]. [notListening] = the receiver said it is not listening (retry policy belongs to a later stage). */
    class Keep(val config: CellularAudioConfiguration, val notListening: Boolean = false) : CellularConfigDecision()
    /** The previous configuration failed; try [next]. */
    class Fallback(val next: CellularAudioConfiguration) : CellularConfigDecision()
    /** Every configuration failed to initialize / start. [attempts] holds each reason in attempt order. */
    class Exhausted(val attempts: List<CellularConfigAttempt>) : CellularConfigDecision()
}

data class CellularConfigAttempt(val config: CellularAudioConfiguration, val reason: String)

/** Result of [CellularConfigFallback.selectFirst]. */
sealed class CellularConfigResult {
    class Selected(val config: CellularAudioConfiguration, val failedBefore: List<CellularConfigAttempt>) : CellularConfigResult()
    class AllFailed(val attempts: List<CellularConfigAttempt>) : CellularConfigResult()
}

/**
 * Stage 33A: fallback selection over [order] (default [CellularAudioConfiguration.ORDER]). Pure and Android-free; knows nothing about
 * Bluetooth, AudioTransport or call state. Not thread-safe (one owner, like the future backend on the main thread).
 *
 * Rules: starts at the first configuration; moves to the next ONLY on [CellularConfigEvent.InitFailed]; [CellularConfigEvent.Started]
 * and [CellularConfigEvent.ReceiverRefused] (e.g. NOT_LISTENING) never move it.
 */
class CellularConfigFallback(private val order: List<CellularAudioConfiguration> = CellularAudioConfiguration.ORDER) {
    private var index = 0
    private val failed = ArrayList<CellularConfigAttempt>()
    private var exhausted = false

    /** The configuration to try / currently in use, or null once every configuration failed. */
    val current: CellularAudioConfiguration? get() = if (exhausted) null else order.getOrNull(index)
    val failedAttempts: List<CellularConfigAttempt> get() = failed.toList()

    fun onEvent(event: CellularConfigEvent): CellularConfigDecision {
        val cur = current ?: return CellularConfigDecision.Exhausted(failedAttempts)
        return when (event) {
            is CellularConfigEvent.Started -> CellularConfigDecision.Keep(cur)
            is CellularConfigEvent.ReceiverRefused ->
                CellularConfigDecision.Keep(cur, notListening = AudioTransportMessages.isNotListening(event.reason))
            is CellularConfigEvent.InitFailed -> {
                failed += CellularConfigAttempt(cur, event.reason)
                if (index + 1 < order.size) { index++; CellularConfigDecision.Fallback(order[index]) }
                else { exhausted = true; CellularConfigDecision.Exhausted(failedAttempts) }
            }
        }
    }

    /** Back to the first configuration with no history (a new call / a new start). */
    fun reset() { index = 0; failed.clear(); exhausted = false }

    /**
     * Convenience: tries configurations in order with [attempt] (null = initialized and started, otherwise the failure reason;
     * an exception counts as a failure) and stops at the first success.
     */
    fun selectFirst(attempt: (CellularAudioConfiguration) -> String?): CellularConfigResult {
        reset()
        while (true) {
            val cfg = current ?: return CellularConfigResult.AllFailed(failedAttempts)
            val reason = try { attempt(cfg) } catch (t: Throwable) { "${t.javaClass.simpleName}: ${t.message}" }
            if (reason == null) { onEvent(CellularConfigEvent.Started); return CellularConfigResult.Selected(cfg, failedAttempts) }
            if (onEvent(CellularConfigEvent.InitFailed(reason)) is CellularConfigDecision.Exhausted) return CellularConfigResult.AllFailed(failedAttempts)
        }
    }
}
