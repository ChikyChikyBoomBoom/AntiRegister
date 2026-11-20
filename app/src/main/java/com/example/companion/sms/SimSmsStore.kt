package com.example.companion.sms

import android.content.Context
import com.example.companion.protocol.SmsReceived
import com.example.companion.protocol.SmsSendState
import org.json.JSONArray
import org.json.JSONObject

/**
 * SIM Phone: tiny private persistence (app-private SharedPreferences, no backup, never uploaded) for two things:
 *  1. send records (id + state only, no number, no body): lets a re-sent SMS_SEND be answered instead of sent twice;
 *  2. SMS that arrived but the Main Phone has not acknowledged yet (contains the body until acked, then deleted;
 *     entries older than [INBOUND_MAX_AGE_MS] are dropped).
 * All access is synchronized on one lock because the SMS receivers and the Bluetooth listeners share the process.
 */
class SimSmsStore(context: Context) {
    private val sp = context.getSharedPreferences("companion_sim_sms", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- send records

    fun getSend(id: String): SmsSendRecord? = synchronized(LOCK) { loadSends()[id] }

    /** True if the record reached disk (Stage 26: the caller must not send an SMS when the "I am sending this id" record could not be saved). */
    fun putSend(r: SmsSendRecord): Boolean = synchronized(LOCK) {
        val all = loadSends(); all[r.id] = r; saveSends(trim(all))
    }

    /** Atomically updates a record; returns the new record, or null if the id is unknown. */
    fun updateSend(id: String, f: (SmsSendRecord) -> SmsSendRecord): SmsSendRecord? = synchronized(LOCK) {
        val all = loadSends()
        val old = all[id] ?: return null
        val new = f(old); all[id] = new
        saveSends(all)          // Stage 26: a failed write is recorded for developers; the caller still gets the new record to report
        new
    }

    private fun trim(all: MutableMap<String, SmsSendRecord>): Map<String, SmsSendRecord> {
        if (all.size <= MAX_SEND_RECORDS) return all
        return all.values.sortedByDescending { it.updatedAt }.take(MAX_SEND_RECORDS).associateBy { it.id }
    }

    private fun loadSends(): MutableMap<String, SmsSendRecord> {
        val out = LinkedHashMap<String, SmsSendRecord>()
        val arr = parseArray(sp.getString(KEY_SENDS, null)) ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val state = runCatching { SmsSendState.valueOf(o.optString("state", "")) }.getOrNull() ?: continue
            if (id.isEmpty()) continue
            out[id] = SmsSendRecord(
                id, state, o.optString("error", "").ifEmpty { null }, o.optString("detail", "").ifEmpty { null },
                o.optInt("total", 0), o.optInt("reported", 0), o.optInt("failed", 0),
                if (o.has("firstFailure")) o.optInt("firstFailure") else null, o.optLong("updatedAt", 0L)
            )
        }
        return out
    }

    private fun saveSends(all: Map<String, SmsSendRecord>): Boolean {
        val arr = JSONArray()
        all.values.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("state", r.state.name).put("total", r.total)
                .put("reported", r.reported).put("failed", r.failed).put("updatedAt", r.updatedAt).apply {
                    r.error?.let { put("error", it) }; r.detail?.let { put("detail", it) }; r.firstFailureCode?.let { put("firstFailure", it) }
                })
        }
        // commit(), not apply(): the record must be on disk BEFORE Android is asked to send (Stage 26), or a process kill in between
        // could lose it and a re-send after the reconnect would then send the SMS a second time. Tiny file, rare writes.
        // Stage 26: commit() reports whether the write reached disk; it used to be ignored. A failure is kept for developers, never silent.
        val ok = try { sp.edit().putString(KEY_SENDS, arr.toString()).commit() } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_STORE, "save send records", e); false
        }
        if (!ok) SmsDiagnostics.record(SmsDiagnostics.Area.SIM_STORE, "send records not written")
        return ok
    }

    // ---------------------------------------------------------------- inbound queue (until the Main Phone acks)

    fun enqueueInbound(r: SmsReceived) = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        val list = loadInbound().filter { now - it.second < INBOUND_MAX_AGE_MS && it.first.transferId != r.transferId }.toMutableList()
        list.add(r to now)
        saveInbound(list.takeLast(MAX_INBOUND))
    }

    fun pendingInbound(): List<SmsReceived> = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        val fresh = loadInbound().filter { now - it.second < INBOUND_MAX_AGE_MS }
        saveInbound(fresh)
        fresh.map { it.first }
    }

    fun ackInbound(id: String) = synchronized(LOCK) { saveInbound(loadInbound().filter { it.first.transferId != id }) }

    private fun loadInbound(): List<Pair<SmsReceived, Long>> {
        val arr = parseArray(sp.getString(KEY_INBOUND, null)) ?: return emptyList()
        val out = ArrayList<Pair<SmsReceived, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", ""); val from = o.optString("from", ""); val body = o.optString("body", "")
            if (id.isEmpty() || from.isEmpty() || body.isEmpty()) continue
            val ts = if (o.has("ts")) o.optLong("ts").takeIf { it > 0 } else null
            val sub = if (o.has("sub")) o.optInt("sub").takeIf { it >= 0 } else null
            out.add(SmsReceived(id, from, body, ts, sub) to o.optLong("at", 0L))
        }
        return out
    }

    private fun saveInbound(list: List<Pair<SmsReceived, Long>>) {
        val arr = JSONArray()
        list.forEach { (r, at) ->
            arr.put(JSONObject().put("id", r.transferId).put("from", r.sender).put("body", r.body).put("at", at).apply {
                r.timestamp?.let { put("ts", it) }; r.subscriptionId?.let { put("sub", it) }
            })
        }
        sp.edit().putString(KEY_INBOUND, arr.toString()).apply()
    }

    private fun parseArray(s: String?): JSONArray? = if (s == null) null else try { JSONArray(s) } catch (e: Exception) {
        // Stage 26: stored text that is not valid JSON is treated as empty (as before) but is now recorded for developers.
        SmsDiagnostics.record(SmsDiagnostics.Area.SIM_STORE, "stored sms data unreadable", e); null
    }

    private companion object {
        val LOCK = Any()
        const val KEY_SENDS = "sends"
        const val KEY_INBOUND = "inbound"
        const val MAX_SEND_RECORDS = 200
        const val MAX_INBOUND = 200
        const val INBOUND_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
