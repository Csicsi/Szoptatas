package hu.csicsi.szoptatas

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Events
import java.util.TimeZone

enum class Side(val code: String, val label: String) {
    LEFT("L", "Bal"),
    RIGHT("R", "Jobb");

    fun other() = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
    }
}

data class Session(val side: Side, val start: Long)
data class LastFeed(val side: Side, val end: Long)

/**
 * Only the running session lives in the app. Finished feeds are calendar events,
 * so the calendar is the log: edit, add or delete feeds there.
 */
object Feeding {
    const val TITLE_PREFIX = "Szoptatás"
    private const val MIN_DURATION_MS = 20_000L // shorter taps are treated as mistakes
    private const val LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000

    private const val KEY_CALENDAR = "calendar_id"
    private const val KEY_SIDE = "run_side"
    private const val KEY_START = "run_start"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("feeding", Context.MODE_PRIVATE)

    fun calendarId(ctx: Context): Long = prefs(ctx).getLong(KEY_CALENDAR, -1L)

    fun setCalendarId(ctx: Context, id: Long) {
        prefs(ctx).edit().putLong(KEY_CALENDAR, id).commit()
    }

    fun running(ctx: Context): Session? {
        val p = prefs(ctx)
        val side = Side.of(p.getString(KEY_SIDE, null)) ?: return null
        return Session(side, p.getLong(KEY_START, 0L))
    }

    /** Starting the other side while one is running logs the current side first. */
    fun start(ctx: Context, side: Side) {
        val current = running(ctx)
        if (current?.side == side) return
        if (current != null) stop(ctx)
        prefs(ctx).edit()
            .putString(KEY_SIDE, side.code)
            .putLong(KEY_START, System.currentTimeMillis())
            .commit()
    }

    fun stop(ctx: Context) {
        val current = running(ctx) ?: return
        val end = System.currentTimeMillis()
        if (end - current.start >= MIN_DURATION_MS) {
            insertEvent(ctx, current.side, current.start, end)
        }
        prefs(ctx).edit().remove(KEY_SIDE).remove(KEY_START).commit()
    }

    private fun insertEvent(ctx: Context, side: Side, start: Long, end: Long) {
        val calendar = calendarId(ctx)
        if (calendar < 0) return
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar)
            put(Events.TITLE, "$TITLE_PREFIX – ${side.label}")
            put(Events.DTSTART, start)
            put(Events.DTEND, end)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        try {
            ctx.contentResolver.insert(Events.CONTENT_URI, values)
        } catch (e: SecurityException) {
            // permission revoked; nothing sensible to do from a widget tap
        }
    }

    /** Latest feed in the calendar, including ones added or edited by hand. */
    fun lastFeed(ctx: Context): LastFeed? {
        val calendar = calendarId(ctx)
        if (calendar < 0) return null
        val now = System.currentTimeMillis()
        val selection = "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0 " +
            "AND ${Events.TITLE} LIKE ? AND ${Events.DTSTART}>=? AND ${Events.DTEND}<=?"
        val args = arrayOf(
            calendar.toString(),
            "$TITLE_PREFIX%",
            (now - LOOKBACK_MS).toString(),
            (now + 60_000).toString(),
        )
        return try {
            ctx.contentResolver.query(
                Events.CONTENT_URI,
                arrayOf(Events.TITLE, Events.DTEND),
                selection,
                args,
                "${Events.DTEND} DESC",
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val title = c.getString(0).orEmpty()
                val side = if (title.contains(Side.RIGHT.label, ignoreCase = true)) Side.RIGHT else Side.LEFT
                LastFeed(side, c.getLong(1))
            }
        } catch (e: SecurityException) {
            null
        }
    }
}
