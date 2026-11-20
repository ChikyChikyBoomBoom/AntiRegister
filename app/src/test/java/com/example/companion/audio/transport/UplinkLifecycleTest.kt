package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 40 JVM tests of [UplinkLifecycle] (over the real [UplinkController]) with a fake transport port, source and environment.
 * WRITTEN BUT NOT RUN: no Kotlin compiler / Gradle was available when authored. [AudioTransport] needs a Handler and is not JVM-testable.
 */
class UplinkLifecycleTest {

    private class FakeSource : DirectionalAudioSource {
        override val name = "fake uplink mic"
        override val direction = AudioDirection.MAIN_TO_SIM
        var starts = 0; var stops = 0
        override fun start(config: AudioConfig, listener: AudioSource.Listener): String? { starts++; return null }
        override fun stop() { stops++ }
    }

    private class FakePort(var direction: AudioDirection? = AudioDirection.MAIN_TO_SIM) : UplinkTransportPort {
        override var isLinkUp = true
        override val localDirection get() = direction
        override var isSending = false
        override var callUplinkRxEnabled = false
        var incomingUplink = false
        override val isReceivingUplink get() = callUplinkRxEnabled && incomingUplink
        var receiveStops = 0
        override fun stopReceivingUplink() { receiveStops++; incomingUplink = false }
        val started = mutableListOf<Pair<AudioSource, AudioConfig>>()
        val stops = mutableListOf<Boolean>()                  // userInitiated flag of each stopSending
        var slotHolder: ((AudioStartOutcome) -> Unit)? = null
        var foreignSlot = false
        var onStopSending: (() -> Unit)? = null
        override fun startSending(source: AudioSource, config: AudioConfig): String? {
            started += source to config; isSending = true; return null
        }
        override fun stopSending(reason: String?, userInitiated: Boolean) { stops += userInitiated; isSending = false; onStopSending?.invoke() }
        override fun claimOutcomeListener(listener: (AudioStartOutcome) -> Unit): Boolean {
            if (foreignSlot) return false
            slotHolder = listener; return true
        }
        override fun releaseOutcomeListener() { slotHolder = null }
        fun outcome(o: AudioStartOutcome) { slotHolder!!.invoke(o) }
    }

    private class FakeEnv : UplinkEnvironment {
        var call: Boolean? = true
        var link = true
        var permission: Boolean? = true
        var permissionThrows = false
        var visible = true
        var busy = false
        var headset: Boolean? = true
        override fun callActive() = call
        override fun linkUp() = link
        override fun microphonePermission(): Boolean? { if (permissionThrows) throw SecurityException("boom"); return permission }
        override fun appVisible() = visible
        override fun microphoneBusyElsewhere() = busy
        override fun headsetOutputPresent() = headset
    }

    private class Rig(flagOn: Boolean = true, direction: AudioDirection? = AudioDirection.MAIN_TO_SIM) {
        val port = FakePort(direction)
        val src = FakeSource()
        val env = FakeEnv()
        val controller = UplinkController(port, src)
        val lifecycle = UplinkLifecycle(controller, env, UplinkFlags(uplinkBridge = flagOn)) { port.direction }

        /** Explicit user start + the SIM Phone's READY (what the real transport would report). */
        fun startAndReady(): UplinkStartResult {
            val r = lifecycle.start()
            if (r.started) port.outcome(AudioStartOutcome.Ready)
            return r
        }
    }

    // ------------------------------------------------------------------ default-disabled flags

    @Test fun flagsAreOffAndTwoWayIsNoByDefault() {
        val f = UplinkFlags()
        assertFalse(f.uplinkBridge)
        assertFalse(f.downlinkBridge)
        assertEquals("NO", f.twoWayCellularAudio)
        assertEquals(listOf("uplinkBridge = false", "downlinkBridge = false", "twoWayCellularAudio = NO"), f.lines())
    }

    @Test fun withTheFlagOffStartIsRefusedAndNothingIsTouched() {
        val r = Rig(flagOn = false)
        val res = r.lifecycle.start()
        assertEquals(UplinkBlock.FLAG_OFF, res.block)
        assertFalse(res.started)
        assertTrue(r.port.started.isEmpty())
        assertEquals(UplinkLifecycleState.DISABLED, r.lifecycle.state())
        assertEquals(0, r.src.starts)
    }

