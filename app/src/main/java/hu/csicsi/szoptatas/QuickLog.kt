package hu.csicsi.szoptatas

import android.content.Context
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap entries from places without a dialog (widget, watch).
 * Instead of asking, a repeat of the same entry within 2 minutes is skipped.
 */
object QuickLog {
    private const val WINDOW_MS = 2 * 60_000L

    sealed class Result {
        object Added : Result()
        data class Duplicate(val at: Long) : Result()
        object Failed : Result()
    }

    fun log(ctx: Context, title: String): Result {
        val now = System.currentTimeMillis()
        val previous = Feeding.entriesSince(ctx, now - WINDOW_MS)
            .lastOrNull { it.title.equals(title, ignoreCase = true) }
        if (previous != null) return Result.Duplicate(previous.start)
        Feeding.logNow(ctx, title) ?: return Result.Failed
        return Result.Added
    }

    /** Short feedback text, also used on the watch. */
    fun message(title: String, r: Result): String = when (r) {
        Result.Added -> "$title hozzáadva"
        is Result.Duplicate -> "Már rögzítve ${hhmm(r.at)}-kor"
        Result.Failed -> "Nem sikerült menteni"
    }

    fun toast(ctx: Context, title: String, r: Result) {
        Toast.makeText(ctx, message(title, r), Toast.LENGTH_SHORT).show()
    }

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
}
