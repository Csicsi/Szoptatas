package hu.csicsi.szoptatas

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** Pushes the feeding state to the watch tile (Data Layer item "/state"). */
object WearSync {
    const val STATE_PATH = "/state"

    /** [message] is shown briefly on the tile, e.g. "Kakis pelus hozzáadva". */
    fun publish(ctx: Context, message: String = "") {
        val running = Feeding.running(ctx)
        val last = if (running == null) Feeding.lastFeed(ctx) else null
        val req = PutDataMapRequest.create(STATE_PATH).apply {
            dataMap.putString("running", running?.side?.code ?: "")
            dataMap.putLong("start", running?.start ?: 0L)
            dataMap.putString("last", last?.let { it.side?.code ?: "?" } ?: "")
            dataMap.putLong("lastEnd", last?.end ?: 0L)
            val bottle = if (running == null) Feeding.lastBottle(ctx) else null
            dataMap.putString("bottle", bottle?.label ?: "")
            dataMap.putLong("bottleAt", bottle?.time ?: 0L)
            val sum = Summary.load(ctx)
            dataMap.putInt("feeds", sum.feeds)
            dataMap.putLong("feedMinutes", sum.feedMinutes)
            dataMap.putInt("wet", sum.wet)
            dataMap.putInt("dirty", sum.dirty)
            dataMap.putInt("formulaMl", sum.formulaMl)
            dataMap.putInt("milkMl", sum.breastMilkMl)
            dataMap.putInt("momPeeMl", sum.momPeeMl)
            dataMap.putInt("momPeeCount", sum.momPeeCount)
            dataMap.putBoolean("vitamin", sum.vitaminToday)
            dataMap.putLong("ibuprofen", sum.lastIbuprofen ?: 0L)
            dataMap.putInt("ibuprofenHours", Reminder.hours(ctx))
            dataMap.putString("msg", message)
            dataMap.putLong("msgAt", if (message.isEmpty()) 0L else System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(ctx).putDataItem(req)
        } catch (e: Exception) {
            // no Play services / no watch: ignore
        }
    }
}

/** Entries the watch app may log as free text ("/log/text"); anything else is ignored. */
private val WATCH_TITLES = Regex("""^((Tápszer|Anyatej|Anya pisi) \d{1,4} ml|Ibuprofen|D-vitamin \(Noel\))$""")

/** Receives requests from the watch tile and watch app and writes the calendar. */
class WearListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        var message = ""
        when (event.path) {
            "/feed/left" -> Feeding.start(this, Side.LEFT)
            "/feed/right" -> Feeding.start(this, Side.RIGHT)
            "/feed/stop" -> Feeding.stop(this)
            "/sync" -> {
                // watch app opened: answer right away, then again after pulling the calendar
                WearSync.publish(this)
                Feeding.requestSync(this)
                Thread.sleep(6_000)
                WearSync.publish(this)
                return
            }
            "/log/text" -> {
                val title = String(event.data, Charsets.UTF_8).trim()
                if (!WATCH_TITLES.matches(title)) return
                message = QuickLog.message(title, QuickLog.log(this, title))
            }
            "/log/pee", "/log/poo", "/log/both" -> {
                val title = when (event.path) {
                    "/log/pee" -> "Pisis pelus"
                    "/log/poo" -> "Kakis pelus"
                    else -> "Kakis pisis pelus"
                }
                message = QuickLog.message(title, QuickLog.log(this, title))
            }
            else -> return
        }
        FeedAlert.schedule(this)
        Reminder.schedule(this)
        FeedWidget.refreshAll(this)
        WearSync.publish(this, message)
    }
}
