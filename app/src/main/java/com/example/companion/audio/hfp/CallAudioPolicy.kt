package com.example.companion.audio.hfp

import com.example.companion.audio.call.AudioMode
import com.example.companion.audio.call.CallAudioResolver
import com.example.companion.audio.diag.Evaluation
import com.example.companion.protocol.CallState

enum class CallAudioPath { HFP, CUSTOM_BLUETOOTH_TRANSPORT, NONE }

/** [wantAudio] = "an audio session should exist now". Used only by the Stage 6 report screen. */
data class CallAudioDecision(val path: CallAudioPath, val wantAudio: Boolean, val reason: String)

/**
 * Report-facing view of the Stage 7 selection rule. The rule itself lives in [CallAudioResolver] (one source of truth);
 * this object only maps its answer for HfpReport. It starts nothing and can never end a call.
 */
object CallAudioPolicy {
    fun decide(cap: HfpCapability, stage4: Evaluation?, call: CallState): CallAudioDecision {
        val c = CallAudioResolver.resolve(cap.cellularCallAudioViaHfp, stage4, cellularBridgeAvailable = false)
        val active = call == CallState.ACTIVE
        return when (c.mode) {
            AudioMode.HFP -> CallAudioDecision(CallAudioPath.HFP, active, c.detail)
            AudioMode.CUSTOM_BLUETOOTH_TRANSPORT -> CallAudioDecision(CallAudioPath.CUSTOM_BLUETOOTH_TRANSPORT, active, c.detail)
            AudioMode.NONE -> CallAudioDecision(CallAudioPath.NONE, false, c.detail)
        }
    }
}
