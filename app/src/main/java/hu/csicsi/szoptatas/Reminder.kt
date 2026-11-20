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
 * Optional "Ibuprofen: X óra telt el" notification on this phone.
 * It only reports elapsed time; how often to take it is up to you and your doctor.
 */
object Reminder {
    private const val KEY_HOURS = "ibuprofen_hours"
    private const val CHANNEL = "ibuprofen"

    /** 0 = off. */
    fun hours(ctx: Context) = ctx.getSharedPreferences("feeding", Context.MODE_PRIVATE).getInt(KEY_HOURS, 0)

    fun setHours(ctx: Context, h: Int) {
        ctx.getSharedPreferences("feeding", Context.MODE_PRIVATE).edit().putInt(KEY_HOURS, h).apply()
        if (h == 0) cancel(ctx)
    }

    fun scheduleAfterDose(ctx: Context) {
        val h = hours(ctx)
        if (h <= 0) return
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
            .setContentText("${hours(ctx)} óra telt el az utolsó óta.")
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
