package com.example.companion.audio.transport

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What [UplinkLifecycle] needs to know about the world. Android-free so it can be faked; production: [AndroidUplinkEnvironment].
 * Every answer is read fresh each time; "unknown" (null) is always treated as "condition not met".
 */
interface UplinkEnvironment {
    /** True = a call is ACTIVE, false = no active call, null = unknown. */
    fun callActive(): Boolean?
    fun linkUp(): Boolean
    /** True = RECORD_AUDIO granted, false = denied, null = could not be determined. */
    fun microphonePermission(): Boolean?
    /** True while at least one activity of this app is started (Android only allows foreground microphone capture without a service). */
    fun appVisible(): Boolean
    /** True while another feature (the Stage 4 diagnostic) holds the microphone. */
    fun microphoneBusyElsewhere(): Boolean
    /** True = a headset-type output device exists, false = none, null = unknown. Presence only: routing and echo handling are NOT verified. */
    fun headsetOutputPresent(): Boolean?
}

/** Why [UplinkLifecycle.start] did not start (or why a running uplink was stopped). [text] is shown to the user. */
enum class UplinkBlock(val text: String) {
    SHUT_DOWN("Uplink was shut down"),
    FLAG_OFF("Switched off in this build (uplinkBridge = false)"),
    ALREADY_RUNNING("Uplink already running"),
    NO_ROLE("No role selected"),
    NOT_MAIN_PHONE("The uplink microphone runs on the Main Phone only"),
    NO_LINK("Bluetooth is not connected"),
    NO_ACTIVE_CALL("There is no active call"),
    BACKGROUND("The app is in the background; the microphone is used only while the call screen is visible"),
    PERMISSION("Microphone permission is not granted"),
    MICROPHONE_BUSY("Another feature is using the microphone"),
    ECHO_RISK("No headset detected: the Main Phone speaker may feed back into the microphone"),
    /** The transport / controller refused; the exact text is in [UplinkStartResult.message]. */
    REFUSED("Refused")
}

/** [block] == null means negotiation started (the microphone is NOT open yet; it opens only after the SIM Phone's sink accepts). */
class UplinkStartResult(val block: UplinkBlock?, val message: String) {
    val started: Boolean get() = block == null
}

enum class UplinkLifecycleState(val text: String) {
    DISABLED("Disabled (uplinkBridge = false) - microphone off"),
    IDLE("Idle - microphone off"),
    NEGOTIATING("Negotiating with the SIM Phone - microphone not open yet"),
    MICROPHONE_ACTIVE("MICROPHONE ACTIVE - capturing and sending to the SIM Phone"),
    NOT_SUPPORTED("Not supported / not verified - microphone off"),
    FAILED("Failed - microphone off"),
    SHUT_DOWN("Shut down - microphone off")
}

/** Everything the screens need, read in one go. Plain values; no audio. */
class UplinkSnapshot(
    val flags: UplinkFlags,
    val state: UplinkLifecycleState,
    val capability: UplinkCapability,
    val detail: String?,
    val lastStopReason: String?,
    /** What would stop a Start press right now (null = it would start). Echo risk is evaluated without an acknowledgement. */
    val startBlock: UplinkBlock?,
    val microphoneActive: Boolean,
    val localDirection: AudioDirection?,
    val linkUp: Boolean,
    val callActive: Boolean?,
    val microphonePermission: Boolean?,
    val headsetOutputPresent: Boolean?,
    val appVisible: Boolean,
    /** SIM Phone: the receiver is armed / an uplink stream is being received. */
    val receiverArmed: Boolean,
    val receivingUplink: Boolean
)

