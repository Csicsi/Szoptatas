package hu.csicsi.szoptatas

import android.content.Context
import java.util.Calendar

data class DayStats(
    val dayStart: Long,
    val feeds: Int,
    val feedMinutes: Long,
    val wet: Int,
    val dirty: Int,
    val formulaMl: Int,
    val breastMilkMl: Int,
    val momPeeMl: Int,
)

data class WeightPoint(val time: Long, val grams: Int)

/** Parses calendar titles (app-made and hand-written) into numbers for the dashboard. */
object Stats {
    val FORMULA = Regex("""(?i)(tápszer|táp)\D{0,3}(\d+)\s*ml""")
    val MILK = Regex("""(?i)(anyatej|tej)\D{0,3}(\d+)\s*ml""")
    val MOM_PEE = Regex("""(?i)anya pisi\D{0,3}(\d+)\s*ml""")
    val WEIGHT = Regex("""(?i)^(súly\s*)?(\d{4,5})\s*(g|gr)\b""")

    private const val DAY = 24L * 60 * 60 * 1000

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun isFinishedFeed(e: Entry) = Feeding.isFeed(e.title) && !e.title.endsWith(Feeding.RUNNING_MARK) &&
        !MOM_PEE.containsMatchIn(e.title)

    fun day(entries: List<Entry>, dayStart: Long): DayStats {
        val d = entries.filter { it.start >= dayStart && it.start < dayStart + DAY }
        val feeds = d.filter { isFinishedFeed(it) }
        return DayStats(
            dayStart = dayStart,
            feeds = feeds.size,
            feedMinutes = feeds.sumOf { e -> Feeding.parsedRange(e)?.let { (it.second - it.first) / 60_000 } ?: 0L },
            wet = d.count { it.title.contains("pisis", ignoreCase = true) },
            dirty = d.count { it.title.contains("kakis", ignoreCase = true) },
            formulaMl = d.sumOf { FORMULA.find(it.title)?.groupValues?.get(2)?.toIntOrNull() ?: 0 },
            breastMilkMl = d.sumOf { MILK.find(it.title)?.groupValues?.get(2)?.toIntOrNull() ?: 0 },
            momPeeMl = d.sumOf { MOM_PEE.find(it.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    /** Last [days] days including today, oldest first. */
    fun lastDays(ctx: Context, days: Int): List<DayStats> {
        val today = startOfDay(System.currentTimeMillis())
        val from = today - (days - 1) * DAY
        val entries = Feeding.entriesSince(ctx, from)
        return (0 until days).map { day(entries, from + it * DAY) }
    }

    fun weights(ctx: Context, since: Long): List<WeightPoint> =
        Feeding.entriesSince(ctx, since).mapNotNull { e ->
            WEIGHT.find(e.title.trim())?.groupValues?.get(2)?.toIntOrNull()?.let { WeightPoint(e.start, it) }
        }

    /** Gaps between feed starts within the last 24 h, in minutes. */
    fun feedGaps(ctx: Context): List<Long> {
        val now = System.currentTimeMillis()
        val starts = Feeding.entriesSince(ctx, now - 2 * DAY)
            .filter { isFinishedFeed(it) }
            .map { Feeding.parsedRange(it)?.first ?: it.start }
            .filter { it >= now - DAY }
            .sorted()
        return starts.zipWithNext { a, b -> (b - a) / 60_000 }.filter { it > 0 }
    }
}
