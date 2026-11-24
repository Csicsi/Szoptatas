package hu.csicsi.szoptatas

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full watch app: everything the phone screen has except weight and notes. Scroll with the bezel. */
class NoelActivity : Activity(), DataClient.OnDataChangedListener {

    private lateinit var scroll: ScrollView
    private lateinit var badge: TextView
    private lateinit var status: TextView
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var btnIbuprofen: Button
    private lateinit var btnVitamin: Button
    private lateinit var btnMomPee: Button
    private lateinit var summary: TextView

    private var state: WatchState? = null
    private var shownMsgAt = 0L
    private var armed: String? = null // second tap needed for a likely duplicate
    private var armedAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(36), dp(14), dp(48))
        }

        badge = TextView(this).apply {
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        status = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(badge)
        root.addView(status)

        btnLeft = button("Bal", IDLE) { feed("/feed/left") }
        btnRight = button("Jobb", IDLE) { feed("/feed/right") }
        root.addView(row(btnLeft, btnRight, button("Stop", STOP) { feed("/feed/stop") }))

        root.addView(header("Pelus"))
        root.addView(row(
            button("Pisi", PEE, PEE_TEXT) { send("/log/pee") },
            button("Kaki", POO) { send("/log/poo") },
            button("Mind", BOTH) { send("/log/both") },
        ))

        root.addView(header("Etetés"))
        root.addView(row(
            button("Táp", FORMULA) { amount("Tápszer", 30, 5) },
            button("Anyatej", MILK) { amount("Anyatej", 30, 5) },
        ))

        root.addView(header("Anya"))
        btnIbuprofen = button("Ibuprofen", PILL) { ibuprofen() }.apply { maxLines = 2 }
        btnMomPee = button("Pisi ml", MOM) { amount("Anya pisi", 100, 10) }.apply { maxLines = 2 }
        root.addView(row(btnIbuprofen))
        root.addView(row(btnMomPee))

        root.addView(header("Egyéb"))
        btnVitamin = button("D-vitamin", VITAMIN) { vitamin() }
        root.addView(row(btnVitamin))

        summary = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(summary)

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFocusableInTouchMode = true // so the rotating bezel scrolls it
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        WatchState.send(this, "/sync")
        load()
        handler.post(tick)
        scroll.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
        handler.removeCallbacks(tick)
    }

    override fun onDataChanged(events: DataEventBuffer) = load()

    /** Bezel scrolling, in case the ScrollView doesn't take the rotary event itself. */
    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_SCROLL &&
            event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
            scroll.smoothScrollBy(0, (delta * dp(64)).toInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun load() = WatchState.read(this) { s ->
        state = s
        render()
        if (s != null && s.msg.isNotEmpty() && s.msgAt != shownMsgAt &&
            System.currentTimeMillis() - s.msgAt < 15_000
        ) {
            shownMsgAt = s.msgAt
            Toast.makeText(this, s.msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        val s = state
        if (s == null) {
            setBadge("NINCS KAPCSOLAT", false)
            status.text = "Nyisd meg a Noelt a telefonon"
            summary.text = ""
            return
        }
        var left = IDLE
        var right = IDLE
        if (s.running.isNotEmpty()) {
            setBadge("SZOPI FUT · ${WatchState.label(s.running).uppercase()}", true)
            status.text = since(s.start)
            if (s.running == "L") left = ACTIVE else right = ACTIVE
        } else {
            setBadge("SZÜNET", false)
            status.text = if (s.lastEnd > 0) since(s.lastEnd) else "–"
            if (s.last == "L") right = SUGGEST else if (s.last == "R") left = SUGGEST
        }
        btnLeft.background = rounded(left)
        btnRight.background = rounded(right)

        if (s.ibuprofen > 0) {
            val next = s.ibuprofen + s.ibuprofenHours * 3_600_000L
            val left2 = next - System.currentTimeMillis()
            if (left2 > 0) {
                val m = (left2 + 59_999) / 60_000
                btnIbuprofen.text = "Ibuprofen ${hhmm(next)}\nmég ${m / 60} ó ${m % 60} p"
                btnIbuprofen.background = rounded(PILL)
            } else {
                btnIbuprofen.text = "Ibuprofen ${hhmm(next)}\nesedékes"
                btnIbuprofen.background = rounded(DUE)
            }
        }
        btnVitamin.text = if (s.vitamin) "D-vitamin ma kész" else "D-vitamin"
        btnVitamin.background = rounded(if (s.vitamin) DONE else VITAMIN)
        btnMomPee.text = if (s.momPeeCount == 0) "Anya pisi (ml)" else "Anya pisi (ml)\nma ${s.momPeeMl} ml"

        val parts = mutableListOf("Ma: ${s.feeds} szopi · ${s.feedMinutes} perc", "pisis ${s.wet} · kakis ${s.dirty}")
        if (s.formulaMl + s.milkMl > 0) parts += "táp ${s.formulaMl} ml · anyatej ${s.milkMl} ml"
        summary.text = parts.joinToString("\n")
    }

    // ---- actions ----

    private fun feed(path: String) {
        WatchState.send(this, path)
        haptic()
        status.text = "…"
    }

    private fun send(path: String, text: String = "") {
        WatchState.send(this, path, text)
        haptic()
    }

    /** Taps that would likely duplicate need a second tap within 4 s. */
    private fun confirmOrArm(key: String, warning: String?): Boolean {
        if (warning == null) return true
        val now = System.currentTimeMillis()
        if (armed == key && now - armedAt < 4_000) {
            armed = null
            return true
        }
        armed = key
        armedAt = now
        Toast.makeText(this, "$warning – koppints újra", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun ibuprofen() {
        val last = state?.ibuprofen ?: 0L
        val recent = last > 0 && System.currentTimeMillis() - last < 60 * 60_000L
        if (confirmOrArm("ibu", if (recent) "Már bevéve ${hhmm(last)}-kor" else null)) send("/log/text", "Ibuprofen")
    }

    private fun vitamin() {
        if (confirmOrArm("vit", if (state?.vitamin == true) "Ma már megvolt" else null)) {
            send("/log/text", "D-vitamin (Noel)")
        }
    }

    private fun amount(kind: String, start: Int, step: Int) {
        startActivity(Intent(this, AmountActivity::class.java).apply {
            putExtra("kind", kind)
            putExtra("start", start)
            putExtra("step", step)
        })
    }

    // ---- view helpers ----

    private fun setBadge(text: String, running: Boolean) {
        badge.text = text
        badge.setTextColor(if (running) Color.WHITE else MUTED)
        badge.background = GradientDrawable().apply {
            setColor(if (running) ACTIVE else Color.parseColor("#2D2A31"))
            cornerRadius = dp(12).toFloat()
        }
        status.setTextColor(if (running) Color.parseColor("#F48FB1") else Color.WHITE)
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(MUTED)
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        buttons.forEach {
            addView(it, LinearLayout.LayoutParams(0, dp(if (it.maxLines > 1) 52 else 44), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun button(label: String, color: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        maxLines = 1
        setTextColor(textColor)
        stateListAnimator = null
        setPadding(dp(4), 0, dp(4), 0)
        background = rounded(color)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }

    private fun haptic() = window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

    private fun since(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        return if (m < 60) "$m perc" else "${m / 60} ó ${m % 60} p"
    }

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val MUTED = Color.parseColor("#9C95A4")
        val IDLE = Color.parseColor("#3A3740")
        val ACTIVE = Color.parseColor("#D6336C")
        val SUGGEST = Color.parseColor("#5A2A3A")
        val STOP = Color.parseColor("#4A4650")
        val PEE = Color.parseColor("#F2C14E")
        val PEE_TEXT = Color.parseColor("#3A2E05")
        val POO = Color.parseColor("#8B5E3C")
        val BOTH = Color.parseColor("#A97A35")
        val FORMULA = Color.parseColor("#3D7EA6")
        val MILK = Color.parseColor("#C2577D")
        val PILL = Color.parseColor("#6B5CA5")
        val MOM = Color.parseColor("#3E8C82")
        val VITAMIN = Color.parseColor("#E0892E")
        val DONE = Color.parseColor("#2E7D32")
        val DUE = Color.parseColor("#C62828")
    }
}
