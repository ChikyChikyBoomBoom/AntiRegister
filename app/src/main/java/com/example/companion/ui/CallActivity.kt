package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.audio.call.AudioSessionState
import com.example.companion.audio.call.CallAudioManager
import com.example.companion.audio.call.CallAudioStatus
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.ClientCallController
import com.example.companion.protocol.CallState

/** Main phone: minimal call-control screen. Only observes [ClientCallController]; no call logic here. */
class CallActivity : LocalizedActivity(), ClientCallController.Listener, ConnectionManager.Listener, CallAudioManager.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refreshTimer(); main.postDelayed(this, 1_000) } }
    private lateinit var linkV: LinkBanner
    private lateinit var emptyV: View
    private lateinit var stateV: TextView
    private lateinit var timerV: TextView
    private lateinit var audioV: TextView
    private lateinit var audioNoteV: TextView
    private lateinit var mute: Button
    private lateinit var speaker: Button
    private lateinit var numberV: TextView
    private lateinit var infoV: TextView
    private lateinit var errV: TextView
    private lateinit var answer: Button
    private lateinit var reject: Button
    private lateinit var hangup: Button
    private lateinit var dial: Button
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Calls"
        // Stage 7: calling is off. This screen only holds call buttons, so it closes at once (same pattern as the stale-role exit elsewhere).
        if (!CallFeatures.isCallingEnabled()) { showCallingDisabled(); finish(); return }
        // Stage 10: a call is in progress -> the dedicated call screen replaces this one (Back goes to the previous screen).
        if (CallScreenState.isLive(app.clientCalls.snapshot?.state)) {
            startActivity(Intent(this, InCallActivity::class.java)); finish(); return
        }
        val root = vbox()
        linkV = LinkBanner(this)
        // Stage 24: friendly state wording, an explained "no call" state, secondary lines in the shared text styles.
        emptyV = emptyState(UiIcon.CALLS, UiPolish.NO_ACTIVE_CALL)
        stateV = label().apply { textSize = 24f; setTypeface(null, Typeface.BOLD) }
        timerV = label().apply { textSize = 20f }
        numberV = label()
        audioV = label().apply { textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY) }
        audioNoteV = label().apply { textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY) }
        infoV = label().apply { textSize = 14f; setTextColor(UiColors.ERROR) }
        errV = errorText()
        root.addView(linkV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(emptyV)
        listOf(stateV, timerV, numberV, audioV, audioNoteV, infoV, errV).forEach { root.addView(it) }

        // These act on the SIM PHONE's own call audio through Telecom (the Main Phone's mic/speaker are not part of the call).
        mute = button("Mute SIM Phone mic") {
            app.clientCalls.setMute(app.clientCalls.simAudio?.micMuted != true)
        }
        speaker = button("SIM Phone speaker") {
            app.clientCalls.setSpeaker(app.clientCalls.simAudio?.speakerOn != true)
        }
        root.addView(row(mute, speaker))

        answer = styledButton("Answer", ButtonStyle.PRIMARY) { app.clientCalls.answer() }
        reject = styledButton("Reject", ButtonStyle.DANGER) { app.clientCalls.reject() }
        hangup = styledButton("Hang up", ButtonStyle.DANGER) { app.clientCalls.hangup() }
        root.addView(row(answer, reject, hangup))

        input = EditText(this).apply {
            hint = "Number to dial"
            setPadding(dp(4), dp(12), dp(4), dp(12))
            inputType = InputType.TYPE_CLASS_PHONE
            keyListener = DigitsKeyListener.getInstance("0123456789+")   // restrict typing; never rewrite what was typed
        }
        root.addView(input)
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("+", "0", "⌫")).forEach { keys ->
            root.addView(row(*keys.map { k -> button(k) { key(k) } }.toTypedArray()))
        }
        dial = styledButton("Dial", ButtonStyle.PRIMARY) {
            val n = input.text.toString()
            if (n.isEmpty()) Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show()
            else app.clientCalls.dial(n)
        }
        root.addView(dial)
        setContentView(ScrollView(this).apply { addView(constrainWidth(root)) })
    }

    private fun key(k: String) {
        val t = input.text
        if (k == "⌫") { if (t.isNotEmpty()) t.delete(t.length - 1, t.length) } else t.append(k)
        input.setSelection(input.text.length)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    override fun onStart() {
        super.onStart()
        app.clientCalls.addListener(this); app.connection.addListener(this); app.callAudio.addListener(this)
        refresh()
        main.post(tick)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        app.clientCalls.removeListener(this); app.connection.removeListener(this); app.callAudio.removeListener(this)
        super.onStop()
    }

    override fun onCallUiChanged() = refresh()
    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()
    override fun onCallAudioChanged(status: CallAudioStatus) = refresh()

    private fun refresh() {
        if (!::dial.isInitialized) return   // finished in onCreate (see above)
        val cc = app.clientCalls
        val s = cc.snapshot
        val up = cc.linkUp
        val link = linkStatusFor(up)
        linkV.update(link, "Bluetooth: ${link.label}")
        val noCall = up && s?.state == CallState.IDLE
        emptyV.show(noCall)                       // "No active call" replaces the bare state line
        stateV.show(!noCall)
        stateV.text = when {
            s != null -> UiPolish.callStateLabel(s.state) + (s.disconnectCause?.let { " ($it)" } ?: "")
            up -> "Waiting for SIM Phone…"
            else -> "Call state unknown (not connected)"
        }
        numberV.setTextOrHide(when {
            s == null || s.state == CallState.IDLE -> ""
            else -> (if (s.state == CallState.RINGING) "Caller: " else "Number: ") + (s.number ?: "not available")
        })
        infoV.setTextOrHide(if (cc.controlAvailable == false)
            "The SIM Phone cannot control calls yet: enable call control in its Connection status screen." else "")
        refreshAudio(s?.state)
        errV.setTextOrHide(cc.lastError?.let { "${cc.lastErrorCommand ?: "Command"} failed: ${describe(it)}" } ?: "")

        answer.show(s?.state == CallState.RINGING)
        reject.show(s?.state == CallState.RINGING)
        hangup.show(s?.state in HANGUP_STATES)
        dial.isEnabled = up && (s?.state == CallState.IDLE || s?.state == CallState.DISCONNECTED)
    }

    /**
     * Three separate facts, never merged: cellular call (state line), Bluetooth link (top line), call audio (this line).
     * A Bluetooth link alone is never shown as working call audio.
     */
    private fun refreshAudio(call: CallState?) {
        val a = app.callAudio.status
        val inCall = call != null && call != CallState.IDLE && call != CallState.DISCONNECTED
        audioV.setTextOrHide(if (call == null || !inCall) "" else
            "Audio: " + if (call == CallState.ACTIVE) a.label else "not started (call not connected yet)")
        audioNoteV.setTextOrHide(when {
            call == CallState.ACTIVE && (a.state == AudioSessionState.UNAVAILABLE || a.state == AudioSessionState.FAILED) ->
                "Call connected, audio unavailable.\n${a.detail}\n" +
                    "This phone's microphone and speaker are NOT part of the call. Talk and listen on the SIM Phone."
            else -> ""
        })
        val rep = app.clientCalls.simAudio
        val canControl = app.clientCalls.linkUp && call == CallState.ACTIVE && rep != null
        mute.isEnabled = canControl && rep?.micMuted != null
        speaker.isEnabled = canControl && rep?.speakerOn != null
        mute.text = "Mute SIM Phone mic: " + (rep?.micMuted?.let { if (it) "ON" else "OFF" } ?: "unknown")
        speaker.text = "SIM Phone speaker: " + (rep?.speakerOn?.let { if (it) "ON" else "OFF" } ?: "unknown")
        refreshTimer()
    }

    private fun refreshTimer() {
        val since = app.clientCalls.activeSince
        timerV.setTextOrHide(if (since == null || app.clientCalls.snapshot?.state != CallState.ACTIVE) "" else {
            val sec = (SystemClock.elapsedRealtime() - since) / 1000
            String.format("%02d:%02d", sec / 60, sec % 60)   // time since this phone saw the call become ACTIVE
        })
    }

    private fun describe(code: String) = CallScreenState.describeError(code)

    private companion object {
        val HANGUP_STATES = setOf(CallState.ACTIVE, CallState.DIALING, CallState.CONNECTING,
            CallState.SELECT_ACCOUNT, CallState.HOLDING)
    }
}
