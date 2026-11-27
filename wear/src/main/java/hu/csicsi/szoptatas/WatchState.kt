package hu.csicsi.szoptatas

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

/** Everything the phone publishes under "/state". */
data class WatchState(
    val running: String,
    val start: Long,
    val last: String,
    val lastEnd: Long,
    val feeds: Int,
    val feedMinutes: Long,
    val wet: Int,
    val dirty: Int,
    val formulaMl: Int,
    val milkMl: Int,
    val momPeeMl: Int,
    val momPeeCount: Int,
    val vitamin: Boolean,
    val ibuprofen: Long,
    val ibuprofenHours: Int,
    val msg: String,
    val msgAt: Long,
    val bottle: String,
    val bottleAt: Long,
) {
    companion object {
        fun from(m: DataMap) = WatchState(
            running = m.getString("running", ""),
            start = m.getLong("start"),
            last = m.getString("last", ""),
            lastEnd = m.getLong("lastEnd"),
            feeds = m.getInt("feeds"),
            feedMinutes = m.getLong("feedMinutes"),
            wet = m.getInt("wet"),
            dirty = m.getInt("dirty"),
            formulaMl = m.getInt("formulaMl"),
            milkMl = m.getInt("milkMl"),
            momPeeMl = m.getInt("momPeeMl"),
            momPeeCount = m.getInt("momPeeCount"),
            vitamin = m.getBoolean("vitamin"),
            ibuprofen = m.getLong("ibuprofen"),
            ibuprofenHours = m.getInt("ibuprofenHours", 6),
            msg = m.getString("msg", ""),
            msgAt = m.getLong("msgAt"),
            bottle = m.getString("bottle", ""),
            bottleAt = m.getLong("bottleAt"),
        )

        fun read(ctx: Context, done: (WatchState?) -> Unit) {
            Wearable.getDataClient(ctx).dataItems
                .addOnSuccessListener { items ->
                    var state: WatchState? = null
                    items.forEach { item ->
                        if (item.uri.path == "/state") state = from(DataMapItem.fromDataItem(item).dataMap)
                    }
                    items.release()
                    done(state)
                }
                .addOnFailureListener { done(null) }
        }

        /** Sends a request to the phone app; [text] goes along as the payload. */
        fun send(ctx: Context, path: String, text: String = "") {
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach {
                    Wearable.getMessageClient(ctx).sendMessage(it.id, path, text.toByteArray(Charsets.UTF_8))
                }
            }
        }

        fun label(code: String) = when (code) {
            "L" -> "Bal"
            "R" -> "Jobb"
            else -> ""
        }
    }
}
