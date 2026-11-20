package com.example.companion.audio.transport

/** The platform audio sources Stage 32B may try, in this fixed order. Mapped to `MediaRecorder.AudioSource.*` only in [AndroidCellularRecorderOpener]. */
enum class CellularCaptureSource(val label: String) {
    VOICE_DOWNLINK("MediaRecorder.AudioSource.VOICE_DOWNLINK"),
    VOICE_CALL("MediaRecorder.AudioSource.VOICE_CALL")
}

/**
 * An opened and STARTED recorder (Stage 32C: also readable). Thread rules, mirroring AudioRecord:
 * [read] is called only from the capture thread and blocks; [stop] may be called from any thread and must make a blocked [read] return;
 * [release] is idempotent and is called only by the capture thread (or by the owner if the capture thread never started).
 */
interface CellularRecorder {
    /** Blocking read of up to [len] bytes into [buf] at [off]. Returns bytes read (>0), 0 = nothing yet, <0 = error code. */
    fun read(buf: ByteArray, off: Int, len: Int): Int
    /** Idempotent; must never throw. Unblocks a pending [read]. */
    fun stop()
    /** Idempotent; must never throw. Frees the recorder completely. */
    fun release()
}

/** Result of one attempt to open and start a recorder on one source. */
sealed class CellularOpenResult {
    class Opened(val recorder: CellularRecorder) : CellularOpenResult()
    /** [reason] must carry the underlying error (exception text, state codes...) for debugging. */
    class Failed(val reason: String) : CellularOpenResult()
}

/** Opens and starts a recorder for one source, or reports why it could not. Must release anything it created on failure. Fakeable in JVM tests. */
fun interface CellularRecorderOpener {
    fun open(source: CellularCaptureSource, config: AudioConfig): CellularOpenResult
}

/** One failed attempt, kept for diagnostics. */
data class CellularAttempt(val source: CellularCaptureSource, val reason: String)

sealed class CellularSelection {
    /** [recorder] is open and recording; the caller owns it and must release it. [failedBefore] = earlier sources that failed. */
    class Selected(val source: CellularCaptureSource, val recorder: CellularRecorder,
                   val failedBefore: List<CellularAttempt>) : CellularSelection()
    /** Every source failed. [attempts] holds each underlying reason in attempt order. */
    class AllFailed(val attempts: List<CellularAttempt>) : CellularSelection()
}

/**
 * Stage 32B: source selection ONLY (reading lives in [CellularDownlinkSource], Stage 32C). Tries [ORDER] one by one and stops at the first that opens and starts.
 * The next source is attempted only after the previous one failed. This class reads no audio and has no Android classes.
 * Whether either source works on the POCO F3 / Xiaomi firmware is UNVERIFIED.
 */
class CellularSourceSelector(private val opener: CellularRecorderOpener) {
    /** [order] defaults to [ORDER]; Stage 33A passes a single source to try exactly one configuration. */
    fun select(config: AudioConfig, order: List<CellularCaptureSource> = ORDER): CellularSelection {
        val failed = ArrayList<CellularAttempt>()
        for (source in order) {
            val result = try { opener.open(source, config) }
            catch (t: Throwable) { CellularOpenResult.Failed("${t.javaClass.simpleName}: ${t.message}") }
            when (result) {
                is CellularOpenResult.Opened -> return CellularSelection.Selected(source, result.recorder, failed.toList())
                is CellularOpenResult.Failed -> failed += CellularAttempt(source, result.reason)
            }
        }
        return CellularSelection.AllFailed(failed.toList())
    }

    companion object {
        /** Fixed order: downlink first, call second. */
        val ORDER: List<CellularCaptureSource> = listOf(CellularCaptureSource.VOICE_DOWNLINK, CellularCaptureSource.VOICE_CALL)
    }
}
