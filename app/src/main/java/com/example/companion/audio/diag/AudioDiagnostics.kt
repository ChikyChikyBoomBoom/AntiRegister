package com.example.companion.audio.diag

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.CallAudioState
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Owns the Stage 4 experiment state (kept in memory only, lives with the Application). Runs one experiment at a time on
 * a background thread. Knows nothing about Bluetooth. Audio is analysed in memory and never stored.
 */
class AudioDiagnostics(private val context: Context, private val callSnapshot: () -> CallSnapshot) {

    interface Listener {
        fun onChanged() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()
    private val probes = ArrayList<ProbeResult>()
    private val injections = ArrayList<InjectionRun>()
    private val modeTest = ArrayList<String>()

    @Volatile var running = false; private set
    @Volatile var status = "Idle"; private set
    /** Index of a tone run waiting for the human answer "did the remote hear it?", or -1. */
    @Volatile var pendingAnswer = -1; private set
    @Volatile private var cancelFlag = false

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun changed() { main.post { listeners.forEach { it.onChanged() } } }

    fun snapshot(): DiagSnapshot = synchronized(lock) { DiagSnapshot(probes.toList(), injections.toList(), modeTest.toList()) }

    fun cancel() { cancelFlag = true }

    fun clear() {
        if (running) return
        synchronized(lock) { probes.clear(); injections.clear(); modeTest.clear() }
        pendingAnswer = -1
        changed()
    }

    private fun callActive() = callSnapshot().state == CallState.ACTIVE

    private fun currentContext(): RunContext {
        val am = context.getSystemService(AudioManager::class.java)
        val route = RouteControl.currentRoute()
        val speaker = if (route != null) (route and CallAudioState.ROUTE_SPEAKER) != 0 else am.isSpeakerphoneOn
        return RunContext(callActive(), AudioModeProbe.modeName(am.mode), RouteControl.routeName(route), speaker)
    }

    private fun submit(label: String, block: () -> Unit): Boolean {
        if (running) return false
        running = true; cancelFlag = false; status = label
        changed()
        exec.execute {
            try { block() } catch (t: Throwable) { status = "Error: ${t.javaClass.simpleName}: ${t.message}" }
            finally {
                running = false
                if (!status.startsWith("Error")) status = if (cancelFlag) "Stopped" else "Idle"
                changed()
            }
        }
        return true
    }

    /** Probes every source for [PROBE_MS] each. The humans must keep the described conversation pattern all the time. */
    fun runPhase(phase: Phase) = submit("Phase: ${phase.label}") {
        for (src in AudioSources.ALL) {
            if (cancelFlag) break
            status = "${phase.label}: ${src.name} ..."; changed()
            val r = AudioProbe.run(context, src, phase, PROBE_MS, currentContext(), ::callActive) { cancelFlag }
            synchronized(lock) {
                probes.removeAll { it.phase == phase && it.source.name == src.name }
                probes.add(r)
            }
            changed()
            SystemClock.sleep(150)
        }
    }

    fun runToneTest(voiceUsage: Boolean) = submit("Tone test") {
        val usage = if (voiceUsage) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val content = if (voiceUsage) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC
        val variant = if (voiceUsage) Evaluator.VAR_VOICE else Evaluator.VAR_MEDIA
        val run = ToneInjection.run(context, variant, usage, content, currentContext(), ::callActive, cancelled = { cancelFlag })
        val idx = synchronized(lock) { injections.add(run); injections.size - 1 }
        pendingAnswer = idx
    }

    fun answerRemoteHeard(index: Int, heard: Heard) {
        synchronized(lock) {
            if (index in injections.indices) injections[index] = injections[index].copy(remoteHeard = heard)
        }
        if (pendingAnswer == index) pendingAnswer = -1
        changed()
    }

    /** Only outside a call (a failed restore during a live call could disturb its audio). */
    fun runModeWriteTest(): Boolean {
        if (callSnapshot().state != CallState.IDLE) return false
        return submit("Audio mode write test") {
            val lines = AudioModeProbe.writeTest(context.getSystemService(AudioManager::class.java))
            synchronized(lock) { modeTest.clear(); modeTest.addAll(lines) }
        }
    }

    companion object { const val PROBE_MS = 3000 }
}
