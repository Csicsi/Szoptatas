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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Side(val code: String, val label: String) {
    LEFT("L", "Bal"),
    RIGHT("R", "Jobb");

    fun other() = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
        /** null for hand-written entries that don't name a side. */
        fun fromTitle(title: String): Side? = when {
            title.contains("jobb", ignoreCase = true) -> RIGHT
            title.contains("bal", ignoreCase = true) -> LEFT
            else -> null
        }
    }
}

data class Session(val side: Side, val start: Long, val eventId: Long)
data class LastFeed(val side: Side?, val end: Long)
data class Entry(val title: String, val start: Long, val end: Long)

/**
 * The calendar is the single source of truth, also for the running feed:
 * start inserts a provisional "Szoptatás – Bal ⏳" event, stop (on any phone
 * sharing the calendar) closes the newest ⏳ event.
 */
object Feeding {
    private val FEED = Regex("(?i)szopi|szopiz|szoptat")
    private val RANGE = Regex("""(\d{1,2})[:.](\d{2})\s*-\s*(\d{1,2})[:.](\d{2})""")
    private val TIME = Regex("""(\d{1,2})[:.](\d{2})""")
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

    // Same notation as the hand-written entries: "09:00-10:50 szopi bal"
    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    private fun runningTitle(side: Side, start: Long) = "${hhmm(start)}- szopi ${side.label.lowercase()}$RUNNING_MARK"
    private fun closedTitle(side: Side, start: Long, end: Long) = "${hhmm(start)}-${hhmm(end)} szopi ${side.label.lowercase()}"
    private fun staleTitle(side: Side, start: Long) = "${hhmm(start)}- szopi ${side.label.lowercase()}$STALE_MARK"

    fun isFeed(title: String) = FEED.containsMatchIn(title)

    /** Real start/end from "HH:mm-HH:mm" in the title, anchored to the event's day. */
    fun parsedRange(e: Entry): Pair<Long, Long>? {
        val m = RANGE.find(e.title) ?: return null
        val (h1, m1, h2, m2) = m.destructured
        var start = atTime(e.start, h1.toInt(), m1.toInt())
        if (start - e.start > 12 * HOUR) start -= DAY
        if (e.start - start > 12 * HOUR) start += DAY
        var end = atTime(start, h2.toInt(), m2.toInt())
        if (end < start) end += DAY
        return start to end
    }

    /** When the feed ended: range end, else the single time in the title, else the event start. */
    fun feedEnd(e: Entry): Long {
        parsedRange(e)?.let { return it.second }
        val m = TIME.find(e.title) ?: return e.start
        var t = atTime(e.start, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        if (t - e.start > 12 * HOUR) t -= DAY
        if (e.start - t > 12 * HOUR) t += DAY
        return t
    }

    private fun atTime(day: Long, h: Int, min: Int): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = day
        set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24 * HOUR

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
                    val side = Side.fromTitle(c.getString(1).orEmpty()) ?: Side.LEFT
                    if (now - start > STALE_AFTER_MS) {
                        markStale(ctx, id, side, start)
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
        logEvent(ctx, runningTitle(side, now), now, now + 60_000)
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
                    put(Events.TITLE, closedTitle(s.side, s.start, end))
                    put(Events.DTEND, end)
                }
                ctx.contentResolver.update(uri, v, null, null)
                uri
            }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun markStale(ctx: Context, id: Long, side: Side, start: Long) {
        val v = ContentValues().apply { put(Events.TITLE, staleTitle(side, start)) }
        try {
            ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    // ---- time corrections ("−5 perc" in the banner) ----

    /** Moves a point entry (start and end) by [deltaMs]; returns the new start. */
    fun shiftEntry(ctx: Context, event: Uri, deltaMs: Long): Long? = try {
        ctx.contentResolver.query(event, arrayOf(Events.DTSTART, Events.DTEND), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val start = c.getLong(0) + deltaMs
            val v = ContentValues().apply {
                put(Events.DTSTART, start)
                put(Events.DTEND, c.getLong(1) + deltaMs)
            }
            ctx.contentResolver.update(event, v, null, null)
            requestSync(ctx)
            start
        }
    } catch (e: SecurityException) {
        null
    }

    /** Moves the start of the running feed (and the time in its title); returns the new start. */
    fun shiftRunningStart(ctx: Context, deltaMs: Long): Long? {
        val s = running(ctx) ?: return null
        val start = s.start + deltaMs
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, s.eventId)
        return try {
            val v = ContentValues().apply {
                put(Events.DTSTART, start)
                put(Events.DTEND, maxOf(System.currentTimeMillis(), start + 60_000))
                put(Events.TITLE, runningTitle(s.side, start))
            }
            ctx.contentResolver.update(uri, v, null, null)
            requestSync(ctx)
            start
        } catch (e: SecurityException) {
            null
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

    /** Latest finished feed, app-made or hand-written; side is null if the title has none. */
    fun lastFeed(ctx: Context): LastFeed? {
        val now = System.currentTimeMillis()
        return entriesSince(ctx, now - LOOKBACK_MS)
            .filter { isFeed(it.title) && !it.title.endsWith(RUNNING_MARK) }
            .map { it to feedEnd(it) }
            .filter { it.second <= now + 60_000 }
            .maxByOrNull { it.second }
            ?.let { (e, end) -> LastFeed(Side.fromTitle(e.title), end) }
    }

    // ---- sync ----

    /** Google account the chosen calendar belongs to; null for local calendars. */
    fun syncAccount(ctx: Context): Account? {
        val calendar = calendarId(ctx)
        if (calendar < 0) return null
        return try {
            ctx.contentResolver.query(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar),
                arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val type = c.getString(1)
                if (type == CalendarContract.ACCOUNT_TYPE_LOCAL) null else Account(c.getString(0), type)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Ask Android to sync the calendar now, so the other phone sees changes quickly. */
    fun requestSync(ctx: Context): Account? {
        val account = syncAccount(ctx) ?: return null
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        try {
            ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
        } catch (e: Exception) {
            return null
        }
        return account
    }

    fun isSyncing(account: Account): Boolean = try {
        ContentResolver.isSyncActive(account, CalendarContract.AUTHORITY) ||
            ContentResolver.isSyncPending(account, CalendarContract.AUTHORITY)
    } catch (e: Exception) {
        false
    }
}
