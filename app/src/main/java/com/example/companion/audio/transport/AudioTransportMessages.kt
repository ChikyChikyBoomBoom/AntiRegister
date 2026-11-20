package com.example.companion.audio.transport

/**
 * Stage 31B: reusable, Android-free definitions for [AudioTransport]'s refusal / timeout texts and for the
 * "may this phone accept an incoming stream?" decision.
 *
 * Wire compatibility: nothing here changes a protocol message. A refusal is still an AUDIO_STOP whose optional
 * `reason` string carries the text (reason present = error), exactly as before. The texts below are character for
 * character the ones the Audio Test screen has always shown.
 */
object AudioTransportMessages {

    /**
     * Prefix of every "this phone is not ready to accept incoming audio" reason. Used when the receiving device is not
     * currently listening (today: neither the Audio Test screen nor call reception has armed it).
     * A future sender can recognise a refusal with [isNotListening] without matching the whole text.
     */
    const val NOT_LISTENING = "Not listening"

    /** Refusal reason sent while neither the Audio Test screen nor call reception is armed. Same text as before Stage 31B. */
    const val NOT_LISTENING_TEST_SCREEN = "$NOT_LISTENING: open the audio test screen on this phone"

    /** Sender-side message when the peer never answered AUDIO_START within the negotiate timeout. Same text as before Stage 31B. */
    const val NEGOTIATE_TIMEOUT = "The other phone did not answer. Open the audio test screen on it first."

    /** True when [reason] is a not-listening refusal (this text, or any text starting with [NOT_LISTENING]). */
    fun isNotListening(reason: String?): Boolean = reason != null && reason.startsWith(NOT_LISTENING)

    /**
     * Whether an incoming stream may be accepted. [testScreenRx] is the existing Audio Test flag (`rxEnabled`),
     * [callRx] the Stage 31B `callRxEnabled` flag, [callUplinkRx] the Stage 39 `callUplinkRxEnabled` flag (default false = as before). Any one arms the receiver; all false = refuse.
     */
    fun acceptsIncoming(testScreenRx: Boolean, callRx: Boolean, callUplinkRx: Boolean = false): Boolean = testScreenRx || callRx || callUplinkRx

    /** The refusal reason to send, or null when the stream may be accepted. */
    fun refusalReason(testScreenRx: Boolean, callRx: Boolean, callUplinkRx: Boolean = false): String? =
        if (acceptsIncoming(testScreenRx, callRx, callUplinkRx)) null else NOT_LISTENING_TEST_SCREEN
}
