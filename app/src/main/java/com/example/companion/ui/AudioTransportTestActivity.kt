package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.audio.transport.AudioConfig
import com.example.companion.audio.transport.AudioTransport
import com.example.companion.audio.transport.JitterConfig
import com.example.companion.audio.transport.TransportStats
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.Role

/**
 * Stage 5 diagnostic: Bluetooth AUDIO TRANSPORT test (microphone -> Bluetooth -> speaker). This is NOT cellular call
 * audio. Everything (microphone, speaker, buffers) is released as soon as this screen leaves the foreground.
 */
class AudioTransportTestActivity : LocalizedActivity(), AudioTransport.Listener {
    private lateinit var headV: TextView
    private lateinit var statsV: TextView
    private lateinit var permV: TextView
    private lateinit var sendBtn: Button
    private lateinit var rateBtn: Button
    private lateinit var jitterBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 500) } }
    private var jitterIndex = 1

    private val ctl get() = app.audioTest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DiagnosticScreens.isVisible()) { finish(); return }   // Stage 3: hidden developer screen; finishing in onCreate skips onStart (no listeners, probe or audio)
        title = "Bluetooth audio test"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = vbox()
        root.addView(label("Stage 5 - Bluetooth audio transport test").apply { setTypeface(null, Typeface.BOLD) })
        root.addView(label(INFO).apply { textSize = 13f })
        headV = label(); permV = label()
        root.addView(headV); root.addView(permV)
        sendBtn = button("") { onSendClicked() }
        rateBtn = button("") { toggleRate() }
        jitterBtn = button("") { cycleJitter() }
        root.addView(sendBtn); root.addView(rateBtn); root.addView(jitterBtn)
        root.addView(button("Copy statistics") { copyStats() })
        statsV = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true) }
        root.addView(statsV)
        setContentView(ScrollView(this).apply { addView(root) })
        jitterIndex = JitterConfig.PRESETS.indexOfFirst { it.targetMs == ctl.transport.jitterConfig.targetMs }.coerceAtLeast(0)
    }

    override fun onStart() {
        super.onStart()
        ctl.transport.addListener(this)
        ctl.onScreenVisible(true)          // enables receiving on this phone while the screen is open
        ui.post(tick)
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        ctl.transport.removeListener(this)
        ctl.onScreenVisible(false)         // stops sending/receiving, releases AudioRecord, AudioTrack, buffers
        super.onStop()
    }

    override fun onTransportChanged() = refresh()

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun onSendClicked() {
        if (ctl.isSending) { ctl.stopMic(); refresh(); return }
        // Stage 4: do not ask for the microphone while audio transfer is off (same message startMicToPeer would show)
        if (!AudioTransferFeatures.isTransferEnabled()) { Toast.makeText(this, AudioTransferFeatures.DISABLED_MESSAGE, Toast.LENGTH_LONG).show(); return }
        if (!micGranted()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return }
        ctl.startMicToPeer()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == REQ_MIC && !micGranted())
            Toast.makeText(this, "Microphone permission denied - this phone can only receive audio.", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun toggleRate() {
        if (ctl.isSending) return
        val rates = AudioConfig.SUPPORTED_RATES
        ctl.sampleRate = rates[(rates.indexOf(ctl.sampleRate) + 1) % rates.size]
        refresh()
    }

    private fun cycleJitter() {
        jitterIndex = (jitterIndex + 1) % JitterConfig.PRESETS.size
        ctl.transport.jitterConfig = JitterConfig.PRESETS[jitterIndex]      // applies live to a running stream
    }

    private fun copyStats() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Audio transport stats", statsText()))
        Toast.makeText(this, "Statistics copied", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val role = app.prefs.role
        val s = ctl.transport.snapshot()
        val test = when (role) {
            Role.MAIN_PHONE -> "Test A: this phone's microphone -> SIM Phone speaker"
            Role.SIM_PHONE -> "Test B: this phone's microphone -> Main Phone speaker"
            null -> "No role selected"
        }
        headV.text = "Role: ${role?.label ?: "-"}\nBluetooth: ${connText(app.connection.state)}\n$test"
        permV.text = if (micGranted()) "Microphone permission: granted" else "Microphone permission: NOT granted (needed to send)"
        sendBtn.text = if (ctl.isSending) "Stop sending" else "Start: send my microphone to the other phone"
        sendBtn.isEnabled = role != null && (ctl.isSending || s.linkUp)
        rateBtn.text = "Sample rate: ${ctl.sampleRate / 1000} kHz (tap to change)"
        rateBtn.isEnabled = !ctl.isSending
        jitterBtn.text = "Receive buffer target: ${JitterConfig.PRESETS[jitterIndex].targetMs} ms (tap to change)"
        statsV.text = statsText(s)
    }

    private fun connText(st: ConnectionManager.State) = if (st == ConnectionManager.State.CONNECTED) "CONNECTED" else st.name

    private fun statsText(s: TransportStats = ctl.transport.snapshot()): String {
        fun n(v: Long?) = v?.toString() ?: "-"
        fun ms(v: Number?) = v?.let { "$it ms" } ?: "-"
        val c = s.config
        return buildString {
            appendLine("Connection : ${if (s.linkUp) "CONNECTED" else "NOT CONNECTED"}")
            appendLine("Format     : " + (c?.let { "PCM16 mono ${it.sampleRate} Hz, ${it.frameMs} ms frames (~${it.kbps} kbit/s)" } ?: "-"))
            appendLine("Last error : ${s.lastError ?: "none"}")
            appendLine()
            appendLine("== SENDING (my microphone) : ${s.txState} ==")
            appendLine("Packets sent       : ${s.txSent}  (${s.txBytes / 1024} KiB)")
            appendLine("Dropped here       : ${s.txQueueDropped}  (send queue full)")
            appendLine("Input level (peak) : ${s.txPeak} / 32767" + if (s.txState == AudioTransport.TxState.STREAMING && s.txPeak == 0) "  <- silence: mic muted or blocked?" else "")
            appendLine("Peer received      : ${n(s.peerReceived)}")
            appendLine("Peer lost/overflow : ${n(s.peerLost)} / ${n(s.peerOverflow)}   underruns: ${n(s.peerUnderruns)}")
            appendLine("Peer buffer/output : ${ms(s.peerBufferMs)} / ${ms(s.peerOutputMs)}")
            appendLine("Round trip (audio) : ${ms(s.rttMs)}")
            appendLine("Est. latency       : ${ms(s.estLatencyMs)}  (frame + RTT/2 + peer buffer + peer output)")
            appendLine()
            appendLine("== RECEIVING (other phone -> my speaker) : ${s.rxState} ==")
            appendLine("Packets received   : ${s.rxReceived}")
            appendLine("Lost (seq gaps)    : ${s.rxLost}")
            appendLine("Dropped (overflow) : ${s.rxOverflow}   late/dup: ${s.rxLate}   ignored: ${s.rxIgnored}")
            appendLine("Underruns          : ${s.rxUnderruns}")
            appendLine("Buffer             : ${s.rxBufferMs} ms (target ${s.rxTargetMs} ms)   output: ${s.rxOutputMs} ms")
            appendLine("Jitter             : ${"%.1f".format(s.rxJitterMs)} ms")
            appendLine("Receive-side delay : ${ms(s.rxSideLatencyMs)}  (frame + buffer + output; transit is shown on the sender)")
            appendLine()
            append("Estimates are approximate: they exclude microphone/speaker hardware latency and assume a symmetric round trip.")
        }
    }

    private companion object {
        const val REQ_MIC = 30
        const val INFO =
            "Bluetooth audio transport test only. This is NOT cellular call audio.\n" +
            "1) Open this screen on BOTH phones (both CONNECTED).\n" +
            "2) Raise the media volume on the receiving phone. Keep the phones apart or use headphones, otherwise you will hear feedback.\n" +
            "3) Press Start on one phone, speak, and listen on the other. Then repeat the other way round.\n" +
            "Closing or leaving this screen stops the microphone and the speaker."
    }
}
