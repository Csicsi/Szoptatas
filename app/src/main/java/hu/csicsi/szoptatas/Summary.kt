package hu.csicsi.szoptatas

import android.content.Context
import java.util.Calendar

/** Numbers for today, parsed from calendar titles (app-made and hand-written ones). */
data class Summary(
    val feeds: Int,
    val feedMinutes: Long,
    val wet: Int,
    val dirty: Int,
    val formulaMl: Int,
    val breastMilkMl: Int,
    val vitaminToday: Boolean,
    val lastIbuprofen: Long?,
    val lastWeight: Int?,
    val momPeeMl: Int,
    val momPeeCount: Int,
) {
    companion object {
        private val FORMULA = Regex("""(?i)(tápszer|táp)\D{0,3}(\d+)\s*ml""")
        private val MILK = Regex("""(?i)(anyatej|tej)\D{0,3}(\d+)\s*ml""")
        private val MOM_PEE = Regex("""(?i)anya pisi\D{0,3}(\d+)\s*ml""")
        private val WEIGHT = Regex("""(?i)^(súly\s*)?(\d{4,5})\s*(g|gr)\b""")

        fun load(ctx: Context): Summary {
            val now = System.currentTimeMillis()
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val month = Feeding.entriesSince(ctx, now - 30L * 24 * 60 * 60 * 1000)
            val today = month.filter { it.start >= midnight }

            val feeds = today.filter { Feeding.isFeed(it.title) && !it.title.endsWith(Feeding.RUNNING_MARK) }
            return Summary(
                feeds = feeds.size,
                // only entries with "HH:mm-HH:mm" in the title have a known duration
                feedMinutes = feeds.sumOf { e -> Feeding.parsedRange(e)?.let { (it.second - it.first) / 60_000 } ?: 0L },
                wet = today.count { it.title.contains("pisis", ignoreCase = true) },
                dirty = today.count { it.title.contains("kakis", ignoreCase = true) },
                formulaMl = today.sumOf { FORMULA.find(it.title)?.groupValues?.get(2)?.toIntOrNull() ?: 0 },
                breastMilkMl = today.sumOf { MILK.find(it.title)?.groupValues?.get(2)?.toIntOrNull() ?: 0 },
                vitaminToday = today.any { it.title.contains("vitamin", ignoreCase = true) },
                lastIbuprofen = month.lastOrNull { it.title.contains("ibuprofen", ignoreCase = true) }?.start,
                momPeeMl = today.sumOf { MOM_PEE.find(it.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                momPeeCount = today.count { MOM_PEE.containsMatchIn(it.title) },
                lastWeight = month.lastOrNull { WEIGHT.containsMatchIn(it.title.trim()) }
                    ?.let { WEIGHT.find(it.title.trim())?.groupValues?.get(2)?.toIntOrNull() },
            )
        }
    }
}
