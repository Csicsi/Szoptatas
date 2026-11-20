package hu.csicsi.szoptatas

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FeedWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, build(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_LEFT -> Feeding.start(ctx, Side.LEFT)
            ACTION_RIGHT -> Feeding.start(ctx, Side.RIGHT)
            ACTION_STOP -> Feeding.stop(ctx)
            ACTION_REFRESH -> Unit
            else -> {
                super.onReceive(ctx, intent)
                return
            }
        }
        refreshAll(ctx)
    }

    companion object {
        private const val ACTION_LEFT = "hu.csicsi.szoptatas.LEFT"
        private const val ACTION_RIGHT = "hu.csicsi.szoptatas.RIGHT"
        private const val ACTION_STOP = "hu.csicsi.szoptatas.STOP"
        private const val ACTION_REFRESH = "hu.csicsi.szoptatas.REFRESH"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FeedWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx))
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_feed)
            val configured = Feeding.calendarId(ctx) >= 0
            val running = Feeding.running(ctx)

            var leftBg = R.drawable.btn_idle
            var rightBg = R.drawable.btn_idle

            when {
                !configured -> {
                    v.setTextViewText(R.id.status, ctx.getString(R.string.setup_needed))
                    v.setViewVisibility(R.id.timer, View.GONE)
                }
                running != null -> {
                    v.setTextViewText(R.id.status, "${running.side.label} fut · kezdés ${hhmm(running.start)}")
                    showTimerSince(v, running.start)
                    if (running.side == Side.LEFT) leftBg = R.drawable.btn_active else rightBg = R.drawable.btn_active
                }
                else -> {
                    val last = Feeding.lastFeed(ctx)
                    if (last == null) {
                        v.setTextViewText(R.id.status, ctx.getString(R.string.no_entries))
                        v.setViewVisibility(R.id.timer, View.GONE)
                    } else {
                        val next = last.side?.other()
                        v.setTextViewText(
                            R.id.status,
                            if (next == null) "Utolsó szopi vége ${hhmm(last.end)}" else "Utolsó: ${next.other().label}, vége ${hhmm(last.end)} → most: ${next.label}",
                        )
                        showTimerSince(v, last.end)
                        if (next == Side.LEFT) leftBg = R.drawable.btn_suggest else if (next == Side.RIGHT) rightBg = R.drawable.btn_suggest
                    }
                }
            }

            v.setInt(R.id.btn_left, "setBackgroundResource", leftBg)
            v.setInt(R.id.btn_right, "setBackgroundResource", rightBg)

            if (configured) {
                v.setOnClickPendingIntent(R.id.btn_left, broadcast(ctx, ACTION_LEFT, 1))
                v.setOnClickPendingIntent(R.id.btn_right, broadcast(ctx, ACTION_RIGHT, 2))
                v.setOnClickPendingIntent(R.id.btn_stop, broadcast(ctx, ACTION_STOP, 3))
                v.setOnClickPendingIntent(R.id.status, broadcast(ctx, ACTION_REFRESH, 4))
            } else {
                val open = openApp(ctx)
                listOf(R.id.btn_left, R.id.btn_right, R.id.btn_stop, R.id.status)
                    .forEach { v.setOnClickPendingIntent(it, open) }
            }
            return v
        }

        /** Chronometer counts up on its own, so the widget needs no periodic updates for it. */
        private fun showTimerSince(v: RemoteViews, wallClockStart: Long) {
            val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wallClockStart)
            v.setViewVisibility(R.id.timer, View.VISIBLE)
            v.setChronometer(R.id.timer, base, null, true)
        }

        private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

        private fun broadcast(ctx: Context, action: String, req: Int): PendingIntent {
            val i = Intent(ctx, FeedWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun openApp(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
            return PendingIntent.getActivity(
                ctx, 10, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
