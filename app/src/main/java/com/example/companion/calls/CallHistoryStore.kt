package com.example.companion.calls

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Main Phone: local Recent Calls history. Private app database (allowBackup=false), never uploaded, never sent over
 * Bluetooth, never shared with the SIM Phone. Keeps the newest [MAX_ENTRIES] calls.
 */
class CallHistoryStore(context: Context) : SQLiteOpenHelper(context, "companion_calls.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recent_calls(id TEXT PRIMARY KEY, number TEXT, type TEXT, started_at INTEGER NOT NULL, " +
            "duration_sec INTEGER, cause TEXT)")
        db.execSQL("CREATE INDEX idx_recent_started ON recent_calls(started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** True if inserted, false if an entry with this id already exists (de-duplication). Throws on a real database error. */
    fun insert(e: CallLogEntry): Boolean {
        val v = ContentValues().apply {
            put("id", e.id); put("number", e.number); put("type", e.type?.name)
            put("started_at", e.startedAt); put("duration_sec", e.durationSeconds); put("cause", e.cause)
        }
        val db = writableDatabase
        val inserted = db.insertWithOnConflict("recent_calls", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) trim(db)
        return inserted
    }

    /** Newest first. */
    fun all(): List<CallLogEntry> {
        val out = ArrayList<CallLogEntry>()
        readableDatabase.rawQuery(
            "SELECT id,number,type,started_at,duration_sec,cause FROM recent_calls ORDER BY started_at DESC, rowid DESC", null
        ).use { c -> while (c.moveToNext()) out.add(entry(c)) }
        return out
    }

    /** Removes one entry. True if it existed. */
    fun delete(id: String): Boolean = writableDatabase.delete("recent_calls", "id=?", arrayOf(id)) > 0

    /** Stage 20: removes every entry (this phone only). Returns how many were removed. */
    fun clearAll(): Int = writableDatabase.delete("recent_calls", null, null)

    private fun trim(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM recent_calls WHERE id NOT IN " +
                "(SELECT id FROM recent_calls ORDER BY started_at DESC, rowid DESC LIMIT $MAX_ENTRIES)"
        )
    }

    private fun entry(c: Cursor) = CallLogEntry(
        id = c.getString(0),
        number = if (c.isNull(1)) null else c.getString(1),
        type = if (c.isNull(2)) null else runCatching { CallLogType.valueOf(c.getString(2)) }.getOrNull(),
        startedAt = c.getLong(3),
        durationSeconds = if (c.isNull(4)) null else c.getLong(4),
        cause = if (c.isNull(5)) null else c.getString(5)
    )

    companion object { const val MAX_ENTRIES = 200 }
}
