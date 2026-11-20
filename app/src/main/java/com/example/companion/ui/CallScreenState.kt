package com.example.companion.ui

import com.example.companion.audio.call.AudioIndicator
import com.example.companion.audio.call.AudioSessionState
import com.example.companion.protocol.CallError
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import com.example.companion.protocol.SimAudioReport
import java.util.Locale

/** Which face the Main Phone call screen shows. Derived every time from real state; never stored. */
enum class CallScreenMode {
    SIM_UNAVAILABLE,   // Bluetooth link is down: the SIM Phone cannot be reached, real call state is unknown
    SYNCING,           // link is up but the SIM Phone has not reported its call state yet
    IDLE,              // no call
    INCOMING,          // ringing
    OUTGOING,          // dialing / connecting / waiting for SIM choice
    ACTIVE,            // connected (or on hold)
    ENDING,            // hang-up in progress
    ENDED              // call finished
}

/** What this screen remembers about a call that just finished (the SIM Phone may already report IDLE). */
data class EndedCall(val number: String?, val cause: String?)

/** Everything [CallScreenState.from] needs. All values come from the existing controllers; nothing is invented. */
data class CallScreenInput(
    val linkUp: Boolean,
    /** Lower-case link word for the banner, e.g. "disconnected", "reconnecting". */
    val linkText: String,
    /** [com.example.companion.calls.ClientCallController.snapshot]: null = unknown. */
    val snapshot: CallSnapshot?,
    /** Last live call this screen saw (used only to label the "unavailable" view). */
    val lastLive: CallSnapshot? = null,
    val ended: EndedCall? = null,
    val controlAvailable: Boolean? = null,
    val simAudio: SimAudioReport? = null,
    /** Seconds since this phone saw the call become ACTIVE; null when unknown. */
    val activeSeconds: Long? = null,
    val audioState: AudioSessionState? = null,
    val audioDetail: String = "",
    /** Stage 19: contact lookup. The default knows nobody, so the number is shown, exactly as before. */
    val nameFor: (String) -> String? = { null },
    /** Stage 21: this phone's own audio controls, exactly as [com.example.companion.audio.call.CallAudioStatus] reports them. */
    val phoneMuteSupported: Boolean = false,
    val phoneMicMuted: Boolean? = null,
    val phoneSpeakerSupported: Boolean = false,
    val phoneSpeakerOn: Boolean? = null
)

data class CallScreenUi(
    val mode: CallScreenMode,
    val title: String,
    val numberLine: String,
    val statusLine: String,
    val durationLine: String,
    val banner: String,
    val audioNote: String,
    val showAnswer: Boolean,
    val showReject: Boolean,
    val showHangup: Boolean,
    val showAudioControls: Boolean,
    val muteEnabled: Boolean,
    /** SIM Phone's reported mute state; null = not reported. */
    val muteOn: Boolean?,
    val speakerEnabled: Boolean,
    val speakerOn: Boolean?,
    /** Stage 19: the contact's name for [callerNumber], or empty when the caller is not a contact (or Contacts is off). */
    val nameLine: String = "",
    /** Stage 19: the number this screen is about (for the contact photo); null when there is none. */
    val callerNumber: String? = null,
    /** Stage 21: "Audio Connected" / "Connecting" / "Audio Unavailable" while the call is connected; empty otherwise. */
    val audioStateLine: String = "",
    val audioIndicator: AudioIndicator? = null,
    /** Stage 21: controls for THIS phone's mic / speaker. Shown only when the audio path supports them (never in this build). */
    val showPhoneAudioControls: Boolean = false,
    val phoneMuteEnabled: Boolean = false,
    val phoneMuteOn: Boolean? = null,
    val phoneSpeakerEnabled: Boolean = false,
    val phoneSpeakerOn: Boolean? = null
)

/** Pure functions (no Android types) so the call screen's decisions can be unit-tested on a plain JVM. */
object CallScreenState {

    const val UNKNOWN_NUMBER = "Unknown number"

    /** A call that is ringing, being placed, connected or held. */
    fun isLive(state: CallState?): Boolean = when (state) {
        CallState.RINGING, CallState.CONNECTING, CallState.SELECT_ACCOUNT,
        CallState.DIALING, CallState.ACTIVE, CallState.HOLDING -> true
        else -> false
    }

    /**
     * Stage 17: should the call screen be brought up for this report? [previous] = the last KNOWN state (a link drop reports
     * null and is NOT a state change). Opens when a live call appears, or when a new ring follows a non-ringing state; a
     * reconnect that merely re-reports the same live call does not pull the screen forward again. Stage 27: with session ids
     * ([previousId] / [currentId], null = unknown) a ring of a DIFFERENT session also opens it, even if the old ring was never seen ending.
     */
    fun shouldOpen(previous: CallState?, current: CallState?, previousId: String? = null, currentId: String? = null): Boolean {
        if (!isLive(current)) return false
        if (!isLive(previous)) return true
        // Stage 27: a different session ringing right after a ring we remember (the first call ended while the link was down) is a new call.
        if (current == CallState.RINGING && previous == CallState.RINGING) return CallNotificationState.isOtherSession(previousId, currentId)
        return current == CallState.RINGING
    }

