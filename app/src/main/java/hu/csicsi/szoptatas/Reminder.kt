package hu.csicsi.szoptatas

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Ibuprofen interval as you set it (from your doctor's instructions), used for the
 * countdown on the button and the optional notification. The last dose is read from
 * the shared calendar, so the notification fires on every phone that has it enabled.
 */
object Reminder {
    private const val KEY_INTERVAL = "ibuprofen_interval"
    private const val KEY_NOTIFY = "ibuprofen_notify"
    private const val KEY_NOTIFIED = "ibuprofen_notified_for"
    private const val CHANNEL = "ibuprofen"
    private const val LOOKBACK_MS = 2L * 24 * 60 * 60 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("feeding", Context.MODE_PRIVATE)

    fun hours(ctx: Context) = prefs(ctx).getInt(KEY_INTERVAL, 6)

    fun setHours(ctx: Context, h: Int) {
        prefs(ctx).edit().putInt(KEY_INTERVAL, h).apply()
        schedule(ctx)
    }

    fun notifyOn(ctx: Context) = prefs(ctx).getBoolean(KEY_NOTIFY, false)

    fun setNotify(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_NOTIFY, on).apply()
        schedule(ctx)
    }

    fun lastDose(ctx: Context): Long? = Feeding.entriesSince(ctx, System.currentTimeMillis() - LOOKBACK_MS)
        .lastOrNull { it.title.contains("ibuprofen", ignoreCase = true) }?.start

    /** (Re)plan the alarm for "last dose + interval". Cheap; call after any change. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val last = lastDose(ctx)
        if (!notifyOn(ctx) || last == null) {
            am.cancel(pending(ctx))
            return
        }
        val at = last + hours(ctx) * 3_600_000L
        if (at <= System.currentTimeMillis()) check(ctx) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
    }

    /** Notify once per dose, if the next one is due. */
    fun check(ctx: Context) {
        if (!notifyOn(ctx)) return
        val last = lastDose(ctx) ?: return
        if (System.currentTimeMillis() < last + hours(ctx) * 3_600_000L) {
            schedule(ctx)
            return
        }
        if (prefs(ctx).getLong(KEY_NOTIFIED, 0L) == last) return
        prefs(ctx).edit().putLong(KEY_NOTIFIED, last).apply()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ibuprofen emlékeztető", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 21, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Ibuprofen")
            .setContentText("Következő adag ideje (${hours(ctx)} óra telt el).")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(1, n)
        } catch (e: SecurityException) {
            // notification permission denied
        }
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 20, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // a dose logged on the other phone may not have synced yet: pull first, then check
        Feeding.requestSync(ctx)
        val pending = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Reminder.check(ctx)
            pending.finish()
        }, 8_000)
    }
}
