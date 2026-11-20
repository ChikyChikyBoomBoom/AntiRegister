package com.example.companion.audio.diag

enum class Avail { UNKNOWN, AVAILABLE, UNAVAILABLE }
class Capability(val status: Avail, val detail: String)

class SourceEval(
    val source: TestSource,
    val quiet: ProbeResult?, val remote: ProbeResult?, val local: ProbeResult?,
    val remoteResp: Boolean?, val localResp: Boolean?,
    val verdict: String,
    val all: List<ProbeResult>
)

class Evaluation(
    val sources: List<SourceEval>,
    val remote: Capability, val local: Capability, val injection: Capability,
    val result: String, val resultText: String
)

/**
 * Turns raw observations into capability statements. "AVAILABLE" is only produced from observed signal that
 * follows who was speaking, never from a successful API call. Thresholds are heuristics and are printed in the report.
 */
object Evaluator {
    const val RESPONSE_DB = 10.0      // talk phase must be this much louder than the same source's quiet phase
    const val MIN_TALK_DB = -55.0     // ...and above this absolute level
    const val LEAK_MARGIN_DB = 15.0   // call-path gain must beat microphone-path gain by this much to rule out leakage
    const val VAR_VOICE = "USAGE_VOICE_COMMUNICATION"
    const val VAR_MEDIA = "USAGE_MEDIA"

    fun evaluate(s: DiagSnapshot): Evaluation {
        val evs = AudioSources.ALL.map { src ->
            val mine = s.probes.filter { it.source.name == src.name }
            val q = mine.lastOrNull { it.phase == Phase.QUIET }
            val r = mine.lastOrNull { it.phase == Phase.REMOTE_ONLY }
            val l = mine.lastOrNull { it.phase == Phase.LOCAL_ONLY }
            val rr = responds(r, q)
            val lr = responds(l, q)
            SourceEval(src, q, r, l, rr, lr, verdict(mine, rr, lr), mine)
        }
        val remote = remoteCap(evs)
        val local = localCap(evs)
        val inj = injectionCap(s.injections)

        val result: String
        val text: String
        when {
            remote.status == Avail.UNKNOWN || local.status == Avail.UNKNOWN ->
                { result = "INCOMPLETE"; text = "Not enough valid observations yet. Run Quiet, Remote and Local phases during an ACTIVE call." }
            remote.status == Avail.AVAILABLE && local.status == Avail.AVAILABLE && inj.status == Avail.AVAILABLE ->
                { result = "A"; text = "Result A - Full two-way audio is available on THIS device (downlink observed, local voice observed, injection confirmed by the remote person with no acoustic path)." }
            remote.status == Avail.AVAILABLE && inj.status == Avail.UNKNOWN ->
                { result = "INCOMPLETE"; text = "Downlink was observed but injection has not been tested conclusively. Run both tone tests with the earpiece route." }
            remote.status == Avail.AVAILABLE ->
                { result = "PARTIAL"; text = "NOT Result A: downlink observed, but local voice and/or injection was not demonstrated. Does not fit A-D cleanly; treat as 'receive only' until proven otherwise." }
            remote.status == Avail.UNAVAILABLE && local.status == Avail.AVAILABLE ->
                { result = "B"; text = "Result B - Only microphone audio is available. Cellular downlink audio was NOT accessible. The project has not solved call audio." }
            remote.status == Avail.UNAVAILABLE && local.status == Avail.UNAVAILABLE ->
                { result = "C"; text = "Result C - Cellular audio capture is blocked (neither remote nor local voice reached the app during the call)." }
            else -> { result = "INCOMPLETE"; text = "Observations do not fit any category yet." }
        }
        return Evaluation(evs, remote, local, inj, result, text)
    }

    // ------------------------------------------------------------------ per-source

