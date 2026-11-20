package com.example.companion.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Stage 17 retry rules. WRITTEN BUT NOT RUN. */
class ReconnectPolicyTest {

    @Test fun clientBackoffGrowsThenStaysAtTheCap() {
        val d = (1..8).map { ReconnectPolicy.delayMs(it, isHost = false) }
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L, 30_000L, 30_000L), d)
    }

    @Test fun hostBackoffStartsFasterAndSharesTheCap() {
        val d = (1..7).map { ReconnectPolicy.delayMs(it, isHost = true) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), d)
    }

    @Test fun absurdAttemptNumbersDoNotOverflow() {
        assertEquals(30_000L, ReconnectPolicy.delayMs(10_000, isHost = false))
        assertEquals(3_000L, ReconnectPolicy.delayMs(0, isHost = false))
    }

    @Test fun firstConnectGivesUpAfterTheFastAttempts() {
        assertTrue(ReconnectPolicy.canRetry(ReconnectPolicy.FAST_ATTEMPTS - 1, linkWasUp = false, sinceLossMs = 0))
        assertFalse(ReconnectPolicy.canRetry(ReconnectPolicy.FAST_ATTEMPTS, linkWasUp = false, sinceLossMs = 0))
    }

    @Test fun lostLinkKeepsRetryingInsideTheWindowOnly() {
        assertTrue(ReconnectPolicy.canRetry(50, linkWasUp = true, sinceLossMs = 60_000))
        assertTrue(ReconnectPolicy.canRetry(50, linkWasUp = true, sinceLossMs = ReconnectPolicy.LOST_LINK_WINDOW_MS - 1))
        assertFalse(ReconnectPolicy.canRetry(50, linkWasUp = true, sinceLossMs = ReconnectPolicy.LOST_LINK_WINDOW_MS))
    }

    @Test fun attemptTextHasNoTotalOnceTheCountIsUnbounded() {
        assertEquals("Attempt 2 of 6", ReconnectPolicy.attemptText(2))
        assertEquals("Attempt 6 of 6", ReconnectPolicy.attemptText(6))
        assertEquals("Attempt 7", ReconnectPolicy.attemptText(7))
    }

    @Test fun lateAclBroadcastRightAfterConnectingIsIgnored() {
        assertFalse(ReconnectPolicy.aclDisconnectApplies(100))
        assertTrue(ReconnectPolicy.aclDisconnectApplies(ReconnectPolicy.ACL_SETTLE_MS))
    }

    // ---- Stage 25: a link that connects and drops again at once must back off

    @Test fun stableLinkResetsQuickDrops() {
        assertEquals(0, ReconnectPolicy.nextQuickDrops(4, ReconnectPolicy.STABLE_LINK_MS))
        assertEquals(0, ReconnectPolicy.nextQuickDrops(0, 5 * 60_000L))
    }

    @Test fun quickDropsAccumulateUpToACap() {
        assertEquals(1, ReconnectPolicy.nextQuickDrops(0, 1_500))
        assertEquals(3, ReconnectPolicy.nextQuickDrops(2, ReconnectPolicy.STABLE_LINK_MS - 1))
        assertEquals(10, ReconnectPolicy.nextQuickDrops(10, 100))
    }

    @Test fun flappingLinkGetsLongerDelaysForTheFirstRetry() {
        // ConnectionManager adds quickDrops to the attempt number (the first retry after a drop is attempt 1).
        val d = (0..4).map { ReconnectPolicy.delayMs(1 + it, isHost = false) }
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L), d)
        assertEquals(30_000L, ReconnectPolicy.delayMs(1 + 10, isHost = false))
    }
}
