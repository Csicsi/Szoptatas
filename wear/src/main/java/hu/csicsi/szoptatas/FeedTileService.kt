package hu.csicsi.szoptatas

import android.content.Context
import android.graphics.Color
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.ListenableFuture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** State as published by the phone. */
private data class TileState(val running: String, val start: Long, val last: String, val lastEnd: Long)

private fun label(code: String) = when (code) {
    "L" -> "Bal"
    "R" -> "Jobb"
    else -> ""
}

/** Tile with Bal / Jobb / Stop. The phone does the calendar work. */
class FeedTileService : TileService() {

    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val clicked = req.currentState.lastClickableId
        var pending: String? = null
        when (clicked) {
            "left", "right", "stop" -> {
                send(this, "/feed/$clicked")
                pending = clicked
            }
        }
        return CallbackToFutureAdapter.getFuture { completer ->
            readState(this) { state -> completer.set(tile(state, pending)) }
            "tile"
        }
    }

    override fun onTileResourcesRequest(
        req: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())
            "res"
        }

    private fun tile(state: TileState?, pending: String?): TileBuilders.Tile {
        val status = when {
            pending == "stop" -> "Leállítva…"
            pending != null -> "${if (pending == "left") "Bal" else "Jobb"} indul…"
            state == null -> "Nincs kapcsolat a telefonnal"
            state.running.isNotEmpty() -> "${label(state.running)} fut · ${hhmm(state.start)} óta"
            state.last.isNotEmpty() -> "Utolsó szopi: ${label(state.last)} ${hhmm(state.lastEnd)}".replace("  ", " ")
            else -> "Még nincs bejegyzés"
        }
        val running = if (pending == null) state?.running.orEmpty() else ""
        val suggest = if (running.isEmpty() && pending == null && state != null && (state.last == "L" || state.last == "R")) {
            if (state.last == "L") "R" else "L"
        } else ""

        val buttons = LayoutElementBuilders.Row.Builder()
            .addContent(button("left", "Bal", colorFor("L", running, suggest)))
            .addContent(spacer())
            .addContent(button("right", "Jobb", colorFor("R", running, suggest)))
            .build()

        val layout = LayoutElementBuilders.Column.Builder()
            .addContent(text(status, 13f))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(buttons)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(button("stop", "Stop", STOP))
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    private fun colorFor(side: String, running: String, suggest: String) = when (side) {
        running -> ACTIVE
        suggest -> SUGGEST
        else -> IDLE
    }

    private fun spacer() = LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build()

    private fun text(s: String, size: Float) = LayoutElementBuilders.Text.Builder()
        .setText(s)
        .setMaxLines(2)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(Color.WHITE))
                .build(),
        )
        .build()

    private fun button(id: String, label: String, color: Int) = LayoutElementBuilders.Box.Builder()
        .setWidth(dp(if (id == "stop") 72f else 60f))
        .setHeight(dp(if (id == "stop") 40f else 52f))
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(
                    ModifiersBuilders.Clickable.Builder()
                        .setId(id)
                        .setOnClick(ActionBuilders.LoadAction.Builder().build())
                        .build(),
                )
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(argb(color))
                        .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                        .build(),
                )
                .build(),
        )
        .addContent(text(label, 15f))
        .build()

    companion object {
        private const val RES_VERSION = "1"
        private val IDLE = Color.parseColor("#4A4A4A")
        private val ACTIVE = Color.parseColor("#E91E63")
        private val SUGGEST = Color.parseColor("#8A3A5A")
        private val STOP = Color.parseColor("#5A5A5A")

        private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

        private fun send(ctx: Context, path: String) {
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, path, ByteArray(0)) }
            }
        }

        private fun readState(ctx: Context, done: (TileState?) -> Unit) {
            Wearable.getDataClient(ctx).dataItems
                .addOnSuccessListener { items ->
                    var state: TileState? = null
                    items.forEach { item ->
                        if (item.uri.path == "/state") {
                            val m = DataMapItem.fromDataItem(item).dataMap
                            state = TileState(
                                m.getString("running", ""), m.getLong("start"),
                                m.getString("last", ""), m.getLong("lastEnd"),
                            )
                        }
                    }
                    items.release()
                    done(state)
                }
                .addOnFailureListener { done(null) }
        }
    }
}

/** Phone published a new state: redraw the tile. */
class StateListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.type == DataEvent.TYPE_CHANGED }) {
            TileService.getUpdater(this).requestUpdate(FeedTileService::class.java)
        }
    }
}