/**
 * Stage 40: reliable microphone / uplink lifecycle on top of [UplinkController] (which still owns the stream; nothing here
 * opens a recorder or a second pipeline). Main thread only, like the controller and [AudioTransport].
 *
 * Rules enforced here:
 * - Disabled by default: [UplinkFlags.uplinkBridge] is false unless the build says otherwise, and with it off [start] always refuses.
 * - Explicit activation: nothing in this class calls [start]; only a user action does. After any stop (user, call end, Bluetooth,
 *   background, failure) the uplink stays stopped until the user presses Start again. There is no automatic resume.
 * - Capture only while ALL of these hold: Main Phone, Bluetooth up, call ACTIVE, app visible, RECORD_AUDIO granted. They are
 *   checked before a start AND re-checked on every [onEnvironmentChanged]; the first one that fails stops the uplink immediately.
 * - The microphone opens only after the SIM Phone's sink answered READY (existing handshake). The only sink in this build refuses
 *   with "Not supported / not verified", so the microphone is never opened here; this class never reports that as working.
 * - Duplicate sessions: refused while a run exists, while another outgoing stream exists, while the Stage 4 diagnostic holds the
 *   microphone, and while another owner holds the transport's start-outcome slot (the last two via [UplinkController]).
 * - [shutdown] stops everything, disarms the receiver and refuses any later start.
 * - SIM Phone: arms the receiver only while the flag is on, Bluetooth is up and a call is active; otherwise disarms it (which also
 *   ends an incoming uplink stream). Arming opens nothing.
 * No audio is stored or logged here; there is no file, log or storage call in this class.
 */
