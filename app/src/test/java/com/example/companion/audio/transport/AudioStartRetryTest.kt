package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 33B JVM tests. WRITTEN BUT NOT RUN: no Gradle / Android SDK / device was available when this stage was authored.
 * Virtual time, fakes only, no threads, no Android classes (AudioTransport / Handler are not involved).
 */
class AudioStartRetryTest {

    /** Virtual clock. [advance] runs every non-cancelled task that became due, in order. */
    private class FakeScheduler : RetryScheduler {
        class Task(val at: Long, val run: Runnable) { var cancelled = false }
        var now = 0L
        val tasks = ArrayList<Task>()
        val delays = ArrayList<Long>()
        override fun postDelayed(delayMs: Long, task: Runnable): RetryTimer {
            delays += delayMs
            val t = Task(now + delayMs, task); tasks += t
            return RetryTimer { t.cancelled = true }
        }
        val pending get() = tasks.count { !it.cancelled && it.at >= 0 }
        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = tasks.filter { !it.cancelled && it.at <= now }.minByOrNull { it.at } ?: break
                tasks.remove(due); due.run.run()
            }
        }
        /** Misbehaving scheduler: runs tasks even though they were cancelled. */
        fun fireEvenCancelled() { val all = tasks.toList(); tasks.clear(); all.forEach { it.run.run() } }
    }

    private class FakeSource : AudioSource {
        override val name = "fake"
        override fun start(config: AudioConfig, listener: AudioSource.Listener): String? = null
        override fun stop() {}
    }

    private class RecListener : AudioStartRetry.Listener {
        val retries = ArrayList<Pair<Int, Long>>()
        val finished = ArrayList<Pair<AudioStartOutcome, Int>>()
        override fun onRetryScheduled(afterAttempt: Int, delayMs: Long) { retries += afterAttempt to delayMs }
        override fun onFinished(outcome: AudioStartOutcome, attempts: Int) { finished += outcome to attempts }
    }

    private class Env {
        val scheduler = FakeScheduler()
        val sent = ArrayList<AudioStartRequest>()
        var linkUp = true
        var active = true
        var startError: String? = null
        var aborts = 0
        val listener = RecListener()
        val retry = AudioStartRetry(scheduler, { linkUp }, { active }, { sent += it; startError }, { aborts++ })
        val request = AudioStartRequest(FakeSource(), AudioConfig(sampleRate = 16_000))
        fun begin() = retry.begin(request, listener)
    }

    private val notListening = AudioStartOutcome.Refused(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN)

    // 1 ------------------------------------------------------------------------------------------------------------
    @Test fun notListeningTriggersRetry() {
        val e = Env()
        assertTrue(e.begin())
        assertEquals(1, e.sent.size)                       // first AUDIO_START goes out immediately
        e.retry.onOutcome(notListening)
        assertEquals(1, e.sent.size)                       // not repeated before the delay
        assertEquals(1, e.scheduler.pending)
        e.scheduler.advance(500)
        assertEquals(2, e.sent.size)
        assertEquals(listOf(1 to 500L), e.listener.retries)
        assertTrue(e.retry.isRunning)
        assertTrue(e.listener.finished.isEmpty())
    }

    @Test fun retriesKeepGoingWhileNotListening() {
        val e = Env(); e.begin()
        repeat(5) { e.retry.onOutcome(notListening); e.scheduler.advance(500) }
        assertEquals(6, e.sent.size)
        assertEquals(6, e.retry.attempts)
        assertTrue(e.retry.isRunning)
    }

    @Test fun onlyNotListeningRefusalsAreRetried() {
        for (o in listOf(AudioStartOutcome.Refused("No role selected"), AudioStartOutcome.Refused(null),
                         AudioStartOutcome.TimedOut, AudioStartOutcome.SourceFailed("AudioRecord init failed"))) {
            val e = Env(); e.begin()
            e.retry.onOutcome(o)
            assertEquals(0, e.scheduler.pending)
            assertEquals(1, e.sent.size)
            assertEquals(1, e.listener.finished.size)
            assertSame(o, e.listener.finished[0].first)
            assertFalse(e.retry.isRunning)
        }
    }

    // 2 ------------------------------------------------------------------------------------------------------------
    @Test fun retryDelayIsExactly500Ms() {
        assertEquals(500L, AudioStartRetry.RETRY_DELAY_MS)
        val e = Env(); e.begin()
        assertEquals(500L, e.retry.retryDelayMs)
        e.retry.onOutcome(notListening)
        assertEquals(listOf(500L), e.scheduler.delays)
        e.scheduler.advance(499)
        assertEquals(1, e.sent.size)                       // 1 ms early: nothing
        e.scheduler.advance(1)
        assertEquals(2, e.sent.size)                       // exactly 500 ms
        e.retry.onOutcome(notListening)
        assertEquals(listOf(500L, 500L), e.scheduler.delays)   // every retry waits the same 500 ms
    }

    // 3 ------------------------------------------------------------------------------------------------------------
    @Test fun sameConfigurationIsRetained() {
        val e = Env(); e.begin()
        repeat(3) { e.retry.onOutcome(notListening); e.scheduler.advance(500) }
        assertEquals(4, e.sent.size)
        e.sent.forEach {
            assertSame(e.request, it)                      // the very same request object
            assertSame(e.request.source, it.source)
            assertEquals(16_000, it.config.sampleRate)     // never another sample rate
        }
    }

    @Test fun notListeningNeverAdvancesTheFallback() {
        val fb = CellularConfigFallback()
        val first = fb.current
        repeat(10) {
            val ev = notListening.toConfigEvent()!!
            val d = fb.onEvent(ev)
            assertTrue(d is CellularConfigDecision.Keep && d.notListening)
        }
        assertEquals(first, fb.current)                    // still VOICE_DOWNLINK @ 16000
        assertEquals(CellularCaptureSource.VOICE_DOWNLINK, fb.current!!.source)
        assertTrue(fb.failedAttempts.isEmpty())
        // a genuine source failure is the only outcome that maps to InitFailed
        assertTrue(AudioStartOutcome.SourceFailed("x").toConfigEvent() is CellularConfigEvent.InitFailed)
        assertTrue(AudioStartOutcome.Ready.toConfigEvent() is CellularConfigEvent.Started)
        assertNull(AudioStartOutcome.TimedOut.toConfigEvent())
        assertNull(AudioStartOutcome.LinkDown.toConfigEvent())
    }

    // 4 ------------------------------------------------------------------------------------------------------------
    @Test fun successStopsRetries() {
        val e = Env(); e.begin()
        e.retry.onOutcome(notListening); e.scheduler.advance(500)
        e.retry.onOutcome(AudioStartOutcome.Ready)
        assertEquals(1, e.listener.finished.size)
        assertSame(AudioStartOutcome.Ready, e.listener.finished[0].first)
        assertEquals(2, e.listener.finished[0].second)
        assertFalse(e.retry.isRunning)
        assertEquals(0, e.scheduler.pending)
        e.scheduler.advance(5_000)
        assertEquals(2, e.sent.size)                       // nothing more is sent
        e.retry.onOutcome(notListening)                    // a late message changes nothing
        assertEquals(0, e.scheduler.pending)
        assertEquals(1, e.listener.finished.size)
    }

    // 5 ------------------------------------------------------------------------------------------------------------
    @Test fun cancelWhileWaitingForTimerStopsRetries() {
        val e = Env(); e.begin()
        e.retry.onOutcome(notListening)
        e.retry.cancel()
        assertFalse(e.retry.isRunning)
        assertEquals(0, e.scheduler.pending)
        e.scheduler.advance(10_000)
        assertEquals(1, e.sent.size)
        assertTrue(e.listener.finished.isEmpty())          // no callback after cancellation
        assertEquals(0, e.aborts)                          // nothing was in flight
    }

    @Test fun cancelWhileWaitingForAnswerAbortsTheAttempt() {
        val e = Env(); e.begin()
        e.retry.cancel()
        assertEquals(1, e.aborts)
        e.retry.onOutcome(AudioStartOutcome.Ready)         // late answer of the cancelled attempt
        e.retry.onOutcome(notListening)
        assertTrue(e.listener.finished.isEmpty())
        assertEquals(0, e.scheduler.pending)
        assertEquals(1, e.sent.size)
    }

    @Test fun staleTimerAfterCancelDoesNothing() {
        val e = Env(); e.begin()
        e.retry.onOutcome(notListening)
        e.retry.cancel()
        e.scheduler.fireEvenCancelled()                    // even a scheduler that ignores cancel() cannot restart anything
        assertEquals(1, e.sent.size)
        assertTrue(e.listener.finished.isEmpty())
    }

    @Test fun staleTimerOfOldRunDoesNotTouchNewRun() {
        val e = Env(); e.begin()
        e.retry.onOutcome(notListening)
        e.retry.cancel()
        assertTrue(e.begin())                              // new run: sent = 2
        e.scheduler.fireEvenCancelled()                    // old timer fires late
        assertEquals(2, e.sent.size)
        assertEquals(1, e.retry.attempts)
    }

    @Test fun cancelIsIdempotentAndSafeWhenIdle() {
        val e = Env()
        e.retry.cancel(); e.retry.cancel()
        e.begin(); e.retry.cancel(); e.retry.cancel()
        assertEquals(1, e.aborts)
    }

    @Test fun cancelAfterSuccessDoesNotAbort() {
        val e = Env(); e.begin(); e.retry.onOutcome(AudioStartOutcome.Ready)
        e.retry.cancel()
        assertEquals(0, e.aborts)                          // the running stream belongs to the owner
    }

    @Test fun abortFailureDoesNotEscapeCancel() {
        val s = FakeScheduler()
        val r = AudioStartRetry(s, { true }, { true }, { null }, { throw IllegalStateException("boom") })
        r.begin(AudioStartRequest(FakeSource(), AudioConfig()), RecListener())
        r.cancel()
        assertFalse(r.isRunning)
    }

    // 6 ------------------------------------------------------------------------------------------------------------
    @Test fun secondBeginIsRefusedWhileRunning() {
        val e = Env()
        assertTrue(e.begin())
        assertFalse(e.begin())                             // waiting for the answer
        assertEquals(1, e.sent.size)
        e.retry.onOutcome(notListening)
        assertFalse(e.begin())                             // waiting for the timer
        e.scheduler.advance(500)
        assertEquals(2, e.sent.size)
        assertFalse(e.begin())
        assertEquals(2, e.sent.size)
        assertEquals(0, e.scheduler.pending)               // no second timer was ever created
    }

    @Test fun newRunAllowedAfterFinish() {
        val e = Env(); e.begin(); e.retry.onOutcome(AudioStartOutcome.Ready)
        assertTrue(e.begin())
        assertEquals(2, e.sent.size)
        assertEquals(1, e.retry.attempts)                  // attempt counter belongs to the new run
    }

    @Test fun beginAgainFromFinishCallbackIsAllowed() {
        val e = Env()
        var again = false
        val l = object : AudioStartRetry.Listener {
            override fun onFinished(outcome: AudioStartOutcome, attempts: Int) { again = e.retry.begin(e.request, e.listener) }
        }
        e.retry.begin(e.request, l)
        e.retry.onOutcome(AudioStartOutcome.TimedOut)
        assertTrue(again)
        assertTrue(e.retry.isRunning)
    }

    // link / backend / transport errors ---------------------------------------------------------------------------
    @Test fun linkDownWhileWaitingForTimerStopsRetries() {
        val e = Env(); e.begin()
        e.retry.onOutcome(notListening)
        e.linkUp = false
        e.retry.onOutcome(AudioStartOutcome.LinkDown)
        assertSame(AudioStartOutcome.LinkDown, e.listener.finished.single().first)
        assertEquals(0, e.scheduler.pending)
        e.scheduler.advance(5_000)
        assertEquals(1, e.sent.size)
    }

    @Test fun linkDownDetectedWhenTimerFiresEvenWithoutNotification() {
        val e = Env(); e.begin()
        e.retry.onOutcome(notListening)
        e.linkUp = false
        e.scheduler.advance(500)
        assertEquals(1, e.sent.size)                       // nothing sent into a dead link
        assertSame(AudioStartOutcome.LinkDown, e.listener.finished.single().first)
    }

    @Test fun inactiveBackendStopsRetries() {
        val e = Env(); e.begin()
        e.retry.onOutcome(notListening)
        e.active = false
        e.scheduler.advance(500)
        assertEquals(1, e.sent.size)
        assertSame(AudioStartOutcome.Inactive, e.listener.finished.single().first)
    }

    @Test fun beginWithLinkDownFinishesImmediatelyWithoutSending() {
        val e = Env(); e.linkUp = false
        assertTrue(e.begin())
        assertTrue(e.sent.isEmpty())
        assertSame(AudioStartOutcome.LinkDown, e.listener.finished.single().first)
        assertFalse(e.retry.isRunning)
    }

    @Test fun transportRejectingTheStartEndsTheRun() {
        val e = Env(); e.startError = "Already sending"
        e.begin()
        val o = e.listener.finished.single().first
        assertTrue(o is AudioStartOutcome.Rejected && o.reason == "Already sending")
        assertFalse(e.retry.isRunning)
        assertEquals(0, e.scheduler.pending)
    }

    @Test fun rejectedOnRetryEndsTheRunInsteadOfLooping() {
        val e = Env(); e.begin()
        e.retry.onOutcome(notListening)
        e.startError = "Already sending"
        e.scheduler.advance(500)
        assertEquals(2, e.sent.size)
        assertTrue(e.listener.finished.single().first is AudioStartOutcome.Rejected)
        assertEquals(0, e.scheduler.pending)
    }

    @Test fun outcomeDeliveredInsideStartAttemptIsNotLost() {
        val s = FakeScheduler()
        lateinit var r: AudioStartRetry
        r = AudioStartRetry(s, { true }, { true }, { r.onOutcome(AudioStartOutcome.Ready); null })
        val l = RecListener()
        r.begin(AudioStartRequest(FakeSource(), AudioConfig()), l)
        assertSame(AudioStartOutcome.Ready, l.finished.single().first)
    }

    @Test fun exceptionFromStartAttemptIsAFailureNotACrash() {
        val s = FakeScheduler()
        val r = AudioStartRetry(s, { true }, { true }, { throw RuntimeException("x") })
        val l = RecListener()
        r.begin(AudioStartRequest(FakeSource(), AudioConfig()), l)
        assertTrue(l.finished.single().first is AudioStartOutcome.Rejected)
    }
}