    @Test fun withTheFlagOffEveryEnvironmentChangeIsHarmlessAndNothingIsArmed() {
        val main = Rig(flagOn = false)
        main.lifecycle.onEnvironmentChanged()
        assertTrue(main.port.started.isEmpty())
        val sim = Rig(flagOn = false, direction = AudioDirection.SIM_TO_MAIN)
        sim.lifecycle.onEnvironmentChanged()
        assertFalse(sim.port.callUplinkRxEnabled)
    }

    // ------------------------------------------------------------------ explicit activation

    @Test fun nothingStartsByItselfEvenWithTheFlagOnAndEverythingReady() {
        val r = Rig()
        r.lifecycle.onEnvironmentChanged()
        r.lifecycle.onEnvironmentChanged()
        assertTrue(r.port.started.isEmpty())
        assertEquals(UplinkLifecycleState.IDLE, r.lifecycle.state())
        assertFalse(r.lifecycle.microphoneActive)
        assertNull(r.lifecycle.preflight())          // it COULD start, but only a user action does
    }

    @Test fun startOnlyNegotiatesAndTheMicrophoneIsActiveOnlyAfterReady() {
        val r = Rig()
        val res = r.lifecycle.start()
        assertTrue(res.started)
        assertEquals(UplinkLifecycleState.NEGOTIATING, r.lifecycle.state())
        assertFalse(r.lifecycle.microphoneActive)
        assertEquals(0, r.src.starts)
        r.port.outcome(AudioStartOutcome.Ready)
        assertEquals(UplinkLifecycleState.MICROPHONE_ACTIVE, r.lifecycle.state())
        assertTrue(r.lifecycle.microphoneActive)
    }

    @Test fun peerRefusalMeansNotSupportedAndTheMicrophoneNeverOpens() {
        val r = Rig()
        r.lifecycle.start()
        r.port.outcome(AudioStartOutcome.Refused(UplinkMessages.NOT_SUPPORTED))
        assertEquals(UplinkLifecycleState.NOT_SUPPORTED, r.lifecycle.state())
        assertFalse(r.lifecycle.microphoneActive)
        assertEquals(0, r.src.starts)
        assertFalse(r.lifecycle.snapshot().capability.isSupported)
        assertEquals(UplinkMessages.NOT_SUPPORTED, r.lifecycle.snapshot().capability.headline)
        assertEquals(listOf(true), r.port.stops)     // automatic resume cleared
        assertNull(r.port.slotHolder)
    }

    @Test fun userStopIsRememberedAsNoReason() {
        val r = Rig()
        r.startAndReady()
        r.lifecycle.stop()
        assertNull(r.lifecycle.lastStopReason)
        assertEquals(UplinkLifecycleState.IDLE, r.lifecycle.state())
        assertFalse(r.port.isSending)
    }

    // ------------------------------------------------------------------ permission denial

