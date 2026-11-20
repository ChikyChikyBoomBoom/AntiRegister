package com.example.companion.calls

import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Main Phone only. Listens to the EXISTING [ClientCallController] (the call-state system), hands each snapshot to
 * [CallHistoryTracker] and stores the calls that finished. Adds no messages, no Bluetooth code and no call control.
 *
 * The store is opened lazily, so a phone that never records a call (for example the SIM Phone) never creates the database.
 */
class CallHistoryRecorder(
    private val prefs: AppPrefs,
    private val calls: ClientCallController,
    private val store: () -> CallHistoryStore
) : ClientCallController.Listener {

    interface Listener { fun onCallHistoryChanged() }

    private val tracker = CallHistoryTracker()
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Called on the main thread by [ClientCallController] after every state change, repeat and reconnect. */
    override fun onCallUiChanged() {
        if (prefs.role != Role.MAIN_PHONE) return
        // Stage 5: switch for the legacy recording (call states reported by the SIM Phone's InCallService). ON by default = unchanged behaviour.
        if (!CallHistorySource.LEGACY_LIVE_RECORDING_ENABLED) return
        val finished = tracker.onSnapshot(calls.snapshot)
        if (finished.isEmpty()) return
        var changed = false
        finished.forEach { entry ->
            // A storage failure must never break the call UI; the entry is simply not kept.
            try { if (store().insert(entry)) changed = true } catch (e: Exception) {}
        }
        if (changed) listeners.forEach { it.onCallHistoryChanged() }
    }
}
