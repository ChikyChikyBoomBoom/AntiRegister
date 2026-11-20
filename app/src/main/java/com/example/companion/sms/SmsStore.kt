package com.example.companion.sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Main Phone: local SMS history for the messaging UI (private app database, never uploaded, allowBackup=false).
 * Stores only what the UI shows: number, text, time, direction, status, failure reason and (Stage 13) whether a received
 * message has been opened. The conversation list is not a second copy of the data: it is a query over this one table,
 * so it can never disagree with the messages (delete, de-duplication and unread state all live in one place).
 */
class SmsStore(context: Context) : SQLiteOpenHelper(context, "companion_sms.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, thread TEXT NOT NULL, address TEXT NOT NULL, body TEXT NOT NULL, " +
            "ts INTEGER NOT NULL, direction INTEGER NOT NULL, status TEXT NOT NULL, error TEXT, detail TEXT, " +
            "is_read INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX idx_thread_ts ON messages(thread, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2 (Stage 13): unread tracking. Messages stored before this version count as read: nothing records
        // whether the user saw them, and showing every old conversation as unread would be wrong.
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN is_read INTEGER NOT NULL DEFAULT 1")
    }

    /**
     * True if inserted, false if an entry with this id already exists (de-duplication). Throws on a real database error.
     * A duplicate is ignored completely, so a message re-sent after a Bluetooth reconnect is neither stored twice nor
     * turned back into "unread" after the user opened it. Received messages start unread; our own messages never are.
     */
    fun insert(e: SmsEntry): Boolean {
        // Stage 26: a received SMS that is already stored under another id (e.g. queued by an older SIM Phone build, which gave every
        // broadcast a random id) is the same SMS: same conversation, same text, same service-centre time. When the SMS carried no time,
        // the stored time is "when this phone received it", which never repeats, so this never merges two different messages.
        if (e.direction == SmsDirection.INCOMING && receivedExists(threadKey(e.address), e.body, e.timestamp)) return false
        val v = ContentValues().apply {
            put("is_read", if (e.direction == SmsDirection.INCOMING) 0 else 1)
            put("id", e.id); put("thread", threadKey(e.address)); put("address", e.address); put("body", e.body)
            put("ts", e.timestamp); put("direction", if (e.direction == SmsDirection.OUTGOING) 1 else 0)
            put("status", e.status.name); put("error", e.error); put("detail", e.detail)
        }
        return writableDatabase.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    private fun receivedExists(thread: String, body: String, ts: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE thread=? AND direction=0 AND ts=? AND body=? LIMIT 1",
            arrayOf(thread, ts.toString(), body)).use { it.moveToFirst() }

    /** Moves an outgoing message forward, only if it is currently in one of [allowedFrom] (a final state is never overwritten). */
    fun updateStatus(id: String, status: SmsStatus, error: String?, detail: String?, allowedFrom: Set<SmsStatus>): Boolean {
        val v = ContentValues().apply { put("status", status.name); put("error", error); put("detail", detail) }
        val marks = allowedFrom.joinToString(",") { "?" }
        val args = arrayOf(id) + allowedFrom.map { it.name }
        return writableDatabase.update("messages", v, "id=? AND direction=1 AND status IN ($marks)", args) > 0
    }

    fun thread(key: String): List<SmsEntry> {
        val out = ArrayList<SmsEntry>()
        readableDatabase.rawQuery(
            "SELECT id,address,body,ts,direction,status,error,detail FROM messages WHERE thread=? ORDER BY ts ASC, rowid ASC", arrayOf(key)
        ).use { c -> while (c.moveToNext()) out.add(entry(c)) }
        return out
    }

    /** Outgoing messages the SIM Phone has not confirmed a final result for. */
    fun unresolvedOutgoing(): List<SmsEntry> {
        val out = ArrayList<SmsEntry>()
        readableDatabase.rawQuery(
            "SELECT id,address,body,ts,direction,status,error,detail FROM messages " +
                "WHERE direction=1 AND status IN ('PENDING','SENDING') ORDER BY ts ASC", null
        ).use { c -> while (c.moveToNext()) out.add(entry(c)) }
        return out
    }

    /**
     * One row per conversation, newest message first. The "latest" message is chosen by (ts, rowid), so two messages with
     * the same timestamp still give one deterministic row. [SmsConversation.unread] counts received, not-yet-opened messages.
     */
    fun conversations(): List<SmsConversation> {
        val out = ArrayList<SmsConversation>()
        readableDatabase.rawQuery(
            "SELECT m.thread, m.address, m.body, m.ts, m.direction, " +
                "(SELECT COUNT(*) FROM messages c WHERE c.thread=m.thread), " +
                "(SELECT COUNT(*) FROM messages f WHERE f.thread=m.thread AND f.status='FAILED'), " +
                "(SELECT COUNT(*) FROM messages u WHERE u.thread=m.thread AND u.is_read=0) " +
                "FROM messages m WHERE m.rowid=(SELECT x.rowid FROM messages x WHERE x.thread=m.thread " +
                "ORDER BY x.ts DESC, x.rowid DESC LIMIT 1) " +
                "ORDER BY m.ts DESC, m.rowid DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(SmsConversation(
                c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                if (c.getInt(4) == 1) SmsDirection.OUTGOING else SmsDirection.INCOMING,
                c.getInt(5), c.getInt(6), c.getInt(7)))
        }
        return out
    }

    /** Stage 15: received messages of one conversation that have not been opened yet (used for the notification text). */
    fun unreadCount(key: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE thread=? AND is_read=0", arrayOf(key))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Marks every received message of the conversation as read. True if anything changed. */
    fun markThreadRead(key: String): Boolean {
        val v = ContentValues().apply { put("is_read", 1) }
        return writableDatabase.update("messages", v, "thread=? AND is_read=0", arrayOf(key)) > 0
    }

    fun deleteThread(key: String) { writableDatabase.delete("messages", "thread=?", arrayOf(key)) }

    /** Stage 20: removes every stored message of every conversation (this phone only). Returns how many rows were removed. */
    fun clearAll(): Int = writableDatabase.delete("messages", null, null)

    private fun entry(c: android.database.Cursor) = SmsEntry(
        id = c.getString(0), address = c.getString(1), body = c.getString(2), timestamp = c.getLong(3),
        direction = if (c.getInt(4) == 1) SmsDirection.OUTGOING else SmsDirection.INCOMING,
        status = runCatching { SmsStatus.valueOf(c.getString(5)) }.getOrDefault(SmsStatus.FAILED),
        error = c.getString(6), detail = c.getString(7)
    )
}
