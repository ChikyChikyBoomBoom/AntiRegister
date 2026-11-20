package com.example.companion.audio.call

import com.example.companion.data.Role

/**
 * Stage 34A: creates the one-way cellular downlink backend for [AndroidCallAudioManager].
 * Creating a backend opens nothing (the [CallAudioBackend] contract); `start()` does.
 * Pure Kotlin (Stage 34B: the Android implementation lives in AndroidTransportBackendFactory.kt so this file and
 * [CallBackendSelection] stay JVM-testable).
 */
fun interface CallBackendFactory {
    /** null = no backend (the session then reports UNAVAILABLE). May throw: [CallAudioSession] turns an exception into FAILED. */
    fun create(role: Role): CallAudioBackend?

    companion object {
        /** The behaviour before Stage 34A: never a backend. */
        val NONE = CallBackendFactory { null }
    }
}

/**
 * Stage 34B: the pure decision "does this audio mode get a backend?", extracted unchanged from [AndroidCallAudioManager]
 * so it can be tested on the JVM. Nothing new is decided here.
 */
object CallBackendSelection {
    /** A failing switch lookup counts as disabled (never crashes, keeps the old behaviour). */
    fun switchOn(downlinkBridge: () -> Boolean): Boolean = runCatching { downlinkBridge() }.getOrDefault(false)

    /**
     * A backend only for [AudioMode.CUSTOM_BLUETOOTH_TRANSPORT], only while the switch is on, and only with a saved role.
     * The factory is not called otherwise. Exceptions from the factory are NOT caught here: [CallAudioSession] turns them into FAILED.
     */
    fun select(mode: AudioMode, downlinkBridge: () -> Boolean, role: Role?, factory: CallBackendFactory): CallAudioBackend? {
        if (mode != AudioMode.CUSTOM_BLUETOOTH_TRANSPORT || !switchOn(downlinkBridge)) return null
        val r = role ?: return null
        return factory.create(r)
    }
}
