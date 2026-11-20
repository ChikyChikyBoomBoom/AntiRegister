package com.example.companion.calls

import com.example.companion.protocol.MessageType

/**
 * Stage 7 (read-only calling architecture): the ONE switch for everything that can start, answer, reject, end, mute or otherwise
 * control a phone call, on either phone. Every legacy implementation stays in the project (CallManager, HostCallController,
 * ClientCallController, CompanionInCallService, DialPadActivity, InCallActivity, CallActivity, the notifiers ...); this flag only
 * stops them from RUNNING. It is checked at each entry point (UI button, notification action, Bluetooth command, Telecom service,
 * deep link) so that no single missed screen can place or control a call.
 *
 * Plain `const val`, like [com.example.companion.audio.AudioTransferFeatures]: re-enabling is a deliberate source change
 * (see STAGE7_NOTES.md), never a preference, a manifest entry, an intent extra or something the peer phone can switch on over
 * Bluetooth. Pure Kotlin (no Android classes) so it can be read from JVM tests.
 *
 * NOT covered by this flag, on purpose: call-history DISPLAY ([SimCallLogReader] / READ_CALL_LOG on the SIM Phone, the local
 * [CallHistoryStore] list on the Main Phone), SMS and the Bluetooth connection.
 */
object CallFeatures {
    /** Master switch for call control. While false nothing can place, answer, reject, hang up, mute or route a call. */
    const val CALLING_ENABLED = false

    /**
     * While false the Main Phone's Recent Calls list is read-only: its per-entry Delete and "Clear all calls" are refused
     * (they would change stored history). Display, contact names and the SMS shortcut are unaffected.
     */
    const val HISTORY_EDITING_ENABLED = false

    /** The only text shown wherever a disabled calling action is used: "This section is under development". */
    const val DISABLED_MESSAGE = "این بخش در حال توسعه است"

    /** Function forms so call sites do not become "constant condition" compiler warnings. */
    fun isCallingEnabled(): Boolean = CALLING_ENABLED
    fun isHistoryEditingEnabled(): Boolean = HISTORY_EDITING_ENABLED

    /** The Bluetooth messages that carry a call command or a call report. SMS and keepalive messages are not in this set. */
    val CALL_MESSAGE_TYPES: Set<MessageType> = setOf(
        MessageType.CALL_INCOMING, MessageType.CALL_STATE, MessageType.CALL_ANSWER, MessageType.CALL_REJECT,
        MessageType.CALL_HANGUP, MessageType.DIAL, MessageType.CALL_MUTE, MessageType.CALL_SPEAKER, MessageType.CALL_SYNC_REQUEST
    )

    /** True when [type] must be ignored right now (a call message while calling is off). Never true for SMS / PING / PONG. */
    fun blocksMessage(type: MessageType): Boolean = !isCallingEnabled() && type in CALL_MESSAGE_TYPES
}
