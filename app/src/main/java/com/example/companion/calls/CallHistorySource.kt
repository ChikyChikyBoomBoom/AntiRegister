package com.example.companion.calls

/**
 * Stage 5: where call history comes from. Two switches, both read in exactly one place each.
 *
 *  - [SIM_USES_CALL_LOG]: the SIM Phone's call history is read from Android's own call log ([SimCallLogReader], `CallLog.Calls`,
 *    runtime permission READ_CALL_LOG). It does NOT need the default-phone-app role, [CompanionInCallService], the DIAL filters,
 *    CALL_PHONE or RECORD_AUDIO. Read by SimCallActivity.
 *  - [LEGACY_LIVE_RECORDING_ENABLED]: the old way. The Main Phone builds its Recent Calls from the live call states that the SIM
 *    Phone's InCallService reports over Bluetooth ([CallHistoryRecorder] / [CallHistoryTracker]). Left ON, so the Main Phone behaves
 *    exactly as before (Stage 5 must not change Main Phone behaviour). Set to false to stop that recording; the code stays.
 *    Turning it off changes nothing about live call control: it only stops new rows from being written to the Main Phone's list.
 *
 * Plain vals (not const) so that flipping one never produces "unreachable code" warnings.
 */
object CallHistorySource {
    val SIM_USES_CALL_LOG: Boolean = true
    val LEGACY_LIVE_RECORDING_ENABLED: Boolean = true
}
