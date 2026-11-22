package hu.csicsi.szoptatas

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Optional "X óra óta nem evett" notification. Works from the shared calendar,
 * so feeds logged on the other phone count too (once they have synced).
 */
object FeedAlert {
    private const val KEY_HOURS = "feed_alert_hours"
    private const val KEY_NOTIFIED = "feed_alert_notified_for"
    private const val CHANNEL = "feed_alert"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("feeding", Context.MODE_PRIVATE)

    /** 0 = off. */
    fun hours(ctx: Context) = prefs(ctx).getInt(KEY_HOURS, 0)

    fun setHours(ctx: Context, h: Int) {
        prefs(ctx).edit().putInt(KEY_HOURS, h).apply()
        schedule(ctx)
    }

    /** (Re)plan the alarm for "last feed end + interval". Cheap; call after any change. */
    fun schedule(ctx: Context) {
        val h = hours(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (h <= 0 || Feeding.running(ctx) != null) {
            am.cancel(pending(ctx))
            return
        }
        val last = Feeding.lastFeed(ctx) ?: return
        val at = last.end + h * 3_600_000L
        if (at <= System.currentTimeMillis()) {
            check(ctx)
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
    }

    /** Called by the alarm: notify once per last feed if it is still overdue. */
    fun check(ctx: Context) {
        val h = hours(ctx)
        if (h <= 0 || Feeding.running(ctx) != null) return
        val last = Feeding.lastFeed(ctx) ?: return
        val due = last.end + h * 3_600_000L
        val now = System.currentTimeMillis()
        if (now < due) {
            schedule(ctx)
            return
        }
        if (prefs(ctx).getLong(KEY_NOTIFIED, 0L) == last.end) return
        prefs(ctx).edit().putLong(KEY_NOTIFIED, last.end).apply()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Etetés emlékeztető", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 31, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val mins = (now - last.end) / 60_000
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Noel")
            .setContentText("${mins / 60} ó ${mins % 60} p óta nem volt szopi")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(2, n)
        } catch (e: SecurityException) {
            // notification permission denied
        }
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 30, Intent(ctx, FeedAlertReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class FeedAlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // the alarm may fire before the other phone's feed synced: pull first, then check
        Feeding.requestSync(ctx)
        val pending = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            FeedAlert.check(ctx)
            pending.finish()
        }, 8_000)
    }
}