    /** true/false only when both talk and quiet runs are valid (recorded, during an ACTIVE call); otherwise null. */
    private fun responds(talk: ProbeResult?, quiet: ProbeResult?): Boolean? {
        if (talk == null || quiet == null) return null
        if (!talk.recordingOk || !talk.context.callActive || !quiet.recordingOk || !quiet.context.callActive) return null
        if (talk.signal == Signal.SILENT) return false
        return talk.avgDb >= MIN_TALK_DB && talk.avgDb - quiet.avgDb >= RESPONSE_DB
    }

    private fun verdict(mine: List<ProbeResult>, rr: Boolean?, lr: Boolean?): String {
        if (mine.isEmpty()) return "NOT TESTED"
        if (mine.none { it.recordingOk }) {
            val last = mine.last()
            return "NO AUDIO - " + if (!last.initOk) "initialization failed" else "recording failed"
        }
        if (mine.none { it.context.callActive }) return "CONTROL RUN ONLY (no active call) - no call evidence"
        return when {
            rr == true && lr == true -> "MIXED - responds to remote AND local speech"
            rr == true -> "REMOTE speech only" + if (lr == null) " (local phase missing)" else ""
            lr == true -> "LOCAL speech only" + if (rr == null) " (remote phase missing)" else ""
            rr == false && lr == false ->
                if (mine.filter { it.recordingOk }.all { it.signal == Signal.SILENT }) "SILENT - digital silence in every run"
                else "NO RESPONSE to either voice (stream is not silent but does not follow speech)"
            else -> "INCOMPLETE - run Quiet, Remote and Local phases"
        }
    }

    private fun gain(e: SourceEval): Double = (e.remote?.avgDb ?: -120.0) - (e.quiet?.avgDb ?: -120.0)

    private fun reasons(evs: List<SourceEval>): String = evs.joinToString("; ") { e ->
        val p = e.all.lastOrNull()
        e.source.name + ": " + (if (p == null) "not tested" else if (!p.initOk) "init failed (${p.initDetail})" else p.recordingDetail)
    }

    // ------------------------------------------------------------------ capabilities

    private fun remoteCap(evs: List<SourceEval>): Capability {
        val call = evs.filter { it.source.kind == SourceKind.CALL_PATH }
        val mic = evs.filter { it.source.kind == SourceKind.MICROPHONE_PATH }
        val callHits = call.filter { it.remoteResp == true }
        val micHits = mic.filter { it.remoteResp == true }

        if (callHits.isNotEmpty()) {
            val best = callHits.maxByOrNull { gain(it) } ?: callHits.first()
            val micBest = micHits.maxOfOrNull { gain(it) }
            val names = callHits.joinToString { it.source.name }
            if (micBest != null && gain(best) - micBest < LEAK_MARGIN_DB)
                return Capability(Avail.UNKNOWN,
                    "Remote speech was seen on $names, but microphone sources heard it at a similar level " +
                    "(call-path gain ${f(gain(best))} dB vs microphone gain ${f(micBest)} dB). Cannot separate a real downlink " +
                    "from acoustic leakage or source aliasing. Repeat with the earpiece pressed to the ear / lower volume.")
            return Capability(Avail.AVAILABLE,
                "Remote speech (while the local user was silent) was observed on $names; microphone-path sources " +
                (if (micHits.isEmpty()) "did not hear it." else "heard it much weaker."))
        }
        if (call.none { it.remote != null })
            return Capability(Avail.UNKNOWN, "Remote phase has not been run.")
        if (call.none { it.remote?.context?.callActive == true })
            return Capability(Avail.UNKNOWN, "No ACTIVE call during the remote phase: control run only, not call evidence.")

        val usable = call.filter { it.remote?.recordingOk == true && it.remote?.context?.callActive == true }
        val micNote = if (micHits.isNotEmpty())
            " Remote voice was audible only on microphone-path sources (" + micHits.joinToString { it.source.name } +
            ") i.e. acoustically: MICROPHONE ONLY - this is NOT cellular downlink capture." else ""
        if (usable.isEmpty())
            return Capability(Avail.UNAVAILABLE, "No call-path source could be initialized/recorded: " + reasons(call) + "." + micNote)
        if (usable.all { it.remote?.signal == Signal.SILENT })
            return Capability(Avail.UNAVAILABLE,
                "Call-path sources (" + usable.joinToString { it.source.name } + ") recorded but returned digital silence " +
                "while the remote person was speaking." + micNote)
        return Capability(Avail.UNKNOWN,
            "Call-path sources delivered non-silent data that did not clearly follow the remote speech. " +
            "Repeat the Quiet and Remote phases (remote person must talk continuously)." + micNote)
    }

