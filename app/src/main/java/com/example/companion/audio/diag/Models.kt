package com.example.companion.audio.diag

/** Stage 4 diagnostic data model. Pure data, no Android calls. */

enum class Phase(val label: String) {
    QUIET("Quiet"),
    REMOTE_ONLY("Remote speaks"),
    LOCAL_ONLY("Local speaks")
}

/** CALL_PATH sources are meant to carry telephony audio; MICROPHONE_PATH sources are microphone variants. */
enum class SourceKind { CALL_PATH, MICROPHONE_PATH }

class TestSource(val name: String, val id: Int, val kind: SourceKind, val doc: String)

enum class Signal { NOT_RECORDED, SILENT, LOW_LEVEL, ACTIVE }

/** Conditions at the time of one experiment. callActive = Telecom reported ACTIVE at start AND at end. */
data class RunContext(
    val callActive: Boolean,
    val audioMode: String,
    val route: String,
    val speaker: Boolean?   // null = unknown
)

data class ProbeResult(
    val source: TestSource,
    val phase: Phase?,
    val initOk: Boolean,
    val initDetail: String,
    val sampleRate: Int,
    val recordingOk: Boolean,
    val recordingDetail: String,
    val avgDb: Double,
    val peakDb: Double,
    val zeroFraction: Double,
    val framesRead: Long,
    val readErrors: Int,
    val clientSilenced: Boolean?,
    val routedInput: String?,
    val signal: Signal,
    val context: RunContext
)

enum class Heard { YES, NO, UNSURE }

data class InjectionRun(
    val variant: String,
    val toneHz: Int,
    val durationMs: Int,
    val context: RunContext,
    val trackStarted: Boolean,
    val trackDetail: String,
    val trackRouted: String?,
    val micCheck: String,
    val micToneDetected: Boolean?,   // null = could not verify (mic unavailable / silent)
    val micToneRatio: Double?,
    val remoteHeard: Heard?          // answered by the human afterwards
)

data class DiagSnapshot(
    val probes: List<ProbeResult>,
    val injections: List<InjectionRun>,
    val modeTest: List<String>
)
