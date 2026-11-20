package com.example.companion.audio.call

import com.example.companion.audio.hfp.Tri
import com.example.companion.audio.transport.AudioConfig
import com.example.companion.audio.transport.AudioSource
import com.example.companion.audio.transport.AudioStartOutcome
import com.example.companion.audio.transport.CellularAudioConfiguration
import com.example.companion.audio.transport.RetryScheduler
import com.example.companion.audio.transport.RetryTimer
import com.example.companion.data.Role
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 34B JVM integration tests: [CallAudioResolver] + [CallBackendSelection] + [CallAudioSession] + the real
 * [TransportCallAudioBackend], wired the same way [AndroidCallAudioManager] wires them, with fakes at the Android seams
 * (shared transport port, cellular capture, scheduler). Virtual time, no threads, no Android classes.
 *
 * NOT covered here (Android classes, no Robolectric in this project): [AndroidCallAudioManager] itself,
 * [AndroidTransportBackendFactory], [com.example.companion.app.CompanionApp]. Nothing here says anything about real cellular audio.
 */
class CallAudioIntegrationTest {

    private class FakeScheduler : RetryScheduler {
        class Task(val at: Long, val run: Runnable) { var cancelled = false }
        var now = 0L
        val tasks = ArrayList<Task>()
        override fun postDelayed(delayMs: Long, task: Runnable): RetryTimer {
            val t = Task(now + delayMs, task); tasks += t
            return RetryTimer { t.cancelled = true }
        }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val due = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                now = maxOf(now, due.at); tasks.remove(due); due.run.run()
            }
            now = target
        }
    }

    /** The ONE shared transport, as seen through the backend's port. Counts every touch and every listener-slot overwrite. */
    private class SharedPort : CallTransportPort {
        override var isLinkUp = true
        override var callRxEnabled = false
        override var isSending = false
        override var isReceiving = false
        override var receivedPackets = 0L
        override var lastError: String? = null
        var calls = 0                         // every use of the port
        @JvmField var startListener: ((AudioStartOutcome) -> Unit)? = null
        @JvmField var changeListener: (() -> Unit)? = null
        var slotOverwrites = 0                // a non-null listener replaced by another non-null one = a second owner
        val sent = ArrayList<Pair<AudioSource, AudioConfig>>()
        override fun startSending(source: AudioSource, config: AudioConfig): String? { calls++; sent += source to config; isSending = true; return null }
        override fun stopSending(reason: String?, userInitiated: Boolean) { calls++; isSending = false }
        override fun stopReceiving(reason: String?) { calls++; isReceiving = false }
        override fun setStartListener(l: ((AudioStartOutcome) -> Unit)?) { calls++; if (l != null && startListener != null) slotOverwrites++; startListener = l }
        override fun setChangeListener(l: (() -> Unit)?) { calls++; if (l != null && changeListener != null) slotOverwrites++; changeListener = l }
    }

    private class FakeCellular : ConfigurableCellularSource {
        var stops = 0
        override fun startWithConfiguration(configuration: CellularAudioConfiguration, listener: AudioSource.Listener): String? = null
        override fun stop() { stops++ }
    }

    /** Wires like AndroidCallAudioManager: capability from the resolver, backend from CallBackendSelection. */
    private class Rig(var bridge: Boolean, var role: Role? = Role.SIM_PHONE, val switchThrows: Boolean = false) {
        val sched = FakeScheduler()
        val port = SharedPort()
        val cell = FakeCellular()
        var factoryCalls = 0
        val factoryRoles = ArrayList<Role>()
        val backends = ArrayList<TransportCallAudioBackend>()
        var factoryBehaviour: (Role) -> CallAudioBackend? = { r ->
            TransportCallAudioBackend(r, port, cell, sched).also { backends += it }
        }
        val switch: () -> Boolean = { if (switchThrows) throw IllegalStateException("prefs broken") else bridge }
        val factory = CallBackendFactory { r -> factoryCalls++; factoryRoles += r; factoryBehaviour(r) }
        val states = ArrayList<CallAudioStatus>()
        val session = CallAudioSession(
            capability = {
                CallAudioResolver.resolve(Tri.NO, null, cellularBridgeAvailable = false, downlinkBridge = CallBackendSelection.switchOn(switch))
            },
            backendFactory = { mode -> CallBackendSelection.select(mode, switch, role, factory) },
            onChanged = { states += it }
        )
        val state get() = session.status.state
        fun callStarts(id: String = "c1") { session.onLink(true); session.onCallState(CallState.ACTIVE, id) }
    }

    // ---------------------------------------------------------------- 1 / 2: switch default and disabled behaviour

    @Test fun backendFactoryNoneNeverCreatesABackend() {
        assertNull(CallBackendFactory.NONE.create(Role.SIM_PHONE))
        assertNull(CallBackendFactory.NONE.create(Role.MAIN_PHONE))
    }

    @Test fun disabledBridgeKeepsOldBehaviourAndNeverTouchesTheTransport() {
        val r = Rig(bridge = false)
        r.callStarts()
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
        assertEquals(AudioMode.NONE, r.session.status.mode)
        assertEquals(AudioUnavailableReason.AUDIO_API_UNAVAILABLE, r.session.status.reason)
        assertEquals(0, r.factoryCalls)
        assertEquals(0, r.port.calls)
        assertFalse(r.port.callRxEnabled)
        r.session.onCallState(CallState.DISCONNECTED)
        assertEquals(AudioSessionState.IDLE, r.state)
        assertEquals(0, r.port.calls)
    }

    @Test fun disabledBridgeNeverCallsTheFactoryForAnyMode() {
        val r = Rig(bridge = false)
        AudioMode.values().forEach { assertNull(CallBackendSelection.select(it, r.switch, Role.SIM_PHONE, r.factory)) }
        assertEquals(0, r.factoryCalls)
    }

    @Test fun throwingSwitchCountsAsDisabledAndDoesNotCrash() {
        val r = Rig(bridge = true, switchThrows = true)
        r.callStarts()
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
        assertEquals(AudioMode.NONE, r.session.status.mode)
        assertEquals(0, r.factoryCalls)
        assertFalse(CallBackendSelection.switchOn { throw RuntimeException("x") })
    }

    // ---------------------------------------------------------------- 3: enabled selects the downlink backend

    @Test fun enabledSelectsTheCellularDownlinkBackendForBothRoles() {
        listOf(Role.SIM_PHONE, Role.MAIN_PHONE).forEach { role ->
            val r = Rig(bridge = true, role = role)
            r.callStarts()
            assertEquals(1, r.factoryCalls)
            assertEquals(listOf(role), r.factoryRoles)
            assertEquals(AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, r.session.status.mode)
            assertEquals(AudioSessionState.STARTING, r.state)
            assertTrue(r.backends.single().mode == AudioMode.CUSTOM_BLUETOOTH_TRANSPORT)
        }
    }

    @Test fun onlyTheCustomTransportModeGetsABackend() {
        val r = Rig(bridge = true)
        AudioMode.values().filter { it != AudioMode.CUSTOM_BLUETOOTH_TRANSPORT }.forEach {
            assertNull(it.name, CallBackendSelection.select(it, r.switch, Role.SIM_PHONE, r.factory))
        }
        assertEquals(0, r.factoryCalls)
    }

    @Test fun enabledWithoutSavedRoleIsUnavailableAndCreatesNothing() {
        val r = Rig(bridge = true, role = null)
        r.callStarts()
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
        assertEquals(0, r.factoryCalls)
        assertEquals(0, r.port.calls)
    }

    // ---------------------------------------------------------------- 4: two-way stays NO

    @Test fun twoWayCellularAudioStaysNoWhenTheBridgeIsOn() {
        val cap = CallAudioResolver.resolve(Tri.NO, null, false, downlinkBridge = true)
        assertEquals(AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, cap.mode)
        assertEquals(Tri.NO, cap.twoWayCellularAudio)
    }

    // ---------------------------------------------------------------- 5: never onActive (through the real session)

    @Test fun simBackendReachesConnectedButNeverActive() {
        val r = Rig(bridge = true, role = Role.SIM_PHONE)
        r.callStarts()
        r.port.startListener!!.invoke(AudioStartOutcome.Ready)
        r.port.changeListener!!.invoke()
        r.sched.advance(60_000)
        assertEquals(AudioSessionState.CONNECTED, r.state)
        assertTrue(r.states.none { it.state == AudioSessionState.ACTIVE })
    }

    @Test fun mainBackendReachesConnectedButNeverActive() {
        val r = Rig(bridge = true, role = Role.MAIN_PHONE)
        r.callStarts()
        r.port.isReceiving = true; r.port.receivedPackets = 25
        r.sched.advance(60_000)
        assertEquals(AudioSessionState.CONNECTED, r.state)
        assertTrue(r.states.none { it.state == AudioSessionState.ACTIVE })
    }

    // ---------------------------------------------------------------- 6 / 7: one shared transport, no second owner

    @Test fun simUsesOnlyTheSharedPortAndOneListenerOfEachKind() {
        val r = Rig(bridge = true, role = Role.SIM_PHONE)
        r.callStarts()
        assertEquals(1, r.port.sent.size)                        // one AUDIO_START through the shared port
        assertTrue(r.port.startListener != null && r.port.changeListener != null)
        r.port.startListener!!.invoke(AudioStartOutcome.Ready)
        assertEquals(0, r.port.slotOverwrites)
        r.session.onCallState(CallState.DISCONNECTED)            // call ends -> released, slots cleared
        assertNull(r.port.startListener); assertNull(r.port.changeListener)
        assertEquals(1, r.cell.stops)
    }

    @Test fun mainArmsAndClearsTheSharedCallRxGateAndNeverSends() {
        val r = Rig(bridge = true, role = Role.MAIN_PHONE)
        r.callStarts()
        assertTrue(r.port.callRxEnabled)
        assertTrue(r.port.sent.isEmpty())
        assertNull(r.port.startListener)                          // Main never occupies the start-listener slot
        r.session.onCallState(CallState.DISCONNECTED)
        assertFalse(r.port.callRxEnabled)
        assertNull(r.port.changeListener)
    }

    @Test fun backToBackCallsReuseTheSamePortWithoutOverlap() {
        val r = Rig(bridge = true, role = Role.SIM_PHONE)
        r.callStarts("c1")
        r.session.onCallState(CallState.ACTIVE, "c2")            // missed in-between report = new call
        assertEquals(2, r.backends.size)
        assertEquals(0, r.port.slotOverwrites)                    // old backend released before the new one took the slots
        assertTrue(r.backends[0].phase == TransportCallAudioBackend.Phase.STOPPED)
        assertEquals(2, r.port.sent.size)                         // one AUDIO_START per backend (one per call), both via the same port
    }

    // ---------------------------------------------------------------- 8: initialization failures never crash

    @Test fun factoryThatThrowsIsAFailedStartNotACrash() {
        val r = Rig(bridge = true)
        r.factoryBehaviour = { throw IllegalStateException("AudioRecord exploded") }
        r.callStarts()
        assertEquals(AudioSessionState.FAILED, r.state)
        assertEquals(AudioUnavailableReason.AUDIO_INIT_FAILED, r.session.status.reason)
        assertEquals(0, r.port.calls)
        r.session.onCallState(CallState.DISCONNECTED)            // the call itself is unaffected
        assertEquals(AudioSessionState.IDLE, r.state)
    }

    @Test fun factoryThatReturnsNullIsUnavailable() {
        val r = Rig(bridge = true)
        r.factoryBehaviour = { null }
        r.callStarts()
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
    }

    @Test fun captureFailureInEveryConfigurationEndsAsFailedAndReleasesEverything() {
        val r = Rig(bridge = true, role = Role.SIM_PHONE)
        r.callStarts()
        repeat(4) { r.port.startListener?.invoke(AudioStartOutcome.SourceFailed("needs CAPTURE_AUDIO_OUTPUT (privileged app)")) }
        assertEquals(AudioSessionState.FAILED, r.state)
        assertEquals(AudioUnavailableReason.PERMISSION_OR_ROLE_UNAVAILABLE, r.session.status.reason)
        assertNull(r.port.startListener); assertNull(r.port.changeListener)
        assertFalse(r.port.isSending)
        assertEquals(1, r.cell.stops)
    }

    @Test fun bluetoothDropReleasesTheBackendAndAReconnectStartsExactlyOneNew() {
        val r = Rig(bridge = true, role = Role.MAIN_PHONE)
        r.callStarts()
        r.session.onLink(false)
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
        assertFalse(r.port.callRxEnabled)
        r.session.onLink(true)
        assertEquals(2, r.backends.size)
        assertSame(r.backends[1], r.backends.last())
        assertEquals(0, r.port.slotOverwrites)
    }

    @Test fun anAudioStartTimeoutIsAFailureNotACrash() {
        val r = Rig(bridge = true, role = Role.MAIN_PHONE)
        r.callStarts()
        r.sched.advance(TransportCallAudioBackend.STREAM_TIMEOUT_MS)
        assertEquals(AudioSessionState.FAILED, r.state)
    }
}
