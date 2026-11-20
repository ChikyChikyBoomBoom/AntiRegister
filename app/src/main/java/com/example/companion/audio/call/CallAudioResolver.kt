package com.example.companion.audio.call

import com.example.companion.audio.diag.Evaluation
import com.example.companion.audio.hfp.Tri

/**
 * Pure rule that turns the recorded results of Stages 4-6 into the one capability the app acts on.
 *
 *   Priority 1  HFP proven to carry cellular downlink AND uplink (Stage 6 says NO for a normal app)  -> HFP
 *   Priority 2  [downlinkBridge] enabled (Stage 34A, default false)                                  -> custom transport, ONE-WAY downlink only
 *   Priority 3  Stage 4 Result A on this device AND a cellular source/sink exists in the build       -> custom transport
 *   Priority 4  otherwise                                                                            -> NONE
 *
 * Stage 34A: [downlinkBridge] = false (the default) leaves every result exactly as before. When true, the resolver may select the
 * one-way cellular downlink backend (SIM Phone downlink -> Main Phone). That path NEVER carries the Main Phone microphone:
 * `twoWayCellularAudio` stays [Tri.NO]. Whether the privileged capture works on the POCO F3 / Xiaomi HAL is UNVERIFIED.
 *
 * [cellularBridgeAvailable] is false in this build: there is no CellularAudioSource and no uplink sink, and Android has
 * no supported public API for either (reading call audio needs the privileged CAPTURE_AUDIO_OUTPUT; no API writes into
 * the cellular uplink). Stage 4 can therefore only ever show an acoustic path, which is not call-audio injection.
 */
object CallAudioResolver {

    fun resolve(
        hfpCellularAudio: Tri,
        stage4: Evaluation?,
        cellularBridgeAvailable: Boolean,
        downlinkBridge: Boolean = false
    ): CallAudioCapability = when {
        hfpCellularAudio == Tri.YES -> CallAudioCapability(
            AudioMode.HFP, Tri.YES, null, "HFP reported to carry cellular downlink and uplink.",
            MuteScope.SIM_PHONE_MICROPHONE, RouteScope.SIM_PHONE_SPEAKER)

        downlinkBridge -> CallAudioCapability(
            AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, Tri.NO, null,
            "One-way cellular downlink (SIM Phone -> Main Phone) only; no microphone uplink. Not verified on a device.",
            MuteScope.SIM_PHONE_MICROPHONE, RouteScope.SIM_PHONE_SPEAKER)

        stage4?.result == "A" && cellularBridgeAvailable -> CallAudioCapability(
            AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, Tri.UNKNOWN, null,
            "Stage 4 reported Result A on this device; end-to-end two-way audio is not verified.",
            MuteScope.SIM_PHONE_MICROPHONE, RouteScope.SIM_PHONE_SPEAKER)

        else -> CallAudioCapability(
            AudioMode.NONE, Tri.NO, AudioUnavailableReason.AUDIO_API_UNAVAILABLE,
            "HFP unavailable (Stage 6: HFP cellular audio = $hfpCellularAudio; a normal app cannot be the Hands-Free unit). " +
                "Custom transport unavailable (Stage 4 = ${stage4?.result ?: "not run"}; " +
                (if (cellularBridgeAvailable) "" else "no CellularAudioSource or cellular-uplink sink exists; ") +
                "documented Android design gives a normal app no supported way to read or inject cellular call audio). " +
                "Not verified on a device.",
            MuteScope.SIM_PHONE_MICROPHONE, RouteScope.SIM_PHONE_SPEAKER)
    }
}
