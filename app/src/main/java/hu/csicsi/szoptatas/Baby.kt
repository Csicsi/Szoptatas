package hu.csicsi.szoptatas

import android.content.Context
import java.util.Calendar

/** Birth date (defaults to 13 Nov 2026) and helpers that depend on it. */
object Baby {
    private const val KEY_BIRTH = "birth"
    const val DAY = 24L * 60 * 60 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("feeding", Context.MODE_PRIVATE)

    fun birth(ctx: Context): Long = prefs(ctx).getLong(KEY_BIRTH, Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 13, 0, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis)

    fun setBirth(ctx: Context, t: Long) = prefs(ctx).edit().putLong(KEY_BIRTH, t).apply()

    fun ageDays(ctx: Context, at: Long = System.currentTimeMillis()) = (at - birth(ctx)).toDouble() / DAY

    /** e.g. "63." — WHO weight-for-age percentile for boys. */
    fun percentileText(ctx: Context, grams: Int, at: Long = System.currentTimeMillis()): String {
        val p = Growth.percentile(ageDays(ctx, at), grams / 1000.0)
        return when {
            p < 1 -> "<1."
            p > 99 -> ">99."
            else -> "${Math.round(p)}."
        }
    }
}
