package com.example.companion.data

/**
 * Stage 29: what a value read back from the saved settings must look like before the rest of the app may use it. Pure Kotlin (no Android
 * types), so it is unit-tested on a plain JVM. Every function accepts ANY input and never throws; an unusable value becomes the safe
 * default (null / 0), which is exactly what a phone that never saved the value would give.
 *
 * Used by [AppPrefs] on every read. A value that fails here is treated as "not set": a bad role means the welcome screen is shown again,
 * a bad device address means no SIM Phone is selected (the person picks one again), a bad time means "never connected".
 */
object StoredValues {

    /** "AA:BB:CC:DD:EE:FF": six hex pairs, the only shape Android gives a Bluetooth address. Letter case is kept as stored. */
    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    /** Longest device name kept (the Bluetooth specification allows 248 bytes). */
    const val MAX_NAME = 248

    /** Only the exact enum names that [AppPrefs] writes are accepted (no trimming, no case change): anything else is damaged data. */
    fun role(raw: String?): Role? = Role.values().firstOrNull { it.name == raw }

    /** A well-formed Bluetooth address, or null. */
    fun deviceAddress(raw: String?): String? = raw?.takeIf { MAC.matches(it) }

    /** A printable, non-blank name no longer than [MAX_NAME], or null. Control characters are dropped. */
    fun deviceName(raw: String?): String? {
        val cleaned = raw?.filter { !it.isISOControl() }?.take(MAX_NAME)
        return cleaned?.takeIf { it.isNotBlank() }
    }

    /** A time in epoch milliseconds: a negative value is damaged data and means "never" (0). */
    fun timestamp(raw: Long): Long = if (raw < 0L) 0L else raw
}
