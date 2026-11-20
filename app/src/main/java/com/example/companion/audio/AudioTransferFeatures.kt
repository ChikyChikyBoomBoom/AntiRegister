package com.example.companion.audio

/**
 * Stage 1 kill switches for everything that moves call audio between the two phones (transport, uplink, downlink) and for the
 * HFP probe. Every implementation stays in the project; these flags only stop it from RUNNING.
 *
 * Both flags ship `false`. They are plain `const val`s on purpose: re-enabling is a deliberate source change (see STAGE1_NOTES.md),
 * never a setting, a preference, a manifest entry or something a peer phone can switch on over Bluetooth.
 *
 * Pure Kotlin (no Android classes), so it can be read from JVM tests and from any layer.
 */
object AudioTransferFeatures {
    /**
     * Master switch for audio transfer. While false: [com.example.companion.audio.transport.AudioTransport] refuses to start an
     * outgoing stream and refuses every incoming stream, the Audio Test screen's controller cannot open the microphone or arm
     * the receiver, and the downlink / uplink bridge flags are forced off (the existing `CompanionApp.DOWNLINK_BRIDGE` and
     * `UPLINK_BRIDGE` constants are kept, but are only honoured while this is true as well).
     */
    const val AUDIO_TRANSFER_ENABLED = false

    /**
     * Switch for HFP functionality ([com.example.companion.audio.hfp.BluetoothHfpManager]). While false: the manager does not
     * obtain the Bluetooth adapter at construction and [com.example.companion.audio.hfp.BluetoothHfpManager.probe] opens no
     * profile proxy and reads no HFP/A2DP profile state or bonded devices; it reports "not probed: disabled".
     * Independent of the Bluetooth connection used by SMS and call control, which is not affected.
     */
    const val HFP_ENABLED = false

    /**
     * Stage 2 switch for everything Magisk-related on the app side. The app contains NO code that runs, calls or talks to Magisk (the
     * Magisk module is built on a computer from `magisk/` and flashed by hand), so this flag guards the one place where the app would
     * lean on a Magisk-installed privileged setup: [com.example.companion.audio.call.AndroidTransportBackendFactory] reports the
     * privileged CAPTURE_AUDIO_OUTPUT permission as "not held" while this is false. The `magisk/` scripts have their own matching
     * guard (see STAGE2_NOTES.md). Plain `const val`, same rules as the flags above: re-enabling is a deliberate source change.
     */
    const val MAGISK_ENABLED = false

    /** Text shown wherever a disabled audio feature is asked to start. */
    const val DISABLED_MESSAGE = "Audio transfer is disabled in this build"

    /** Text used by the HFP probe while [HFP_ENABLED] is false. */
    const val HFP_DISABLED_MESSAGE = "HFP is disabled in this build"

    /** Text used wherever a Magisk-dependent capability is asked for while [MAGISK_ENABLED] is false. */
    const val MAGISK_DISABLED_MESSAGE = "Magisk support is disabled in this build"

    /** Function form so call sites do not become "constant condition" compiler warnings. */
    fun isTransferEnabled(): Boolean = AUDIO_TRANSFER_ENABLED

    fun isHfpEnabled(): Boolean = HFP_ENABLED

    fun isMagiskEnabled(): Boolean = MAGISK_ENABLED
}
