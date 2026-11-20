package hu.csicsi.szoptatas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logger screen: every button writes one event into the chosen calendar. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var timer: Chronometer
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var lastLogged: TextView
    private lateinit var undo: Button
    private lateinit var summary: TextView
    private lateinit var btnVitamin: Button
    private lateinit var btnIbuprofen: Button
    private lateinit var btnReminder: Button

    private val handler = Handler(Looper.getMainLooper())
    private val reload = Runnable { refresh() }
    private val calendarObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // debounce: a sync often fires many changes in a row
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 500)
        }
    }
    private var observing = false

    private var lastUri: Uri? = null
    private var setupShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        val granted = checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!granted || Feeding.calendarId(this) < 0) {
            status.setText(R.string.setup_needed)
            if (!setupShown) {
                setupShown = true // open once; avoid a loop if the user backs out
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendarObserver)
        observing = true
        Feeding.requestSync(this) // pull what the other phone logged
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (observing) contentResolver.unregisterContentObserver(calendarObserver)
        observing = false
        handler.removeCallbacks(reload)
    }

    // ---- UI ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        summary = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(summary)

        status = TextView(this).apply { textSize = 18f }
        timer = Chronometer(this).apply { textSize = 44f; setTypeface(typeface, 1) }
        root.addView(status)
        root.addView(timer)

        btnLeft = bigButton("Bal", IDLE) { afterFeedChange(Feeding.start(this, Side.LEFT)) }
        btnRight = bigButton("Jobb", IDLE) { afterFeedChange(Feeding.start(this, Side.RIGHT)) }
        val btnStop = bigButton("Stop", STOP) { afterFeedChange(Feeding.stop(this)) }
        root.addView(row(btnLeft, btnRight, btnStop))

        root.addView(header("Pelus"))
        root.addView(row(
            bigButton("Pisis", PELUS) { log("Pisis pelus") },
            bigButton("Kakis", PELUS) { log("Kakis pelus") },
            bigButton("Mindkettő", PELUS) { log("Kakis pisis pelus") },
        ))

        root.addView(header("Etetés"))
        root.addView(row(
            bigButton("Tápszer", FOOD) { askMl("Tápszer") },
            bigButton("Anyatej", FOOD) { askMl("Anyatej") },
        ))

        root.addView(header("Egyéb"))
        btnIbuprofen = bigButton("Ibuprofen", OTHER) {
            log("Ibuprofen")
            Reminder.scheduleAfterDose(this)
        }
        btnVitamin = bigButton("Vitamin", OTHER) { log("Vitamin (Noel)") }
        btnIbuprofen.maxLines = 2 // name + last dose time
        root.addView(row(btnIbuprofen, btnVitamin))
        root.addView(row(
            bigButton("Súly", OTHER) { askWeight() },
            bigButton("Jegyzet", OTHER) { askNote() },
        ))

        lastLogged = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, 0)
        }
        undo = Button(this).apply {
            text = "Visszavonás"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                lastUri?.let { Feeding.delete(this@MainActivity, it) }
                lastUri = null
                lastLogged.text = "Visszavonva."
                visibility = View.GONE
                FeedWidget.refreshAll(this@MainActivity)
                refresh()
            }
        }
        root.addView(lastLogged)
        root.addView(undo)

        val settings = Button(this).apply {
            text = "Naptár beállítása"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(settings)

        btnReminder = Button(this).apply {
            isAllCaps = false
            setOnClickListener { pickReminder() }
        }
        root.addView(btnReminder)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val running = Feeding.running(this)
        var left = IDLE
        var right = IDLE
        if (running != null) {
            status.text = "${running.side.label} fut · kezdés ${hhmm(running.start)}"
            showSince(running.start)
            if (running.side == Side.LEFT) left = ACTIVE else right = ACTIVE
        } else {
            val last = Feeding.lastFeed(this)
            if (last == null) {
                status.text = getString(R.string.no_entries)
                timer.stop()
                timer.visibility = View.GONE
            } else {
                val next = last.side?.other()
                status.text = if (next == null) "Utolsó szopi vége ${hhmm(last.end)}" else "Utolsó szopi: ${next.other().label}, vége ${hhmm(last.end)} → most: ${next.label}"
                showSince(last.end)
                if (next == Side.LEFT) left = SUGGEST else if (next == Side.RIGHT) right = SUGGEST
            }
        }
        btnLeft.background = rounded(left)
        btnRight.background = rounded(right)

        val sum = Summary.load(this)
        val parts = mutableListOf("Ma: ${sum.feeds} szopi (${sum.feedMinutes} perc)", "pisis ${sum.wet}", "kakis ${sum.dirty}")
        if (sum.formulaMl > 0) parts += "táp ${sum.formulaMl} ml"
        if (sum.breastMilkMl > 0) parts += "anyatej ${sum.breastMilkMl} ml"
        sum.lastWeight?.let { parts += "súly $it g" }
        summary.text = parts.joinToString(" · ")

        btnVitamin.text = if (sum.vitaminToday) "Vitamin ✓" else "Vitamin"
        btnVitamin.background = rounded(if (sum.vitaminToday) DONE else OTHER)
        btnIbuprofen.text = sum.lastIbuprofen?.let {
            val h = (System.currentTimeMillis() - it) / 3_600_000
            val m = (System.currentTimeMillis() - it) / 60_000 % 60
            "Ibuprofen\n${hhmm(it)} (${h} ó ${m} p)"
        } ?: "Ibuprofen"

        val rh = Reminder.hours(this)
        btnReminder.text = if (rh == 0) "Ibuprofen emlékeztető: ki" else "Ibuprofen emlékeztető: $rh óra után"

        WearSync.publish(this)
    }

    private fun pickReminder() {
        val options = arrayOf("Ki", "4 óra", "6 óra", "8 óra")
        val hours = intArrayOf(0, 4, 6, 8)
        AlertDialog.Builder(this)
            .setTitle("Értesítés az utolsó Ibuprofen után")
            .setItems(options) { _, which ->
                Reminder.setHours(this, hours[which])
                if (hours[which] > 0 && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                refresh()
            }
            .show()
    }

    private fun showSince(wallClockStart: Long) {
        timer.base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wallClockStart)
        timer.visibility = View.VISIBLE
        timer.start()
    }

    // ---- actions ----

    private fun afterFeedChange(logged: Uri?) {
        if (logged != null) remember(logged, "Szoptatás")
        FeedWidget.refreshAll(this)
        refresh()
    }

    private fun log(title: String) {
        remember(Feeding.logNow(this, title), title)
    }

    private fun remember(uri: Uri?, title: String) {
        if (uri == null) {
            lastLogged.text = "Nem sikerült menteni – naptár beállítva?"
            undo.visibility = View.GONE
            return
        }
        lastUri = uri
        lastLogged.text = "Mentve: $title · ${hhmm(System.currentTimeMillis())}"
        undo.visibility = View.VISIBLE
        refresh()
    }

    private fun askMl(kind: String) {
        val amount = numberField("ml")
        val leftover = numberField("maradt az alján (ml, opcionális)")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(amount)
            addView(leftover)
        }
        AlertDialog.Builder(this)
            .setTitle(kind)
            .setView(box)
            .setPositiveButton("Mentés") { _, _ ->
                val ml = amount.text.toString().trim()
                if (ml.isEmpty()) return@setPositiveButton
                val rest = leftover.text.toString().trim()
                val title = if (rest.isEmpty()) "$kind $ml ml" else "$kind $ml ml (maradt $rest ml)"
                log(title)
            }
            .setNegativeButton("Mégse", null)
            .show()
        amount.requestFocus()
    }

    private fun askWeight() {
        val grams = numberField("gramm")
        AlertDialog.Builder(this)
            .setTitle("Súly")
            .setView(padded(grams))
            .setPositiveButton("Mentés") { _, _ ->
                val g = grams.text.toString().trim()
                if (g.isNotEmpty()) log("Súly $g g")
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun askNote() {
        val note = EditText(this).apply {
            hint = "jegyzet"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("Jegyzet")
            .setView(padded(note))
            .setPositiveButton("Mentés") { _, _ ->
                val t = note.text.toString().trim()
                if (t.isNotEmpty()) log(t)
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    // ---- small view helpers ----

    private fun numberField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false // otherwise a wrapped label shifts its button down
        buttons.forEach {
            addView(it, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        // shrink long labels (e.g. "Mindkettő") instead of wrapping them
        setAutoSizeTextTypeUniformWithConfiguration(12, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        maxLines = 1
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(color)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    private companion object {
        val IDLE = Color.parseColor("#4A4A4A")
        val ACTIVE = Color.parseColor("#E91E63")
        val SUGGEST = Color.parseColor("#8A3A5A")
        val STOP = Color.parseColor("#5A5A5A")
        val PELUS = Color.parseColor("#6D5A2E")
        val FOOD = Color.parseColor("#2E5A6D")
        val OTHER = Color.parseColor("#3E4E3A")
        val DONE = Color.parseColor("#2E7D32")
    }
}
