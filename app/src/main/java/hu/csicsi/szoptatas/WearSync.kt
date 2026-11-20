package hu.csicsi.szoptatas

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** Pushes the feeding state to the watch tile (Data Layer item "/state"). */
object WearSync {
    const val STATE_PATH = "/state"

    fun publish(ctx: Context) {
        val running = Feeding.running(ctx)
        val last = if (running == null) Feeding.lastFeed(ctx) else null
        val req = PutDataMapRequest.create(STATE_PATH).apply {
            dataMap.putString("running", running?.side?.code ?: "")
            dataMap.putLong("start", running?.start ?: 0L)
            dataMap.putString("last", last?.side?.code ?: "")
            dataMap.putLong("lastEnd", last?.end ?: 0L)
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
        when (event.path) {
            "/feed/left" -> Feeding.start(this, Side.LEFT)
            "/feed/right" -> Feeding.start(this, Side.RIGHT)
            "/feed/stop" -> Feeding.stop(this)
            else -> return
        }
        FeedWidget.refreshAll(this)
        WearSync.publish(this)
    }
}
