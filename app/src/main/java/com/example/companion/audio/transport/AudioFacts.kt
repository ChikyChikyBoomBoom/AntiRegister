package com.example.companion.audio.transport

/**
 * Stage 40: read-only facts about this phone's audio system, as plain values for the Diagnostics screen. `null` always means
 * "could not be determined" and is shown as "unknown", never guessed. No audio content and no device names/addresses are held.
 * Reading them opens no microphone and starts no capture ([AndroidAudioFacts]).
 */
data class AudioFacts(
    /** `AudioManager.getMode()` value, null = unknown. */
    val audioMode: Int? = null,
    /** `AudioDeviceInfo.getType()` of every available communication device (Android 12+), null = not readable on this Android version. */
    val communicationDeviceTypes: List<Int>? = null,
    /** Sample rate -> true/false = `AudioRecord.getMinBufferSize` reported / refused a buffer for it (does NOT prove capture works), null = unknown. */
    val micRates: List<Pair<Int, Boolean?>> = emptyList(),
    /** `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`, null = unknown. */
    val nativeOutputRate: Int? = null,
    /** True = a headset-type OUTPUT device is present, false = none, null = unknown. Presence only: routing and echo are not verified. */
    val headsetOutputPresent: Boolean? = null
)

/** Pure text for [AudioFacts]. The integer constants mirror `AudioManager.MODE_*` / `AudioDeviceInfo.TYPE_*` (written from memory of the SDK; NOT checked against an SDK here). */
object AudioFactsText {
    fun modeText(mode: Int?): String = when (mode) {
        null -> "unknown"
        0 -> "NORMAL"
        1 -> "RINGTONE"
        2 -> "IN_CALL"
        3 -> "IN_COMMUNICATION"
        4 -> "CALL_SCREENING"
        5 -> "CALL_REDIRECT"
        6 -> "COMMUNICATION_REDIRECT"
        else -> "unrecognised ($mode)"
    }

    fun deviceTypeName(type: Int): String = when (type) {
        1 -> "earpiece"
        2 -> "speaker"
        3 -> "wired headset"
        4 -> "wired headphones"
        5 -> "line (analog)"
        6 -> "line (digital)"
        7 -> "Bluetooth SCO"
        8 -> "Bluetooth A2DP"
        9 -> "HDMI"
        11 -> "USB device"
        12 -> "USB accessory"
        13 -> "dock"
        18 -> "telephony"
        22 -> "USB headset"
        23 -> "hearing aid"
        24 -> "speaker (safe)"
        26 -> "BLE headset"
        27 -> "BLE speaker"
        else -> "type $type"
    }

    /** Output types that count as "a headset is present". Presence of such a device does NOT prove the audio is routed to it. */
    private val HEADSET_TYPES = setOf(3, 4, 7, 8, 22, 23, 26)

    fun hasHeadsetOutput(types: List<Int>): Boolean = types.any { it in HEADSET_TYPES }

    fun devicesText(types: List<Int>?): String = when {
        types == null -> "unknown (not readable on this Android version)"
        types.isEmpty() -> "none reported"
        else -> types.map(::deviceTypeName).distinct().joinToString(", ")
    }

    /** One line per rate, plus a note on what the numbers do and do not mean. */
    fun ratesText(rates: List<Pair<Int, Boolean?>>, nativeOutputRate: Int?): String {
        val lines = ArrayList<String>()
        if (rates.isEmpty()) lines += "Microphone rates: unknown"
        else rates.forEach { (hz, ok) ->
            val state = when (ok) { true -> "buffer reported"; false -> "not supported"; null -> "unknown" }
            val used = if (hz in AudioConfig.SUPPORTED_RATES) " (used by this app)" else ""
            lines += "$hz Hz: $state$used"
        }
        lines += "Output rate of this phone: " + (nativeOutputRate?.let { "$it Hz" } ?: "unknown")
        lines += "A reported buffer does not prove that capture works."
        return lines.joinToString("\n")
    }

    fun permissionText(granted: Boolean?): String = when (granted) { true -> "granted"; false -> "denied"; null -> "unknown" }
}
