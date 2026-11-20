package com.example.companion.audio.transport

/**
 * Stage 40: the feature-flag values this build runs with, as plain values (read by [UplinkLifecycle], shown in Diagnostics).
 * Every default is the safe one: nothing is enabled.
 *
 * - [uplinkBridge]: master switch of the Main Phone microphone -> SIM Phone uplink experiment. A build-time constant
 *   (`CompanionApp.UPLINK_BRIDGE`), false by default. It only makes the Start button usable; it never starts anything.
 * - [downlinkBridge]: the Stage 34A switch, shown for completeness.
 * - [twoWayCellularAudio]: the capability resolver's answer as text. It is NOT a switch and nothing in the uplink code can
 *   set it to "YES"; it stays "NO" in this build.
 */
data class UplinkFlags(
    val uplinkBridge: Boolean = false,
    val downlinkBridge: Boolean = false,
    val twoWayCellularAudio: String = TWO_WAY_NO
) {
    fun lines(): List<String> = listOf(
        "uplinkBridge = $uplinkBridge",
        "downlinkBridge = $downlinkBridge",
        "twoWayCellularAudio = $twoWayCellularAudio"
    )

    companion object {
        const val TWO_WAY_NO = "NO"
        const val TWO_WAY_YES = "YES"
        const val TWO_WAY_UNKNOWN = "UNKNOWN"
    }
}
