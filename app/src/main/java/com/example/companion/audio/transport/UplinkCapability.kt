package com.example.companion.audio.transport

/** Stage 39: the one headline used everywhere an uplink sink cannot be used. Never changed per call site. */
object UplinkMessages {
    const val NOT_SUPPORTED = "Not supported / not verified"

    /** True when [reason] is an uplink "not supported" refusal (prefix match, same style as [AudioTransportMessages.isNotListening]). */
    fun isUplinkNotSupported(reason: String?): Boolean = reason != null && reason.startsWith(NOT_SUPPORTED)
}

enum class UplinkSupport { NOT_SUPPORTED, VERIFIED }

enum class UplinkUnsupportedReason { NO_VERIFIED_SINK, PEER_NOT_VERIFIED }

/**
 * Whether audio that reaches the SIM Phone can be injected into the cellular call uplink.
 *
 * Stage 36 classification: POTENTIALLY FEASIBLE BUT UNVERIFIED. This build therefore has NO code path that creates a
 * [UplinkSupport.VERIFIED] value: the constructor is private and [notSupported] is the only factory. `VERIFIED` may only be
 * introduced together with an owner-recorded, far-end-confirmed device result (Stage 36 Gate 4), never inferred by code.
 */
class UplinkCapability private constructor(
    val support: UplinkSupport,
    val reason: UplinkUnsupportedReason?,
    val detail: String
) {
    val isSupported: Boolean get() = support == UplinkSupport.VERIFIED
    /** Always [UplinkMessages.NOT_SUPPORTED] while nothing is verified. */
    val headline: String get() = if (isSupported) "Verified" else UplinkMessages.NOT_SUPPORTED

    companion object {
        fun notSupported(reason: UplinkUnsupportedReason, detail: String) =
            UplinkCapability(UplinkSupport.NOT_SUPPORTED, reason, detail)
    }
}
