package com.example.companion.ui

import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the incoming-call notification rules. WRITTEN BUT NOT RUN. */
class CallNotificationStateTest {

    private val on = NotificationAccessStatus.ON
    private val none: (String) -> String? = { null }

    private fun decide(
        main: Boolean = true, link: Boolean = true, state: CallState? = CallState.RINGING,
        visible: Boolean = false, access: NotificationAccessStatus = on
    ) = CallNotificationState.decide(main, link, state, visible, access)

    // ---- decide

    @Test fun aRingingCallOnABackgroundedMainPhoneShowsTheNotification() {
        assertEquals(CallNotifyDecision.SHOW, decide())
    }

    @Test fun everyStateExceptRingingRemovesIt() {
        CallState.values().filter { it != CallState.RINGING }.forEach {
            assertEquals("state $it", CallNotifyDecision.HIDE_NOT_RINGING, decide(state = it))
        }
        assertEquals(CallNotifyDecision.HIDE_NOT_RINGING, decide(state = null))      // no report yet = unknown
    }

    @Test fun aLostLinkRemovesItBecauseTheCallStateIsUnknown() {
        assertEquals(CallNotifyDecision.HIDE_LINK_DOWN, decide(link = false))
        assertEquals(CallNotifyDecision.HIDE_LINK_DOWN, decide(link = false, state = null))
    }

    @Test fun nothingIsShownWhileTheAppIsVisible() {
        assertEquals(CallNotifyDecision.HIDE_APP_VISIBLE, decide(visible = true))
    }

    @Test fun nothingIsShownOnTheSimPhone() {
        assertEquals(CallNotifyDecision.HIDE_NOT_MAIN_PHONE, decide(main = false))
        assertEquals(CallNotifyDecision.HIDE_NOT_MAIN_PHONE, decide(main = false, visible = true))
    }

    @Test fun nothingIsShownWhenAndroidDoesNotAllowIt() {
        NotificationAccessStatus.values().filter { it != on }.forEach {
            assertEquals(CallNotifyDecision.HIDE_NOTIFICATIONS_OFF, decide(access = it))
        }
    }

    // ---- actionAllowed

    @Test fun aButtonIsSentOnlyForARingingCallOverALiveLink() {
        assertTrue(CallNotificationState.actionAllowed(CallState.RINGING, linkUp = true, alreadyPending = false))
        assertFalse(CallNotificationState.actionAllowed(CallState.RINGING, linkUp = false, alreadyPending = false))
        assertFalse(CallNotificationState.actionAllowed(null, linkUp = true, alreadyPending = false))
        CallState.values().filter { it != CallState.RINGING }.forEach {
            assertFalse("state $it", CallNotificationState.actionAllowed(it, linkUp = true, alreadyPending = false))
        }
    }

    @Test fun aSecondPressWhileTheFirstIsWaitingIsIgnored() {
        assertFalse(CallNotificationState.actionAllowed(CallState.RINGING, linkUp = true, alreadyPending = true))
    }

    // ---- caller

    @Test fun callerIsTheNumberUnlessANameIsKnown() {
        assertEquals("+15551234", CallNotificationState.caller("+15551234", none))
        assertEquals("Alex", CallNotificationState.caller("+15551234") { "Alex" })
        assertEquals("+15551234", CallNotificationState.caller("+15551234") { "  " })
    }

    @Test fun aMissingNumberGetsTheSamePlaceholderAsTheCallScreen() {
        assertEquals(CallScreenState.UNKNOWN_NUMBER, CallNotificationState.caller(null, none))
        assertEquals(CallScreenState.UNKNOWN_NUMBER, CallNotificationState.caller("   ", none))
    }

    @Test fun theNumberIsASecondLineOnlyWhenANameIsShown() {
        assertEquals("+15551234", CallNotificationState.secondary("+15551234") { "Alex" })
        assertEquals("", CallNotificationState.secondary("+15551234", none))
        assertEquals("", CallNotificationState.secondary(null) { "Alex" })
        assertEquals("", CallNotificationState.secondary("Alex") { "Alex" })
    }

    // ---- content / status

    @Test fun identicalContentIsEqualSoItIsNotPostedTwice() {
        val a = CallNotificationState.content("+15551234", none, null, "")
        val b = CallNotificationState.content("+15551234", none, null, "")
        assertEquals(a, b)
        assertNotEquals(a, CallNotificationState.content("+15551234", none, CallNotificationAction.ANSWER, ""))
        assertNotEquals(a, CallNotificationState.content("+15559999", none, null, ""))
    }

    @Test fun statusFollowsThePressThenTheFailureThenPlainRinging() {
        fun status(p: CallNotificationAction?, f: String) = CallNotificationState.statusText(CallNotificationState.content("1", none, p, f))
        assertEquals("Answering…", status(CallNotificationAction.ANSWER, ""))
        assertEquals("Rejecting…", status(CallNotificationAction.REJECT, ""))
        assertEquals("Answer failed: x", status(null, "Answer failed: x"))
        assertEquals("Incoming call", status(null, ""))
    }

    @Test fun buttonsAreHiddenOnlyWhileAPressIsWaiting() {
        assertTrue(CallNotificationState.showButtons(CallNotificationState.content("1", none, null, "")))
        assertTrue(CallNotificationState.showButtons(CallNotificationState.content("1", none, null, "Answer failed: x")))
        assertFalse(CallNotificationState.showButtons(CallNotificationState.content("1", none, CallNotificationAction.REJECT, "")))
    }

    @Test fun theActionNamesRoundTripAndForeignNamesAreIgnored() {
        CallNotificationAction.values().forEach {
            assertEquals(it, CallNotificationState.actionFrom(CallNotificationState.actionName(it)))
        }
        assertEquals(null, CallNotificationState.actionFrom("android.intent.action.VIEW"))
        assertEquals(null, CallNotificationState.actionFrom(null))
    }

    // ---- Stage 17: the same ring after a Bluetooth reconnect is not announced twice

    @Test fun sameNumberBackWithinTheWindowIsTheSameRing() {
        val m = CallNotificationState.RingMemory("+15551234", removedAt = 1_000)
        assertTrue(CallNotificationState.isResumedRing(m, "+15551234", now = 1_000 + 30_000))
    }

    @Test fun differentNumberOrLateReturnAlertsAgain() {
        val m = CallNotificationState.RingMemory("+15551234", removedAt = 1_000)
        assertFalse(CallNotificationState.isResumedRing(m, "+15559999", now = 2_000))
        assertFalse(CallNotificationState.isResumedRing(m, "+15551234", now = 1_000 + CallNotificationState.RING_RESUME_WINDOW_MS + 1))
        assertFalse(CallNotificationState.isResumedRing(null, "+15551234", now = 2_000))
    }

    @Test fun twoWithheldNumbersCountAsTheSameRing() {
        assertTrue(CallNotificationState.isResumedRing(CallNotificationState.RingMemory(null, 0), null, now = 10))
    }
}
