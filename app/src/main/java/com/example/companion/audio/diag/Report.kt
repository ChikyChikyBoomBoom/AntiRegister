package com.example.companion.audio.diag

import android.content.Context
import java.util.Locale

/** Plain-text report: readable on screen and copy/paste-able for comparing phones. */
object Report {
    private fun f(d: Double) = if (d <= -119.9) "-inf" else String.format(Locale.US, "%.1f", d)

    fun build(c: Context, snap: DiagSnapshot, callState: String): String {
        val ev = Evaluator.evaluate(snap)
        val sb = StringBuilder()
        sb.appendLine("CELLULAR AUDIO CAPABILITY")
        sb.appendLine("(call state now: $callState)")
        sb.appendLine()
        fun cap(title: String, x: Capability) {
            sb.appendLine("$title:"); sb.appendLine("  ${x.status.name}"); sb.appendLine("  ${x.detail}")
        }
        cap("Remote Call Audio", ev.remote)
        cap("Local Microphone", ev.local)
        cap("Audio Injection", ev.injection)
        sb.appendLine()
        for (name in listOf("VOICE_CALL", "VOICE_UPLINK", "VOICE_DOWNLINK")) {
            val e = ev.sources.first { it.source.name == name }
            val init = when {
                e.all.isEmpty() -> "NOT TESTED"
                e.all.any { it.initOk } -> "INIT SUCCESS (${e.all.first { it.initOk }.sampleRate} Hz)"
                else -> "INIT FAILED"
            }
            sb.appendLine("$name: $init")
        }
        sb.appendLine()
        sb.appendLine("RESULT: ${ev.result}")
        sb.appendLine(ev.resultText)
        sb.appendLine("Result D (depends on manufacturer / Android version) cannot be decided on one phone: " +
            "compare the RESULT= lines of reports from several different phones.")
        sb.appendLine("Thresholds: talk >= ${Evaluator.RESPONSE_DB} dB above quiet and >= ${Evaluator.MIN_TALK_DB} dBFS; " +
            "leak margin ${Evaluator.LEAK_MARGIN_DB} dB.")
        sb.appendLine()
        sb.appendLine("=== PER SOURCE ===")
        for (e in ev.sources) {
            sb.appendLine()
            sb.appendLine("${e.source.name}  [${if (e.source.kind == SourceKind.CALL_PATH) "call-path" else "microphone-path"}]")
            val okInit = e.all.firstOrNull { it.initOk }
            sb.appendLine("  Initialization: " + when {
                e.all.isEmpty() -> "not tested"
                okInit != null -> "SUCCESS (${okInit.sampleRate} Hz)"
                else -> "FAILED - ${e.all.last().initDetail}"
            })
            val okRec = e.all.firstOrNull { it.recordingOk }
            sb.appendLine("  Recording: " + when {
                e.all.isEmpty() -> "not tested"
                okRec != null -> "SUCCESS"
                okInit != null -> "FAILED - ${e.all.last().recordingDetail}"
                else -> "not started (init failed)"
            })
            e.all.mapNotNull { it.routedInput }.lastOrNull()?.let { sb.appendLine("  Input routed to: $it") }
            for (p in Phase.values()) {
                val r = e.all.lastOrNull { it.phase == p } ?: continue
                if (!r.recordingOk) { sb.appendLine("  ${p.label.padEnd(14)} no data"); continue }
                sb.appendLine("  ${p.label.padEnd(14)} avg ${f(r.avgDb)} dBFS  peak ${f(r.peakDb)} dBFS  signal ${r.signal.name}" +
                    (if (r.clientSilenced == true) "  [system reports client SILENCED]" else "") +
                    (if (!r.context.callActive) "  [NO ACTIVE CALL - control]" else "") +
                    "  route=${r.context.route} mode=${r.context.audioMode}")
            }
            sb.appendLine("  Detected: ${e.verdict}")
        }
        sb.appendLine()
        sb.appendLine("=== INJECTION RUNS ===")
        if (snap.injections.isEmpty()) sb.appendLine("(none)")
        snap.injections.forEachIndexed { i, r ->
            sb.appendLine("#${i + 1} ${r.variant} ${r.toneHz} Hz ${r.durationMs} ms")
            sb.appendLine("  call active=${r.context.callActive} route=${r.context.route} mode=${r.context.audioMode}")
            sb.appendLine("  playback: started=${r.trackStarted} ${r.trackDetail} routedTo=${r.trackRouted ?: "?"}")
            sb.appendLine("  local mic: ${r.micCheck}" + (r.micToneRatio?.let { " (tone ratio ${String.format(Locale.US, "%.3f", it)})" } ?: ""))
            sb.appendLine("  remote heard it: ${r.remoteHeard?.name ?: "not answered"}")
        }
        if (snap.modeTest.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== AUDIO MODE WRITE TEST (run outside a call) ===")
            snap.modeTest.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine("=== DEVICE ===")
        DeviceInfo.lines(c).forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("RESULT=${ev.result}; remote=${ev.remote.status}; local=${ev.local.status}; injection=${ev.injection.status}; " +
            "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; sdk=${android.os.Build.VERSION.SDK_INT}")
        return sb.toString()
    }
}