    @Test fun deniedPermissionBlocksStartAndNothingIsStarted() {
        val r = Rig(); r.env.permission = false
        val res = r.lifecycle.start()
        assertEquals(UplinkBlock.PERMISSION, res.block)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun unknownOrThrowingPermissionCountsAsDenied() {
        val r = Rig(); r.env.permission = null
        assertEquals(UplinkBlock.PERMISSION, r.lifecycle.start().block)
        r.env.permissionThrows = true
        assertEquals(UplinkBlock.PERMISSION, r.lifecycle.start().block)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun grantingThePermissionDoesNotStartByItself() {
        val r = Rig(); r.env.permission = false
        r.lifecycle.start()
        r.env.permission = true
        r.lifecycle.onEnvironmentChanged()
        assertTrue(r.port.started.isEmpty())
        assertTrue(r.lifecycle.start().started)          // only the user's next press starts it
        assertEquals(1, r.port.started.size)
    }

    @Test fun revokedPermissionWhileRunningStopsTheUplink() {
        val r = Rig(); r.startAndReady()
        r.env.permission = false
        r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.isSending)
        assertEquals(UplinkBlock.PERMISSION.text, r.lifecycle.lastStopReason)
    }

    // ------------------------------------------------------------------ call termination

    @Test fun startIsRefusedWithoutAnActiveCall() {
        val r = Rig()
        r.env.call = false
        assertEquals(UplinkBlock.NO_ACTIVE_CALL, r.lifecycle.start().block)
        r.env.call = null                                // unknown is not "active"
        assertEquals(UplinkBlock.NO_ACTIVE_CALL, r.lifecycle.start().block)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun callEndStopsTheMicrophoneImmediately() {
        val r = Rig(); r.startAndReady()
        r.env.call = false
        r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.isSending)
        assertEquals(listOf(true), r.port.stops)         // user-initiated stop: no automatic resume
        assertEquals(UplinkLifecycleState.IDLE, r.lifecycle.state())
        assertFalse(r.lifecycle.microphoneActive)
        assertEquals(UplinkBlock.NO_ACTIVE_CALL.text, r.lifecycle.lastStopReason)
        assertNull(r.port.slotHolder)
    }

    @Test fun callEndWhileNegotiatingStopsBeforeTheMicrophoneEverOpens() {
        val r = Rig(); r.lifecycle.start()
        r.env.call = false
        r.lifecycle.onEnvironmentChanged()
        assertEquals(UplinkLifecycleState.IDLE, r.lifecycle.state())
        assertEquals(0, r.src.starts)
        assertEquals(listOf(true), r.port.stops)
    }

    @Test fun uplinkDoesNotRestartWhenANewCallBecomesActive() {
        val r = Rig(); r.startAndReady()
        r.env.call = false; r.lifecycle.onEnvironmentChanged()
        r.env.call = true; r.lifecycle.onEnvironmentChanged()
        assertEquals(1, r.port.started.size)
        assertEquals(UplinkLifecycleState.IDLE, r.lifecycle.state())
    }

    // ------------------------------------------------------------------ Bluetooth disconnection

    @Test fun startIsRefusedWithoutBluetooth() {
        val r = Rig(); r.env.link = false
        assertEquals(UplinkBlock.NO_LINK, r.lifecycle.start().block)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun bluetoothLossStopsTheMicrophoneAndItDoesNotComeBackOnReconnect() {
        val r = Rig(); r.startAndReady()
        r.env.link = false; r.port.isLinkUp = false
        r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.isSending)
        assertEquals(listOf(true), r.port.stops)
        assertEquals(UplinkBlock.NO_LINK.text, r.lifecycle.lastStopReason)
        r.env.link = true; r.port.isLinkUp = true
        r.lifecycle.onEnvironmentChanged()
        assertEquals(1, r.port.started.size)             // no automatic restart
        assertEquals(UplinkLifecycleState.IDLE, r.lifecycle.state())
    }

    @Test fun linkDownOutcomeFromTheTransportAlsoEndsTheRun() {
        val r = Rig(); r.startAndReady()
        r.port.outcome(AudioStartOutcome.LinkDown)
        assertEquals(UplinkLifecycleState.FAILED, r.lifecycle.state())
        assertFalse(r.lifecycle.microphoneActive)
        assertEquals(listOf(true), r.port.stops)
    }

    // ------------------------------------------------------------------ background restrictions

    @Test fun startIsRefusedInTheBackground() {
        val r = Rig(); r.env.visible = false
        assertEquals(UplinkBlock.BACKGROUND, r.lifecycle.start().block)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun goingToTheBackgroundStopsTheMicrophoneImmediately() {
        val r = Rig(); r.startAndReady()
        r.env.visible = false
        r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.isSending)
        assertEquals(UplinkBlock.BACKGROUND.text, r.lifecycle.lastStopReason)
        r.env.visible = true
        r.lifecycle.onEnvironmentChanged()
        assertEquals(1, r.port.started.size)             // coming back does not restart it
    }

    // ------------------------------------------------------------------ start / stop races and duplicates

    @Test fun duplicateActivationIsRefusedWhileNegotiatingAndWhileStreaming() {
        val r = Rig()
        assertTrue(r.lifecycle.start().started)
        assertEquals(UplinkBlock.ALREADY_RUNNING, r.lifecycle.start().block)
        r.port.outcome(AudioStartOutcome.Ready)
        assertEquals(UplinkBlock.ALREADY_RUNNING, r.lifecycle.start().block)
        assertEquals(1, r.port.started.size)
    }

    @Test fun anotherOutgoingStreamBlocksStartAndIsNeverStopped() {
        val r = Rig(); r.port.isSending = true
        val res = r.lifecycle.start()
        assertEquals(UplinkBlock.REFUSED, res.block)
        assertEquals("Another outgoing audio stream is already running", res.message)
        assertTrue(r.port.started.isEmpty())
        r.lifecycle.stop()
        assertTrue(r.port.stops.isEmpty())               // a foreign stream is not ours to stop
        assertTrue(r.port.isSending)
    }

    @Test fun anotherOwnerOfTheStartSlotBlocksStart() {
        val r = Rig(); r.port.foreignSlot = true
        val res = r.lifecycle.start()
        assertEquals(UplinkBlock.REFUSED, res.block)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun microphoneUsedByAnotherFeatureBlocksStart() {
        val r = Rig(); r.env.busy = true
        assertEquals(UplinkBlock.MICROPHONE_BUSY, r.lifecycle.start().block)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun stopRightAfterStartThenAgainIsClean() {
        val r = Rig(); r.lifecycle.start()
        r.lifecycle.stop()
        assertEquals(listOf(true), r.port.stops)
        assertNull(r.port.slotHolder)                    // a late READY / refusal can no longer reach the controller
        r.lifecycle.stop()                               // idempotent
        assertEquals(listOf(true), r.port.stops)
        assertTrue(r.lifecycle.start().started)          // and a fresh start works
        assertEquals(2, r.port.started.size)
    }

    @Test fun startStopStartWithAStaleSlotNeverRevivesTheOldRun() {
        val r = Rig(); r.lifecycle.start()
        val oldHolder = r.port.slotHolder
        r.lifecycle.stop()
        r.lifecycle.start()
        assertNotNull(oldHolder)
        assertEquals(UplinkLifecycleState.NEGOTIATING, r.lifecycle.state())
        r.port.outcome(AudioStartOutcome.Ready)
        assertEquals(UplinkLifecycleState.MICROPHONE_ACTIVE, r.lifecycle.state())
    }

    @Test fun anEnvironmentChangeInsideStopDoesNotRecurse() {
        val r = Rig(); r.startAndReady()
        r.port.onStopSending = { r.lifecycle.onEnvironmentChanged() }     // the transport calls back while stopping
        r.env.call = false
        r.lifecycle.onEnvironmentChanged()
        assertEquals(1, r.port.stops.size)
        assertEquals(UplinkLifecycleState.IDLE, r.lifecycle.state())
    }

    @Test fun aStreamThatEndsByItselfIsNoticedAndExplained() {
        val r = Rig(); r.startAndReady()
        r.port.isSending = false                         // microphone died / peer stopped, no outcome callback
        r.lifecycle.onEnvironmentChanged()
        assertEquals(UplinkLifecycleState.IDLE, r.lifecycle.state())
        assertFalse(r.lifecycle.microphoneActive)
        assertEquals("Uplink ended", r.lifecycle.lastStopReason)
        assertEquals(listOf(true), r.port.stops)         // Stage 41: no automatic resume after a reconnect
    }

    @Test fun startingAgainClearsTheOldStopReason() {
        val r = Rig(); r.startAndReady()
        r.env.call = false; r.lifecycle.onEnvironmentChanged()
        assertNotNull(r.lifecycle.lastStopReason)
        r.env.call = true
        r.lifecycle.start()
        assertNull(r.lifecycle.lastStopReason)
    }

    // ------------------------------------------------------------------ echo

    @Test fun withoutAHeadsetStartNeedsTheEchoWarningToBeAcknowledged() {
        val r = Rig(); r.env.headset = false
        assertEquals(UplinkBlock.ECHO_RISK, r.lifecycle.start().block)
        assertTrue(r.port.started.isEmpty())
        assertTrue(r.lifecycle.start(echoAcknowledged = true).started)
    }

    @Test fun unknownHeadsetStateIsTreatedLikeNoHeadset() {
        val r = Rig(); r.env.headset = null
        assertEquals(UplinkBlock.ECHO_RISK, r.lifecycle.start().block)
    }

    @Test fun withAHeadsetStartNeedsNoAcknowledgement() {
        val r = Rig(); r.env.headset = true
        assertTrue(r.lifecycle.start().started)
    }

    // ------------------------------------------------------------------ resource cleanup / shutdown

    @Test fun shutdownReleasesEverythingAndRefusesLaterStarts() {
        val r = Rig(); r.startAndReady()
        r.lifecycle.shutdown()
        assertFalse(r.port.isSending)
        assertEquals(listOf(true), r.port.stops)
        assertNull(r.port.slotHolder)
        assertFalse(r.port.callUplinkRxEnabled)
        assertEquals(UplinkLifecycleState.SHUT_DOWN, r.lifecycle.state())
        assertFalse(r.lifecycle.microphoneActive)
        assertEquals(UplinkBlock.SHUT_DOWN, r.lifecycle.start().block)
        r.lifecycle.shutdown()                           // idempotent
        assertEquals(listOf(true), r.port.stops)
    }

    @Test fun environmentChangesAfterShutdownDoNothing() {
        val r = Rig(direction = AudioDirection.SIM_TO_MAIN)
        r.lifecycle.shutdown()
        r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.callUplinkRxEnabled)
    }

    // ------------------------------------------------------------------ SIM Phone side

    @Test fun simPhoneArmsOnlyWhileFlagLinkAndCallAreAllPresent() {
        val r = Rig(direction = AudioDirection.SIM_TO_MAIN)
        r.lifecycle.onEnvironmentChanged()
        assertTrue(r.port.callUplinkRxEnabled)
        r.env.call = false; r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.callUplinkRxEnabled)
        r.env.call = true; r.env.link = false; r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.callUplinkRxEnabled)
        r.env.link = true; r.env.call = null; r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.callUplinkRxEnabled)
    }

    @Test fun simPhoneDisarmingEndsAnIncomingUplinkStream() {
        val r = Rig(direction = AudioDirection.SIM_TO_MAIN)
        r.lifecycle.onEnvironmentChanged()
        r.port.incomingUplink = true
        assertTrue(r.lifecycle.snapshot().receivingUplink)
        r.env.call = false; r.lifecycle.onEnvironmentChanged()
        assertEquals(1, r.port.receiveStops)
        assertFalse(r.lifecycle.snapshot().receivingUplink)
    }

    @Test fun simPhoneNeverStartsTheMicrophone() {
        val r = Rig(direction = AudioDirection.SIM_TO_MAIN)
        assertEquals(UplinkBlock.NOT_MAIN_PHONE, r.lifecycle.start().block)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun mainPhoneNeverArmsTheReceiver() {
        val r = Rig()
        r.lifecycle.onEnvironmentChanged()
        assertFalse(r.port.callUplinkRxEnabled)
    }

    @Test fun noRoleBlocksStart() {
        val r = Rig(direction = null)
        assertEquals(UplinkBlock.NO_ROLE, r.lifecycle.start().block)
    }

    // ------------------------------------------------------------------ reporting

    @Test fun listenersAreToldAboutChangesAndCanBeRemoved() {
        val r = Rig()
        var n = 0
        val l = object : UplinkLifecycle.Listener { override fun onUplinkChanged() { n++ } }
        r.lifecycle.addListener(l)
        r.lifecycle.start()
        r.lifecycle.stop()
        assertTrue(n >= 2)
        r.lifecycle.removeListener(l)
        val before = n
        r.lifecycle.onEnvironmentChanged()
        assertEquals(before, n)
    }

    @Test fun snapshotReportsTheFactsWithoutAnythingBeingStarted() {
        val r = Rig(); r.env.permission = false; r.env.headset = false
        val s = r.lifecycle.snapshot()
        assertEquals(UplinkLifecycleState.IDLE, s.state)
        assertEquals(UplinkBlock.PERMISSION, s.startBlock)
        assertEquals(false, s.microphonePermission)
        assertEquals(false, s.headsetOutputPresent)
        assertEquals(true, s.callActive)
        assertTrue(s.linkUp)
        assertFalse(s.microphoneActive)
        assertTrue(r.port.started.isEmpty())
    }

    @Test fun anEnvironmentThatThrowsFailsSafe() {
        val r = Rig()
        val throwing = object : UplinkEnvironment {
            override fun callActive(): Boolean? = throw IllegalStateException()
            override fun linkUp(): Boolean = throw IllegalStateException()
            override fun microphonePermission(): Boolean? = throw IllegalStateException()
            override fun appVisible(): Boolean = throw IllegalStateException()
            override fun microphoneBusyElsewhere(): Boolean = throw IllegalStateException()
            override fun headsetOutputPresent(): Boolean? = throw IllegalStateException()
        }
        val lc = UplinkLifecycle(r.controller, throwing, UplinkFlags(uplinkBridge = true)) { r.port.direction }
        assertNotNull(lc.start().block)
        assertTrue(r.port.started.isEmpty())
    }
}
