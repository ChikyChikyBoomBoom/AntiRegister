package com.example.companion.calls

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import com.example.companion.data.ReadDiagnostics
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings

/** Outcome of one read of the call log. Nothing here ever throws to the caller. */
sealed interface CallLogResult {
    /** Stage 28: [skipped] = rows that were damaged (no id or date, unreadable columns) and were left out; 0 for a healthy log. */
    data class Loaded(val entries: List<CallLogEntry>, val skipped: Int = 0) : CallLogResult
    /** READ_CALL_LOG is not granted (never asked, denied, or revoked while the app was open). */
    object PermissionMissing : CallLogResult
    /** The call log could not be read (no provider, provider error). [reason] is a short, user-safe text. */
    data class Failed(val reason: String) : CallLogResult
}

/**
 * Stage 5: SIM Phone call history from Android's call log (`CallLog.Calls`), permission READ_CALL_LOG.
 *
 * READ ONLY: one query, nothing inserted / updated / deleted. It does not use Telecom, [CompanionInCallService], the dialer
 * role or any call-control API, and it never places, answers or ends a call. It does not need CALL_PHONE or RECORD_AUDIO.
 *
 * [read] does disk/provider work: call it off the main thread. Newest first, at most [MAX_ENTRIES] rows.
 */
class SimCallLogReader(private val context: Context) {

    fun hasPermission(): Boolean = CallLogPermissions.granted(context)

    fun read(): CallLogResult {
        if (!hasPermission()) return CallLogResult.PermissionMissing
        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC"          // no LIMIT in the sort order (not allowed on newer Android); capped below
            )
            if (cursor == null) {
                ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_QUERY, "call log returned no cursor")
                return CallLogResult.Failed(english(StringKey.CALLS_SIM_NOT_AVAILABLE))
            }
            cursor.use { c ->
                val iId = c.getColumnIndex(CallLog.Calls._ID)
                val iNumber = c.getColumnIndex(CallLog.Calls.NUMBER)
                val iType = c.getColumnIndex(CallLog.Calls.TYPE)
                val iDate = c.getColumnIndex(CallLog.Calls.DATE)
                val iDuration = c.getColumnIndex(CallLog.Calls.DURATION)
                if (iId < 0 || iType < 0 || iDate < 0) {
                    ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_QUERY, "call log columns missing")
                    return CallLogResult.Failed(english(StringKey.CALLS_SIM_BAD_FORMAT))
                }
                val out = ArrayList<CallLogEntry>()
                var skipped = 0
                // Stage 28: one damaged row is skipped and counted; it no longer turns the whole list into an error.
                try {
                    while (out.size < MAX_ENTRIES && c.moveToNext()) {
                        val e = rowOf(c, iId, iNumber, iType, iDate, iDuration)
                        if (e == null) skipped++ else out.add(e)
                    }
                } catch (e: RuntimeException) {
                    // The cursor itself broke part-way (window lost, provider died). Keep what was read; the unread rest counts as left out.
                    ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_QUERY, "call log cursor failed part-way", e)
                    if (out.isEmpty()) return CallLogResult.Failed(english(StringKey.CALLS_SIM_READ_FAILED))
                    skipped++
                }
                if (out.isEmpty() && skipped > 0) {
                    ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_QUERY, "every call log row was unusable", detail = "rows=$skipped")
                    CallLogResult.Failed(english(StringKey.CALLS_SIM_BAD_FORMAT))
                } else CallLogResult.Loaded(out, skipped)
            }
        } catch (e: SecurityException) {
            if (hasPermission()) {
                // Granted, yet Android refuses (a restriction beyond the permission): asking again would loop, so say what it is.
                ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_QUERY, "access refused although permission is granted", e)
                CallLogResult.Failed(english(StringKey.CALLS_SIM_ACCESS_BLOCKED))
            } else {
                ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_QUERY, "permission revoked during query", e)
                CallLogResult.PermissionMissing                     // revoked between the check and the query
            }
        } catch (e: IllegalArgumentException) {
            ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_QUERY, "no call log provider", e)       // "Unknown URL": this phone has no call log
            CallLogResult.Failed(english(StringKey.CALLS_SIM_NOT_AVAILABLE))
        } catch (e: Exception) {
            ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_QUERY, "query call log", e)
            CallLogResult.Failed(english(StringKey.CALLS_SIM_READ_FAILED))
        }
    }

    /** One row -> an entry, or null when the row is damaged (missing id / date, or a column that cannot be read). Never throws. */
    private fun rowOf(c: Cursor, iId: Int, iNumber: Int, iType: Int, iDate: Int, iDuration: Int): CallLogEntry? = try {
        SimCallLogMapper.tryEntry(
            rowId = if (c.isNull(iId)) null else c.getLong(iId),
            number = if (iNumber < 0 || c.isNull(iNumber)) null else c.getString(iNumber),
            type = if (c.isNull(iType)) null else c.getInt(iType),
            dateMs = if (c.isNull(iDate)) null else c.getLong(iDate),
            durationSec = if (iDuration < 0 || c.isNull(iDuration)) null else c.getLong(iDuration)
        )
    } catch (e: RuntimeException) {
        ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_ROW, "read call log row", e)
        null
    }

    /** The reason texts stay English here; the screen maps them to the selected language ([SimCallHistoryState.failureText]). */
    private fun english(key: StringKey): String = Strings.text(key, AppLanguage.ENGLISH)

    companion object { const val MAX_ENTRIES = 100 }
}
