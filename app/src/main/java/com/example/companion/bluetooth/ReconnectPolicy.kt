package com.example.companion.bluetooth

/**
 * Stage 17: the retry / backoff rules of [ConnectionManager], as pure functions (no Android types) so they can be
 * unit-tested on a plain JVM. [ConnectionManager] stays the only thing that actually retries.
 *
 * Two situations are treated differently:
 *  - A connection that was NEVER up in this session (the user pressed Connect and nothing answered): [FAST_ATTEMPTS]
 *    attempts, then give up and show the error. Same as before Stage 17.
 *  - A connection that WAS up and was lost (out of range, remote app restarted, Bluetooth toggled): keep trying with the
 *    capped backoff until the link is back or [LOST_LINK_WINDOW_MS] have passed since the loss. A cellular call on the
 *    SIM Phone may still be running, so one minute and a half of attempts is too short to be useful.
 */
object ReconnectPolicy {
    /** Attempts made with growing delays before the delay stays at [MAX_DELAY_MS]. Also the give-up limit for a first connect. */
    const val FAST_ATTEMPTS = 6
    const val MAX_DELAY_MS = 30_000L
    /** How long after losing an established link the Main Phone keeps retrying before showing an error. */
    const val LOST_LINK_WINDOW_MS = 30L * 60 * 1000

    private const val HOST_BASE_MS = 1_000L      // SIM Phone: just listens again, nothing to wait for
    private const val CLIENT_BASE_MS = 3_000L    // Main Phone: every attempt is a real connect()

    /** Delay before retry number [attempt] (1-based): client 3s, 6s, 12s, 24s, 30s, 30s, ...; host 1s, 2s, 4s, 8s, 16s, 30s, ... */
    fun delayMs(attempt: Int, isHost: Boolean): Long {
        val base = if (isHost) HOST_BASE_MS else CLIENT_BASE_MS
        val shift = (attempt - 1).coerceIn(0, 10)       // coerce: no overflow for absurd attempt numbers
        return minOf(base shl shift, MAX_DELAY_MS)
    }

    /**
     * May another attempt be scheduled? [attemptsMade] = retries already scheduled in this episode,
     * [linkWasUp] = the connection had been up before this episode, [sinceLossMs] = time since it was lost.
     */
    fun canRetry(attemptsMade: Int, linkWasUp: Boolean, sinceLossMs: Long): Boolean =
        if (linkWasUp) sinceLossMs < LOST_LINK_WINDOW_MS else attemptsMade < FAST_ATTEMPTS

    /** Text for the "Reconnecting" line: "Attempt 2 of 6" while the count is bounded, "Attempt 9" in the long phase. */
    fun attemptText(attempt: Int): String =
        if (attempt in 1..FAST_ATTEMPTS) "Attempt $attempt of $FAST_ATTEMPTS" else "Attempt $attempt"

    /** Stage 25: a link that stayed up at least this long counts as a real connection; one that dropped sooner is a "quick drop". */
    const val STABLE_LINK_MS = 10_000L
    private const val MAX_QUICK_DROPS = 10

    /**
     * Quick drops in a row, after a link was lost having been up for [upMs]. A stable link resets the count to 0; a link that
     * dropped almost at once adds one. The count is added to the attempt number when the retry delay is chosen, so a link that
     * connects and drops again and again backs off instead of being re-tried every few seconds forever.
     */
    fun nextQuickDrops(current: Int, upMs: Long): Int =
        if (upMs >= STABLE_LINK_MS) 0 else (current + 1).coerceAtMost(MAX_QUICK_DROPS)

    /**
     * Stage 27: the Main Phone stops retrying after this many quick drops IN A ROW. Without it a link that connects and drops again within
     * [STABLE_LINK_MS], over and over, restarted the [LOST_LINK_WINDOW_MS] window on every connect and was re-tried (slowly) for ever.
     * The delay between those attempts is already capped at [MAX_DELAY_MS], so this is a limit of roughly five to ten minutes of flapping,
     * after which the person is told the link is unstable and presses Connect. Not applied to the SIM Phone: it only listens again
     * (no radio connect), and an always-on SIM Phone must not stop listening because a Main Phone is at the edge of range.
     */
    fun flapLimitReached(quickDrops: Int): Boolean = quickDrops >= MAX_QUICK_DROPS

    /** A late ACL-disconnect broadcast from the PREVIOUS link must not kill a link that was just established. */
    const val ACL_SETTLE_MS = 2_000L
    fun aclDisconnectApplies(sinceConnectedMs: Long): Boolean = sinceConnectedMs >= ACL_SETTLE_MS
}