class UplinkLifecycle(
    private val controller: UplinkController,
    private val env: UplinkEnvironment,
    val flags: UplinkFlags,
    private val localDirection: () -> AudioDirection?
) {
    interface Listener { fun onUplinkChanged() }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private var shutDown = false
    private var checking = false
    private var wasRunning = false

    /** Why the last run ended without the user pressing Stop (call ended, Bluetooth lost, app hidden, failure...). Cleared by the next start. */
    var lastStopReason: String? = null; private set

    // ------------------------------------------------------------------ queries

    val isRunning: Boolean
        get() = controller.status().phase.let { it == UplinkPhase.STARTING || it == UplinkPhase.STREAMING }

    /** True only while the controller reports its stream as STREAMING, i.e. the transport started the microphone. */
    val microphoneActive: Boolean
        get() = !shutDown && controller.status().phase == UplinkPhase.STREAMING

    fun state(): UplinkLifecycleState = when {
        shutDown -> UplinkLifecycleState.SHUT_DOWN
        !flags.uplinkBridge -> UplinkLifecycleState.DISABLED
        else -> when (controller.status().phase) {
            UplinkPhase.IDLE -> UplinkLifecycleState.IDLE
            UplinkPhase.STARTING -> UplinkLifecycleState.NEGOTIATING
            UplinkPhase.STREAMING -> UplinkLifecycleState.MICROPHONE_ACTIVE
            UplinkPhase.NOT_SUPPORTED -> UplinkLifecycleState.NOT_SUPPORTED
            UplinkPhase.FAILED -> UplinkLifecycleState.FAILED
        }
    }

    /** Side-effect free: what a Start press would run into, or null. Order matters: the first failing condition is reported. */
    fun preflight(echoAcknowledged: Boolean = false): UplinkBlock? {
        if (shutDown) return UplinkBlock.SHUT_DOWN
        if (!flags.uplinkBridge) return UplinkBlock.FLAG_OFF
        if (isRunning) return UplinkBlock.ALREADY_RUNNING
        conditionBlock()?.let { return it }
        if (safe(true) { env.microphoneBusyElsewhere() }) return UplinkBlock.MICROPHONE_BUSY
        if (safe<Boolean?>(null) { env.headsetOutputPresent() } != true && !echoAcknowledged) return UplinkBlock.ECHO_RISK
        return null
    }

    fun snapshot(): UplinkSnapshot {
        val st = controller.status()
        return UplinkSnapshot(
            flags = flags,
            state = state(),
            capability = st.capability,
            detail = st.detail,
            lastStopReason = lastStopReason,
            startBlock = preflight(),
            microphoneActive = microphoneActive,
            localDirection = localDirection(),
            linkUp = safe(false) { env.linkUp() },
            callActive = safe<Boolean?>(null) { env.callActive() },
            microphonePermission = safe<Boolean?>(null) { env.microphonePermission() },
            headsetOutputPresent = safe<Boolean?>(null) { env.headsetOutputPresent() },
            appVisible = safe(false) { env.appVisible() },
            receiverArmed = controller.receiverArmed,
            receivingUplink = controller.receivingUplink
        )
    }

    // ------------------------------------------------------------------ commands (user actions only)

    /**
     * Explicit user activation. Returns why it did not start; on [UplinkBlock.PERMISSION] the caller may ask Android for the
     * permission but must NOT start by itself afterwards (the user presses Start again). [echoAcknowledged] = the user confirmed
     * the echo warning (needed when no headset is detected).
     */
    fun start(echoAcknowledged: Boolean = false): UplinkStartResult {
        val block = preflight(echoAcknowledged)
        if (block != null) return UplinkStartResult(block, block.text)
        lastStopReason = null
        val err = controller.start()
        wasRunning = isRunning
        notifyChanged()
        return if (err == null) UplinkStartResult(null, "Negotiation started") else UplinkStartResult(UplinkBlock.REFUSED, err)
    }

    /** Idempotent. [reason] null = the user pressed Stop; otherwise it is remembered as the reason the run ended. */
    fun stop(reason: String? = null) {
        val running = isRunning
        controller.stop()
        wasRunning = false
        if (reason == null) lastStopReason = null else if (running) lastStopReason = reason
        notifyChanged()
    }

    /** Stops everything, disarms the receiver and refuses every later start. Idempotent. Call when the app is going away. */
    fun shutdown() {
        if (shutDown) return
        val running = isRunning
        controller.stop()
        controller.disarmReceiver()
        wasRunning = false
        shutDown = true
        if (running) lastStopReason = "Uplink shut down"
        notifyChanged()
    }

    // ------------------------------------------------------------------ events

    /**
     * Call on every change of call state, Bluetooth state, app visibility, permission and transport state. A running uplink whose
     * conditions no longer hold is stopped immediately; a stream that ended by itself (peer, microphone failure) is noticed here.
     */
    fun onEnvironmentChanged() {
        if (checking) return                       // stop() below re-enters through the transport's change callback
        checking = true
        try {
            if (!shutDown) {
                if (isRunning) conditionBlock()?.let { stop(it.text) }
                noticeEndedByItself()
                syncReceiver()
            }
        } finally { checking = false }
        notifyChanged()
    }

    // ------------------------------------------------------------------ internals

    /** The conditions that must hold for the whole time the microphone is in use. */
    private fun conditionBlock(): UplinkBlock? {
        val dir = localDirection() ?: return UplinkBlock.NO_ROLE
        if (dir != AudioDirection.MAIN_TO_SIM) return UplinkBlock.NOT_MAIN_PHONE
        if (!safe(false) { env.linkUp() }) return UplinkBlock.NO_LINK
        if (safe<Boolean?>(null) { env.callActive() } != true) return UplinkBlock.NO_ACTIVE_CALL
        if (!safe(false) { env.appVisible() }) return UplinkBlock.BACKGROUND
        if (safe<Boolean?>(null) { env.microphonePermission() } != true) return UplinkBlock.PERMISSION
        return null
    }

    private fun noticeEndedByItself() {
        val running = isRunning
        if (wasRunning && !running && lastStopReason == null) {
            lastStopReason = controller.status().detail ?: "Uplink ended"
        }
        wasRunning = running
    }

    /** SIM Phone: arm only while there is something to receive. Arming opens nothing. */
    private fun syncReceiver() {
        if (localDirection() != AudioDirection.SIM_TO_MAIN) return
        val want = flags.uplinkBridge && safe(false) { env.linkUp() } && safe<Boolean?>(null) { env.callActive() } == true
        if (want && !controller.receiverArmed) controller.armReceiver()
        else if (!want && controller.receiverArmed) controller.disarmReceiver()
    }

    private fun notifyChanged() { listeners.forEach { it.onUplinkChanged() } }

    /** A throwing environment (permission lookup, audio service) must never keep the microphone open: fall back to the "not met" value. */
    private inline fun <T> safe(fallback: T, read: () -> T): T = try { read() } catch (e: Exception) { fallback }
}
