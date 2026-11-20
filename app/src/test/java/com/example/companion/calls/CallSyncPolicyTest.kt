package com.example.companion.calls

import com.example.companion.protocol.CallMessages
import com.example.companion.protocol.CallState
import com.example.companion.protocol.Message
import com.example.companion.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Stage 28 sync schedule and request message. WRITTEN BUT NOT RUN. */
class CallSyncPolicyTest {

    private fun next(link: Boolean = true, reported: Boolean = false, retries: Int = 0, state: CallState? = null) =
        CallSyncPolicy.nextDelayMs(link, reported, retries, state)

    @Test fun noLinkNeverAsks() {
        assertNull(next(link = false)); assertNull(next(link = false, reported = true, state = CallState.ACTIVE))
    }

    @Test fun beforeAnyReportItRetriesAFewTimesThenStops() {
        assertEquals(CallSyncPolicy.RETRY_DELAYS_MS[0], next(retries = 0))
        assertEquals(CallSyncPolicy.RETRY_DELAYS_MS[1], next(retries = 1))
        assertEquals(CallSyncPolicy.RETRY_DELAYS_MS[2], next(retries = 2))
        assertNull(next(retries = CallSyncPolicy.RETRY_DELAYS_MS.size))
    }

    @Test fun retriesAreSpacedOutAndShort() {
        assertTrue(CallSyncPolicy.RETRY_DELAYS_MS.toList().zipWithNext().all { (a, b) -> b > a })
        assertTrue(CallSyncPolicy.RETRY_DELAYS_MS.all { it < CallSyncPolicy.REFRESH_MS })
    }

    @Test fun duringALiveCallItRefreshesPeriodically() {
        listOf(CallState.RINGING, CallState.CONNECTING, CallState.SELECT_ACCOUNT, CallState.DIALING,
            CallState.ACTIVE, CallState.HOLDING, CallState.DISCONNECTING).forEach {
            assertEquals("state $it", CallSyncPolicy.REFRESH_MS, next(reported = true, state = it))
        }
    }

    @Test fun withNoCallItStaysQuietAfterTheFirstReport() {
        listOf(null, CallState.IDLE, CallState.DISCONNECTED).forEach { assertNull("state $it", next(reported = true, state = it)) }
    }

    @Test fun syncRequestIsAnEmptyReadOnlyMessageThatSurvivesTheWire() {
        val m = CallMessages.syncRequest()
        assertEquals(MessageType.CALL_SYNC_REQUEST, m.type)
        assertEquals(0, m.payload.length())
        assertEquals(MessageType.CALL_SYNC_REQUEST, Message.decode(m.encode())?.type)
    }

    @Test fun aSyncRequestIsNotParsedAsACallReport() {
        assertNull(CallMessages.parseStatus(CallMessages.syncRequest()))
    }

    @Test fun anUnknownTypeFromANewerBuildIsDroppedNotFatal() {
        val raw = """{"v":1,"type":"CALL_SOMETHING_NEW","payload":{}}""".toByteArray()
        assertNull(Message.decode(raw))
    }
}
