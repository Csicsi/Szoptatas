package hu.csicsi.szoptatas

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.util.TimeZone

enum class Side(val code: String, val label: String) {
    LEFT("L", "Bal"),
    RIGHT("R", "Jobb");

    fun other() = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
        fun fromTitle(title: String) = if (title.contains(RIGHT.label, ignoreCase = true)) RIGHT else LEFT
    }
}

data class Session(val side: Side, val start: Long, val eventId: Long)
data class LastFeed(val side: Side, val end: Long)
data class Entry(val title: String, val start: Long, val end: Long)

/**
 * The calendar is the single source of truth, also for the running feed:
 * start inserts a provisional "Szoptatás – Bal ⏳" event, stop (on any phone
 * sharing the calendar) closes the newest ⏳ event.
 */
object Feeding {
    const val TITLE_PREFIX = "Szoptatás"
    const val RUNNING_MARK = " ⏳"
    private const val STALE_MARK = " (vége?)"
    private const val MIN_DURATION_MS = 20_000L // shorter taps are treated as mistakes
    private const val POINT_DURATION_MS = 5L * 60 * 1000
    private const val STALE_AFTER_MS = 2L * 60 * 60 * 1000
    private const val LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000

    private const val KEY_CALENDAR = "calendar_id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("feeding", Context.MODE_PRIVATE)

    fun calendarId(ctx: Context): Long = prefs(ctx).getLong(KEY_CALENDAR, -1L)

    fun setCalendarId(ctx: Context, id: Long) {
        prefs(ctx).edit().putLong(KEY_CALENDAR, id).commit()
    }

    private fun title(side: Side) = "$TITLE_PREFIX – ${side.label}"

    // ---- running feed ----

    /** All provisional (⏳) feeds, newest first. Stale ones are closed on the way. */
    private fun openFeeds(ctx: Context): List<Session> {
        val calendar = calendarId(ctx)
        if (calendar < 0) return emptyList()
        val now = System.currentTimeMillis()
        val result = mutableListOf<Session>()
        try {
            ctx.contentResolver.query(
                Events.CONTENT_URI,
                arrayOf(Events._ID, Events.TITLE, Events.DTSTART),
                "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0 AND ${Events.TITLE} LIKE ? AND ${Events.DTSTART}>=?",
                arrayOf(calendar.toString(), "%$RUNNING_MARK", (now - LOOKBACK_MS).toString()),
                "${Events.DTSTART} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val start = c.getLong(2)
                    val side = Side.fromTitle(c.getString(1).orEmpty())
                    if (now - start > STALE_AFTER_MS) {
                        markStale(ctx, id, side)
                    } else {
                        result += Session(side, start, id)
                    }
                }
            }
        } catch (e: SecurityException) {
            // no permission yet
        }
        return result
    }

    fun running(ctx: Context): Session? = openFeeds(ctx).firstOrNull()

    /** Returns the URI of a feed that got closed by switching sides, if any. */
    fun start(ctx: Context, side: Side): Uri? {
        val open = openFeeds(ctx)
        if (open.firstOrNull()?.side == side) return null
        val closed = open.mapNotNull { close(ctx, it) }.firstOrNull()
        val now = System.currentTimeMillis()
        logEvent(ctx, title(side) + RUNNING_MARK, now, now + 60_000)
        requestSync(ctx)
        return closed
    }

    /** Closes every open feed; returns the URI of the newest one if it was kept. */
    fun stop(ctx: Context): Uri? {
        val closed = openFeeds(ctx).mapNotNull { close(ctx, it) }.firstOrNull()
        requestSync(ctx)
        return closed
    }

    private fun close(ctx: Context, s: Session): Uri? {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, s.eventId)
        val end = System.currentTimeMillis()
        return try {
            if (end - s.start < MIN_DURATION_MS) {
                ctx.contentResolver.delete(uri, null, null)
                null
            } else {
                val v = ContentValues().apply {
                    put(Events.TITLE, title(s.side))
                    put(Events.DTEND, end)
                }
                ctx.contentResolver.update(uri, v, null, null)
                uri
            }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun markStale(ctx: Context, id: Long, side: Side) {
        val v = ContentValues().apply { put(Events.TITLE, title(side) + STALE_MARK) }
        try {
            ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    // ---- point entries ----

    fun logNow(ctx: Context, title: String): Uri? {
        val now = System.currentTimeMillis()
        val uri = logEvent(ctx, title, now, now + POINT_DURATION_MS)
        requestSync(ctx)
        return uri
    }

    fun logEvent(ctx: Context, title: String, start: Long, end: Long): Uri? {
        val calendar = calendarId(ctx)
        if (calendar < 0) return null
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar)
            put(Events.TITLE, title)
            put(Events.DTSTART, start)
            put(Events.DTEND, end)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return try {
            ctx.contentResolver.insert(Events.CONTENT_URI, values)
        } catch (e: SecurityException) {
            null
        }
    }

    fun delete(ctx: Context, event: Uri) {
        try {
            ctx.contentResolver.delete(event, null, null)
        } catch (e: SecurityException) {
            // ignore; the entry can still be removed in the calendar
        }
        requestSync(ctx)
    }

    // ---- reading ----

    /** All entries of the chosen calendar that start after [since], oldest first. */
    fun entriesSince(ctx: Context, since: Long): List<Entry> {
        val calendar = calendarId(ctx)
        if (calendar < 0) return emptyList()
        val list = mutableListOf<Entry>()
        try {
            ctx.contentResolver.query(
                Events.CONTENT_URI,
                arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND),
                "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0 AND ${Events.DTSTART}>=?",
                arrayOf(calendar.toString(), since.toString()),
                "${Events.DTSTART} ASC",
            )?.use { c ->
                while (c.moveToNext()) list += Entry(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2))
            }
        } catch (e: SecurityException) {
            // no permission yet
        }
        return list
    }

    /** Latest finished feed (app-made titles), for the "next side" hint. */
    fun lastFeed(ctx: Context): LastFeed? {
        val now = System.currentTimeMillis()
        return entriesSince(ctx, now - LOOKBACK_MS)
            .filter { it.title.startsWith(TITLE_PREFIX) && !it.title.endsWith(RUNNING_MARK) && it.end <= now + 60_000 }
            .maxByOrNull { it.end }
            ?.let { LastFeed(Side.fromTitle(it.title), it.end) }
    }

    // ---- sync ----

    /** Ask Android to sync the calendar now, so the other phone sees changes quickly. */
    fun requestSync(ctx: Context) {
        val calendar = calendarId(ctx)
        if (calendar < 0) return
        try {
            ctx.contentResolver.query(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar),
                arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return
                val type = c.getString(1)
                if (type == CalendarContract.ACCOUNT_TYPE_LOCAL) return
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
                ContentResolver.requestSync(Account(c.getString(0), type), CalendarContract.AUTHORITY, extras)
            }
        } catch (e: Exception) {
            // best effort
        }
    }
}
