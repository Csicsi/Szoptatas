package hu.csicsi.szoptatas

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Ibuprofen interval as you set it (from your doctor's instructions), used for the
 * countdown on the button and the optional notification on this phone.
 */
object Reminder {
    private const val KEY_INTERVAL = "ibuprofen_interval"
    private const val KEY_NOTIFY = "ibuprofen_notify"
    private const val CHANNEL = "ibuprofen"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("feeding", Context.MODE_PRIVATE)

    fun hours(ctx: Context) = prefs(ctx).getInt(KEY_INTERVAL, 6)

    fun setHours(ctx: Context, h: Int) {
        prefs(ctx).edit().putInt(KEY_INTERVAL, h).apply()
    }

    fun notifyOn(ctx: Context) = prefs(ctx).getBoolean(KEY_NOTIFY, false)

    fun setNotify(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_NOTIFY, on).apply()
        if (!on) cancel(ctx)
    }

    fun scheduleAfterDose(ctx: Context) {
        val h = hours(ctx)
        if (!notifyOn(ctx)) return
        val at = SystemClock.elapsedRealtime() + h * 60L * 60 * 1000
        alarmManager(ctx).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
    }

    fun cancel(ctx: Context) = alarmManager(ctx).cancel(pending(ctx))

    private fun alarmManager(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 20, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun notify(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ibuprofen emlékeztető", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 21, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
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
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Reminder.notify(ctx)
}