    private fun localCap(evs: List<SourceEval>): Capability {
        val hits = evs.filter { it.localResp == true }
        if (hits.isNotEmpty()) {
            val m = hits.filter { it.source.kind == SourceKind.MICROPHONE_PATH }.joinToString { it.source.name }
            val cp = hits.filter { it.source.kind == SourceKind.CALL_PATH }.joinToString { it.source.name }
            return Capability(Avail.AVAILABLE,
                "Local speech (remote silent) observed on" +
                (if (m.isNotEmpty()) " microphone-path: $m" else "") + (if (cp.isNotEmpty()) " call-path: $cp" else "") + ".")
        }
        if (evs.none { it.local != null })
            return Capability(Avail.UNKNOWN, "Local phase has not been run.")
        if (evs.none { it.local?.context?.callActive == true })
            return Capability(Avail.UNKNOWN, "No ACTIVE call during the local phase: control run only, not call evidence.")
        val usable = evs.filter { it.local?.recordingOk == true && it.local?.context?.callActive == true }
        if (usable.isEmpty())
            return Capability(Avail.UNAVAILABLE, "No source could be initialized/recorded during the call: " + reasons(evs))
        if (usable.all { it.local?.signal == Signal.SILENT })
            return Capability(Avail.UNAVAILABLE,
                "All sources that recorded returned digital silence while the local user spoke " +
                "(system may be silencing background/secondary capture during the call).")
        return Capability(Avail.UNKNOWN,
            "Sources delivered non-silent data that did not clearly follow the local speech. Repeat Quiet and Local phases (speak continuously, close to the microphone).")
    }

    private fun injectionCap(inj: List<InjectionRun>): Capability {
        if (inj.isEmpty()) return Capability(Avail.UNKNOWN, "No tone test has been run.")
        val answered = inj.filter { it.remoteHeard != null && it.context.callActive }
        if (answered.isEmpty())
            return Capability(Avail.UNKNOWN, "Tone test(s) run, but no remote answer yet or the call was not ACTIVE.")
        fun clean(r: InjectionRun) = r.trackStarted && r.context.speaker == false && r.micToneDetected == false
        val yesClean = answered.filter { it.remoteHeard == Heard.YES && clean(it) }
        if (yesClean.isNotEmpty())
            return Capability(Avail.AVAILABLE,
                "Remote person reported hearing the tone (" + yesClean.joinToString { it.variant } +
                ") with the earpiece route and no tone detected on the local microphone.")
        val noClean = answered.filter { it.remoteHeard == Heard.NO && clean(it) }
        val yesDirty = answered.filter { it.remoteHeard == Heard.YES && !clean(it) }
        val noVariants = noClean.map { it.variant }.toSet()
        if (noVariants.containsAll(setOf(VAR_VOICE, VAR_MEDIA)))
            return Capability(Avail.UNAVAILABLE,
                "With a clean earpiece route the remote person heard nothing for either playback usage." +
                if (yesDirty.isNotEmpty()) " The remote heard the tone only when an acoustic path existed (speaker route or leakage to the mic): ACOUSTIC COUPLING ONLY, not injection." else "")
        if (yesDirty.isNotEmpty())
            return Capability(Avail.UNKNOWN,
                "Remote heard the tone, but an acoustic path (speaker route, leakage to the mic, or unverifiable mic) could not be excluded. Repeat with earpiece route.")
        return Capability(Avail.UNKNOWN, "Run both tone tests (voice-communication and media usage) with the earpiece route and answer the question.")
    }

    private fun f(d: Double) = String.format(java.util.Locale.US, "%.1f", d)
}
