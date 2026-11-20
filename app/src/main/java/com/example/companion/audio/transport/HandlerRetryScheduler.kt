package com.example.companion.audio.transport

import android.os.Handler
import android.os.Looper

/**
 * Stage 33B: [RetryScheduler] on the main looper (no thread, no coroutine). Uses its OWN Handler instance on purpose:
 * `AudioTransport.stopAll()` calls `removeCallbacksAndMessages(null)` on the transport's Handler, which must not silently drop a retry timer.
 * Cancellation is the retry's job ([AudioStartRetry.cancel]), not a side effect of `stopAll`.
 */
class HandlerRetryScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : RetryScheduler {
    override fun postDelayed(delayMs: Long, task: Runnable): RetryTimer {
        handler.postDelayed(task, delayMs)
        return RetryTimer { handler.removeCallbacks(task) }
    }
}