    /** Stage 19: the screen as before, plus the caller's contact name when the number is a contact. */
    fun from(i: CallScreenInput): CallScreenUi {
        val ui = base(i)
        val number = callerNumber(i)
        val name = number?.let { i.nameFor(it) }?.trim()?.takeIf { it.isNotEmpty() }
        return if (number == null) ui else ui.copy(nameLine = name.orEmpty(), callerNumber = number)
    }

    /** The number the screen is about, by the same rules the number line uses; null when none is shown. */
    private fun callerNumber(i: CallScreenInput): String? {
        val s = i.snapshot
        val number: String? = when {
            !i.linkUp -> i.lastLive?.number
            s == null -> null
            s.state == CallState.IDLE -> i.ended?.number
            s.state == CallState.DISCONNECTED -> s.number ?: i.lastLive?.number
            else -> s.number
        }
        return number?.takeIf { it.isNotBlank() }
    }

    private fun base(i: CallScreenInput): CallScreenUi {
        val s = i.snapshot
        val banner = banner(i)
        return when {
            !i.linkUp -> unavailable(i)
            s == null -> blank(CallScreenMode.SYNCING, "Waiting for SIM Phone…", banner)
            else -> when (s.state) {
                CallState.IDLE ->
                    if (i.ended != null) ended(i.ended.number, i.ended.cause, banner)
                    else blank(CallScreenMode.IDLE, "No call", banner)
                CallState.RINGING -> live(
                    CallScreenMode.INCOMING, "Incoming call", s, "Ringing…", banner, i,
                    answer = true, reject = true, hangup = false
                )
                CallState.DIALING -> live(CallScreenMode.OUTGOING, "Outgoing call", s, "Calling…", banner, i, hangup = true)
                CallState.CONNECTING -> live(CallScreenMode.OUTGOING, "Outgoing call", s, "Connecting…", banner, i, hangup = true)
                CallState.SELECT_ACCOUNT -> live(
                    CallScreenMode.OUTGOING, "Outgoing call", s,
                    "Waiting for a SIM choice on the SIM Phone", banner, i, hangup = true
                )
                CallState.ACTIVE -> active(s, "Call in progress", banner, i)
                CallState.HOLDING -> active(s, "On hold", banner, i)
                CallState.DISCONNECTING -> live(CallScreenMode.ENDING, "Ending call", s, "Hanging up…", banner, i)
                CallState.DISCONNECTED -> ended(s.number ?: i.lastLive?.number, s.disconnectCause, banner)
            }
        }
    }

    private fun banner(i: CallScreenInput): String =
        if (i.linkUp && i.controlAvailable == false)
            "The SIM Phone cannot control calls yet: enable call control in its Connection status screen."
        else ""

    private fun unavailable(i: CallScreenInput): CallScreenUi {
        val last = i.lastLive
        val inProgress = if (last != null) " A call may still be in progress on the SIM Phone; it is not ended by this." else ""
        return CallScreenUi(
            mode = CallScreenMode.SIM_UNAVAILABLE,
            title = "SIM Phone unavailable",
            numberLine = if (last != null) "Last known call: " + (last.number ?: UNKNOWN_NUMBER.lowercase()) else "",
            statusLine = "Bluetooth is ${i.linkText}. Call controls are off and the call state is unknown.$inProgress",
            durationLine = "", banner = "", audioNote = "",
            showAnswer = false, showReject = false, showHangup = false, showAudioControls = false,
            muteEnabled = false, muteOn = null, speakerEnabled = false, speakerOn = null
        )
    }

    private fun blank(mode: CallScreenMode, title: String, banner: String) = CallScreenUi(
        mode = mode, title = title, numberLine = "", statusLine = "", durationLine = "", banner = banner, audioNote = "",
        showAnswer = false, showReject = false, showHangup = false, showAudioControls = false,
        muteEnabled = false, muteOn = null, speakerEnabled = false, speakerOn = null
    )

    private fun ended(number: String?, cause: String?, banner: String) = CallScreenUi(
        mode = CallScreenMode.ENDED, title = "Call ended",
        numberLine = number ?: UNKNOWN_NUMBER,
        statusLine = cause?.let { "Reason: $it" } ?: "",
        durationLine = "", banner = banner, audioNote = "",
        showAnswer = false, showReject = false, showHangup = false, showAudioControls = false,
        muteEnabled = false, muteOn = null, speakerEnabled = false, speakerOn = null
    )

