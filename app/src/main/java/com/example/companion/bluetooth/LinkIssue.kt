package com.example.companion.bluetooth

/**
 * Stage 27: WHY the Bluetooth link cannot be used right now, when there is a plain, user-fixable reason. Pure Kotlin (no Android
 * types), so it can be unit-tested on a plain JVM. Display and wording only: nothing here starts, stops or retries a connection;
 * [ConnectionManager] stays the only owner of that. The SMS screens use it to say "Bluetooth is turned off" instead of the same
 * "not connected" sentence for every cause.
 */
enum class LinkIssue {
    /** This phone has no Bluetooth adapter. */
    UNSUPPORTED,
    /** A required Bluetooth permission is not granted (denied, or revoked in system settings). */
    PERMISSION_MISSING,
    /** Bluetooth is switched off. The connection resumes by itself when it is switched on again (ConnectionManager keeps wanting it). */
    BLUETOOTH_OFF,
    /** Bluetooth is fine; the link dropped and the app is retrying. A short wait is the right answer. */
    RECONNECTING
}

object LinkIssues {

    /**
     * The first reason that applies, in the order a person would have to fix them: no adapter, then permission, then switched off, then
     * "retrying". null = no known reason (for example never connected, or the SIM Phone is simply not answering), or the link is up.
     */
    fun of(linkUp: Boolean, supported: Boolean, permissionGranted: Boolean, enabled: Boolean, reconnecting: Boolean): LinkIssue? = when {
        linkUp -> null
        !supported -> LinkIssue.UNSUPPORTED
        !permissionGranted -> LinkIssue.PERMISSION_MISSING
        !enabled -> LinkIssue.BLUETOOTH_OFF
        reconnecting -> LinkIssue.RECONNECTING
        else -> null
    }
}
