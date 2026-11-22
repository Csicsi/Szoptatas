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

/** Receives "/feed/left|right|stop" from the watch and writes the calendar. */
class WearListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        var message = ""
        when (event.path) {
            "/feed/left" -> Feeding.start(this, Side.LEFT)
            "/feed/right" -> Feeding.start(this, Side.RIGHT)
            "/feed/stop" -> Feeding.stop(this)
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