    private fun live(
        mode: CallScreenMode, title: String, s: CallSnapshot, status: String, banner: String, i: CallScreenInput,
        answer: Boolean = false, reject: Boolean = false, hangup: Boolean = false
    ) = CallScreenUi(
        mode = mode, title = title, numberLine = s.number ?: UNKNOWN_NUMBER, statusLine = status,
        durationLine = "", banner = banner, audioNote = "",
        showAnswer = answer, showReject = reject, showHangup = hangup, showAudioControls = false,
        muteEnabled = false, muteOn = i.simAudio?.micMuted, speakerEnabled = false, speakerOn = i.simAudio?.speakerOn
    )

    private fun active(s: CallSnapshot, status: String, banner: String, i: CallScreenInput): CallScreenUi {
        val connected = s.state == CallState.ACTIVE
        val rep = i.simAudio
        // Stage 21: unknown / idle / failed / unavailable all count as NOT connected; only a backend-reported path is "connected".
        val indicator = indicatorFor(i.audioState)
        val audioBroken = connected && indicator == AudioIndicator.UNAVAILABLE
        // This phone's controls need an open path that supports them AND a state reported by that path to follow.
        val phoneMute = connected && indicator == AudioIndicator.CONNECTED && i.phoneMuteSupported && i.phoneMicMuted != null
        val phoneSpeaker = connected && indicator == AudioIndicator.CONNECTED && i.phoneSpeakerSupported && i.phoneSpeakerOn != null
        return CallScreenUi(
            mode = CallScreenMode.ACTIVE, title = status, numberLine = s.number ?: UNKNOWN_NUMBER,
            statusLine = if (connected) "" else "The call is held",
            durationLine = if (connected && i.activeSeconds != null) formatDuration(i.activeSeconds) else "",
            banner = banner,
            // Never present a Bluetooth link as working call audio. The Main Phone's mic/speaker are not in the call.
            audioNote = if (audioBroken)
                "Call connected, audio unavailable.\n${i.audioDetail}\n" +
                    "This phone's microphone and speaker are NOT part of the call. Talk and listen on the SIM Phone."
            else "",
            showAnswer = false, showReject = false, showHangup = true, showAudioControls = true,
            // Both buttons act on the SIM Phone (Telecom) and need a reported state to follow.
            muteEnabled = connected && rep?.micMuted != null, muteOn = rep?.micMuted,
            speakerEnabled = connected && rep?.speakerOn != null, speakerOn = rep?.speakerOn,
            audioStateLine = if (connected) indicator.text else "",
            audioIndicator = if (connected) indicator else null,
            showPhoneAudioControls = connected && (i.phoneMuteSupported || i.phoneSpeakerSupported),
            phoneMuteEnabled = phoneMute, phoneMuteOn = if (phoneMute) i.phoneMicMuted else null,
            phoneSpeakerEnabled = phoneSpeaker, phoneSpeakerOn = if (phoneSpeaker) i.phoneSpeakerOn else null
        )
    }

    /** Stage 21: the one mapping from session state to what is shown. null (not reported) is never "connected". */
    fun indicatorFor(state: AudioSessionState?): AudioIndicator = when (state) {
        AudioSessionState.CONNECTED, AudioSessionState.ACTIVE -> AudioIndicator.CONNECTED
        AudioSessionState.STARTING -> AudioIndicator.CONNECTING
        else -> AudioIndicator.UNAVAILABLE
    }

    /** mm:ss, or h:mm:ss from one hour. Negative input is treated as zero. */
    fun formatDuration(totalSeconds: Long): String {
        val t = if (totalSeconds < 0) 0 else totalSeconds
        val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }

    /** Text for a failure code reported by the SIM Phone. */
    fun describeError(code: String): String = when (code) {
        CallError.AUDIO_CONTROL_UNAVAILABLE -> "SIM Phone cannot control call audio (call control not enabled)"
        CallError.ROUTE_UNSUPPORTED -> "SIM Phone does not offer that audio route right now"
        CallError.NO_ROLE -> "SIM Phone is not the default phone app (call control not enabled)"
        CallError.NO_INCOMING_CALL -> "no incoming call"
        CallError.NO_ACTIVE_CALL -> "no call to hang up"
        CallError.INVALID_NUMBER -> "number not accepted (only digits with optional leading +)"
        CallError.CALL_IN_PROGRESS -> "another call is in progress"
        CallError.PERMISSION_DENIED -> "SIM Phone lacks the phone-call permission"
        CallError.TELECOM_ERROR -> "Android rejected the request"
        CallError.NOT_CONFIRMED -> "Android did not report the expected change"
        else -> code
    }
}
