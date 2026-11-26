package hu.csicsi.szoptatas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Chronometer
import android.widget.EditText
import android.widget.HorizontalScrollView
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
    private lateinit var chips: LinearLayout
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private val hideBanner = Runnable { banner.animate().alpha(0f).withEndAction { banner.visibility = View.GONE } }
    private lateinit var btnVitamin: Button
    private lateinit var btnIbuprofen: Button
    private lateinit var btnMomPee: Button
    private lateinit var feedCard: LinearLayout
    private lateinit var badge: TextView
    private lateinit var badgeDot: View
    private val pulse by lazy {
        android.animation.ObjectAnimator.ofFloat(badgeDot, "alpha", 1f, 0.25f).apply {
            duration = 800
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
    }

    private var lastUri: Uri? = null
    private var setupShown = false
    private var lastIbuprofen: Long? = null

    private val handler = Handler(Looper.getMainLooper())
    private val reload = Runnable { refresh() }
    private val tick = object : Runnable {
        override fun run() {
            updateIbuprofen()
            handler.postDelayed(this, 30_000)
        }
    }
    private val calendarObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // debounce: a sync often fires many changes in a row
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 500)
        }
    }
    private var observing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi().apply { padForSystemBars() })
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
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 30_000)
    }

    override fun onPause() {
        super.onPause()
        if (observing) contentResolver.unregisterContentObserver(calendarObserver)
        observing = false
        handler.removeCallbacks(reload)
        handler.removeCallbacks(tick)
    }

    // ---- UI ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(96)) // room to scroll past the banner
        }

        // top bar: title, charts button, menu
        val menuButton = iconButton(R.drawable.ic_menu, "Menü") { showMenu(it) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 30f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(c(R.color.text))
                setPadding(dp(4), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_refresh, "Frissítés") { forceSync(it) })
            addView(iconButton(R.drawable.ic_chart, "Áttekintés") {
                startActivity(Intent(this@MainActivity, DashboardActivity::class.java))
            })
            addView(menuButton)
        })

        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // today's numbers open the dashboard
            setOnClickListener { startActivity(Intent(this@MainActivity, DashboardActivity::class.java)) }
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // feeding card: state badge (running / pause), status line, timer
        badgeDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
        badge = TextView(this).apply {
            textSize = 13f
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(14), dp(6))
            addView(badgeDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            addView(badge)
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(c(R.color.muted))
        }
        timer = Chronometer(this).apply {
            textSize = 56f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(c(R.color.text))
        }
        btnLeft = bigButton("Bal", c(R.color.neutral), R.drawable.ic_left, c(R.color.on_neutral)) {
            afterFeedChange(Feeding.start(this, Side.LEFT))
        }
        btnRight = bigButton("Jobb", c(R.color.neutral), R.drawable.ic_right, c(R.color.on_neutral)) {
            afterFeedChange(Feeding.start(this, Side.RIGHT))
        }
        val btnStop = bigButton("Stop", STOP, R.drawable.ic_stop) { afterFeedChange(Feeding.stop(this)) }
        feedCard = card("Szoptatás", wrap(badgeRow), status, timer, row(btnLeft, btnRight, btnStop))
        root.addView(feedCard)

        // nappies
        root.addView(card(
            "Pelus",
            row(
                bigButton("Pisis", PEE, R.drawable.ic_drop, PEE_TEXT) { guarded("Pisis pelus", 5 * MIN) { log("Pisis pelus") } },
                bigButton("Kakis", POO, R.drawable.ic_poop) { guarded("Kakis pelus", 5 * MIN) { log("Kakis pelus") } },
                bigButton("Mindkettő", POO, R.drawable.ic_both) {
                    guarded("Kakis pisis pelus", 5 * MIN) { log("Kakis pisis pelus") }
                }.apply {
                    background = gradient(PEE_DARK, POO)
                },
            ),
        ))

        root.addView(card(
            "Etetés",
            row(
                bigButton("Tápszer", FORMULA, R.drawable.ic_bottle) { guarded("Tápszer", 15 * MIN) { askMl("Tápszer") } },
                bigButton("Anyatej", MILK, R.drawable.ic_milk) { guarded("Anyatej", 15 * MIN) { askMl("Anyatej") } },
            ),
        ))

        btnIbuprofen = bigButton("Ibuprofen", PILL, R.drawable.ic_pill) {
            guarded("Ibuprofen", 60 * MIN) {
                log("Ibuprofen")
                Reminder.schedule(this)
            }
        }.apply { maxLines = 2 }
        btnMomPee = bigButton("Pisi (ml)", MOM, R.drawable.ic_cup) { guarded("Anya pisi", 2 * MIN) { askMomPee() } }.apply { maxLines = 2 }
        root.addView(card("Anya", row(btnIbuprofen, btnMomPee)))

        btnVitamin = bigButton("D-vitamin", VITAMIN, R.drawable.ic_sun) {
            guarded("vitamin", sinceMidnight = true, contains = true) { log("D-vitamin (Noel)") }
        }
        root.addView(card(
            "Egyéb",
            row(
                btnVitamin,
                bigButton("Súly", GREY, R.drawable.ic_scale) { guarded("Súly", 15 * MIN) { askWeight() } },
                bigButton("Jegyzet", GREY, R.drawable.ic_note) { askNote() },
            ),
        ))



        // confirmation banner floating at the bottom, with undo
        bannerText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val shift = TextView(this).apply {
            text = "−5 PERC"
            textSize = 14f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#F48FB1"))
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { shiftLast() }
        }
        val undo = TextView(this).apply {
            text = "VISSZAVONÁS"
            textSize = 14f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#F48FB1"))
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { undoLast() }
        }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2B2830"))
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
            addView(bannerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(undo)
            addView(shift)
        }

        return FrameLayout(this).apply {
            addView(ScrollView(this@MainActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(root)
            })
            addView(banner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
            ).apply { setMargins(dp(16), 0, dp(16), dp(24)) })
        }
    }

    /** What "−5 perc" moves: the last point entry, or the start of the running feed. */
    private var shiftTarget: Uri? = null
    private var shiftRunning = false
    private var shiftTitle = ""

    private fun showBanner(text: String, canUndo: Boolean, canShift: Boolean = false) {
        bannerText.text = text
        (banner.getChildAt(1)).visibility = if (canUndo) View.VISIBLE else View.GONE
        (banner.getChildAt(2)).visibility = if (canShift) View.VISIBLE else View.GONE
        if (!canShift) { shiftTarget = null; shiftRunning = false }
        handler.removeCallbacks(hideBanner)
        banner.animate().cancel()
        banner.alpha = 0f
        banner.visibility = View.VISIBLE
        banner.animate().alpha(1f).setDuration(150)
        handler.postDelayed(hideBanner, 4_000)
    }

    private fun haptic(ok: Boolean) {
        val type = when {
            Build.VERSION.SDK_INT < 30 -> HapticFeedbackConstants.VIRTUAL_KEY
            ok -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.REJECT
        }
        window.decorView.performHapticFeedback(type)
    }

    private fun shiftLast() {
        val delta = -5 * MIN
        val newStart = when {
            shiftRunning -> Feeding.shiftRunningStart(this, delta)
            shiftTarget != null -> Feeding.shiftEntry(this, shiftTarget!!, delta)
            else -> null
        }
        if (newStart == null) {
            haptic(false)
            return
        }
        haptic(true)
        val text = if (shiftRunning) "$shiftTitle elindítva · ${hhmm(newStart)}" else "$shiftTitle · ${hhmm(newStart)}"
        val keepRunning = shiftRunning
        val keepTarget = shiftTarget
        showBanner(text, canUndo = !keepRunning, canShift = true)
        shiftRunning = keepRunning
        shiftTarget = keepTarget
        FeedWidget.refreshAll(this)
        refresh()
    }

    private fun undoLast() {
        lastUri?.let { Feeding.delete(this, it) }
        lastUri = null
        showBanner("Visszavonva", canUndo = false)
        FeedWidget.refreshAll(this)
        refresh()
    }

    /**
     * Asks before logging the same thing again within [windowMs] (or again today),
     * to catch double taps and both parents logging the same nappy.
     */
    private fun guarded(
        kind: String,
        windowMs: Long = 0,
        sinceMidnight: Boolean = false,
        contains: Boolean = false,
        action: () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val since = if (sinceMidnight) {
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else now - windowMs
        val previous = Feeding.entriesSince(this, since).lastOrNull {
            if (contains) it.title.contains(kind, ignoreCase = true) else it.title.startsWith(kind, ignoreCase = true)
        }
        if (previous == null) {
            action()
            return
        }
        val label = previous.title.substringBefore(" (")
        AlertDialog.Builder(this)
            .setTitle("Már rögzítve")
            .setMessage("$label ${hhmm(previous.start)}-kor már be lett írva. Mégis hozzáadod?")
            .setPositiveButton("Igen, újat") { _, _ -> action() }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun refresh() {
        val running = Feeding.running(this)
        var left = c(R.color.neutral)
        var right = c(R.color.neutral)
        var leftText = c(R.color.on_neutral)
        var rightText = c(R.color.on_neutral)
        setRunningLook(running?.side)
        if (running != null) {
            status.text = "kezdés ${hhmm(running.start)}"
            showSince(running.start)
            if (running.side == Side.LEFT) { left = ACTIVE; leftText = Color.WHITE } else { right = ACTIVE; rightText = Color.WHITE }
        } else {
            val last = Feeding.lastFeed(this)
            if (last == null) {
                status.text = getString(R.string.no_entries)
                timer.stop()
                timer.visibility = View.GONE
            } else {
                val next = last.side?.other()
                status.text = if (next == null) {
                    "utolsó szopi vége ${hhmm(last.end)} · azóta eltelt"
                } else {
                    "utolsó: ${next.other().label}, vége ${hhmm(last.end)} · következő: ${next.label} · azóta eltelt"
                }
                showSince(last.end)
                if (next == Side.LEFT) left = c(R.color.suggest) else if (next == Side.RIGHT) right = c(R.color.suggest)
            }
        }
        styleButton(btnLeft, left, leftText)
        styleButton(btnRight, right, rightText)

        val sum = Summary.load(this)
        chips.removeAllViews()
        chip("${sum.feeds} szopi · ${sum.feedMinutes} perc")
        chip("pisis ${sum.wet}")
        chip("kakis ${sum.dirty}")
        if (sum.formulaMl > 0) chip("táp ${sum.formulaMl} ml")
        if (sum.breastMilkMl > 0) chip("anyatej ${sum.breastMilkMl} ml")
        sum.lastWeight?.let { chip("$it g") }

        btnVitamin.text = if (sum.vitaminToday) "D-vitamin ma kész" else "D-vitamin"
        btnVitamin.background = rounded(if (sum.vitaminToday) DONE else VITAMIN)

        lastIbuprofen = sum.lastIbuprofen
        updateIbuprofen()

        btnMomPee.text = if (sum.momPeeCount == 0) "Pisi (ml)" else "Pisi · ma ${sum.momPeeMl} ml\n${sum.momPeeCount} alkalom"


        WearSync.publish(this)
        FeedAlert.schedule(this)
        Reminder.schedule(this)
    }

    /** Running: rose badge with pulsing dot, rose timer, outlined card. Pause: grey badge, muted timer. */
    private fun setRunningLook(side: Side?) {
        val running = side != null
        badge.text = if (running) "SZOPI FUT · ${side!!.label.uppercase()}" else "SZÜNET"
        badge.setTextColor(if (running) Color.WHITE else c(R.color.muted))
        (badge.parent as View).background = GradientDrawable().apply {
            setColor(if (running) ACTIVE else c(R.color.chip))
            cornerRadius = dp(14).toFloat()
        }
        (badgeDot.background as GradientDrawable).setColor(if (running) Color.WHITE else c(R.color.muted))
        if (running) { if (!pulse.isStarted) pulse.start() } else { pulse.cancel(); badgeDot.alpha = 1f }
        timer.setTextColor(if (running) ACTIVE else c(R.color.muted))
        feedCard.background = GradientDrawable().apply {
            setColor(c(R.color.card))
            cornerRadius = dp(24).toFloat()
            if (running) setStroke(dp(2), ACTIVE)
        }
    }

    private fun showMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Áttekintés, grafikonok")
        menu.menu.add(0, 2, 1, "Ibuprofen időköz: ${Reminder.hours(this)} óra")
        menu.menu.add(0, 3, 2, "Ibuprofen értesítés").apply {
            isCheckable = true
            isChecked = Reminder.notifyOn(this@MainActivity)
        }
        val fa = FeedAlert.hours(this)
        menu.menu.add(0, 6, 3, if (fa == 0) "Etetés emlékeztető: ki" else "Etetés emlékeztető: $fa óra után")
        menu.menu.add(0, 4, 3, "Naptár beállítása")
        menu.menu.add(0, 5, 4, "Naptár frissítése most")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, DashboardActivity::class.java))
                2 -> pickReminder()
                3 -> {
                    val on = !Reminder.notifyOn(this)
                    Reminder.setNotify(this, on)
                    if (on && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                    }
                    showBanner(if (on) "Ibuprofen értesítés bekapcsolva" else "Ibuprofen értesítés kikapcsolva", canUndo = false)
                }
                4 -> startActivity(Intent(this, SettingsActivity::class.java))
                5 -> forceSync(null)
                6 -> pickFeedAlert()
            }
            true
        }
        menu.show()
    }

    /** Sync with Google now and report when it is done (or give up after 30 s). */
    private fun forceSync(button: View?) {
        val account = Feeding.requestSync(this)
        if (account == null) {
            showBanner("Ez a naptár nem szinkronizál (helyi naptár?)", canUndo = false)
            return
        }
        showBanner("Frissítés…", canUndo = false)
        val spin = button?.animate()?.rotationBy(360f * 30)?.setDuration(30_000)?.setInterpolator(null)
        val started = SystemClock.elapsedRealtime()
        var sawActive = false
        val poll = object : Runnable {
            override fun run() {
                val active = Feeding.isSyncing(account)
                if (active) sawActive = true
                val elapsed = SystemClock.elapsedRealtime() - started
                val done = (sawActive && !active) || (!active && elapsed > 3_000)
                if (done || elapsed > 30_000) {
                    spin?.cancel()
                    button?.rotation = 0f
                    refresh()
                    haptic(done)
                    showBanner(
                        if (done) "Naptár frissítve · ${hhmm(System.currentTimeMillis())}"
                        else "A frissítés még tart – nincs net, vagy az Android késlelteti",
                        canUndo = false,
                    )
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.postDelayed(poll, 500)
    }

    private fun iconButton(icon: Int, description: String, onClick: (View) -> Unit) =
        android.widget.ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(c(R.color.text))
            contentDescription = description
            background = GradientDrawable().apply {
                setColor(c(R.color.chip))
                shape = GradientDrawable.OVAL
            }
            setOnClickListener { onClick(it) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }
        }

    /** Keeps a view at its natural width inside a vertical card. */
    private fun wrap(v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(6))
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Countdown to the next dose, based on the interval you set. */
    private fun updateIbuprofen() {
        val last = lastIbuprofen
        if (last == null) {
            btnIbuprofen.text = "Ibuprofen"
            btnIbuprofen.background = rounded(PILL)
            return
        }
        val next = last + Reminder.hours(this) * 3_600_000L
        val left = next - System.currentTimeMillis()
        if (left > 0) {
            val mins = (left + 59_999) / 60_000 // round up, so it never shows 0 p early
            btnIbuprofen.text = "Ibuprofen · ${hhmm(next)}\nmég ${mins / 60} ó ${mins % 60} p"
            btnIbuprofen.background = rounded(PILL)
        } else {
            val late = -left / 60_000
            btnIbuprofen.text = "Ibuprofen · ${hhmm(next)}\nesedékes (${late / 60} ó ${late % 60} p)"
            btnIbuprofen.background = rounded(DUE)
        }
    }

    private fun showSince(wallClockStart: Long) {
        timer.base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wallClockStart)
        timer.visibility = View.VISIBLE
        timer.start()
    }

    // ---- actions ----

    private fun afterFeedChange(logged: Uri?) {
        haptic(true)
        val running = Feeding.running(this)
        when {
            logged != null -> {
                lastUri = logged
                showBanner(if (running != null) "Szopi mentve, ${running.side.label} indul" else "Szopi mentve", canUndo = true)
            }
            running != null -> {
                showBanner("${running.side.label} elindítva · ${hhmm(running.start)}", canUndo = false, canShift = true)
                shiftRunning = true
                shiftTitle = running.side.label
            }
            else -> showBanner("Leállítva", canUndo = false)
        }
        FeedWidget.refreshAll(this)
        refresh()
    }

    private fun log(title: String) {
        remember(Feeding.logNow(this, title), title)
    }

    private fun remember(uri: Uri?, title: String, extra: String = "") {
        if (uri == null) {
            haptic(false)
            showBanner("Nem sikerült menteni – naptár beállítva?", canUndo = false)
            return
        }
        haptic(true)
        lastUri = uri
        showBanner("$title hozzáadva · ${hhmm(System.currentTimeMillis())}$extra", canUndo = true, canShift = true)
        shiftTarget = uri
        shiftTitle = title
        refresh()
    }

    private fun pickReminder() {
        val options = arrayOf("4 óra", "6 óra", "8 óra")
        val hours = intArrayOf(4, 6, 8)
        AlertDialog.Builder(this)
            .setTitle("Ibuprofen időköz")
            .setItems(options) { _, which ->
                Reminder.setHours(this, hours[which])
                showBanner("Ibuprofen időköz: ${hours[which]} óra", canUndo = false)
                refresh()
            }
            .show()
    }

    private fun pickFeedAlert() {
        val options = arrayOf("Ki", "2 óra", "3 óra", "4 óra")
        val hours = intArrayOf(0, 2, 3, 4)
        AlertDialog.Builder(this)
            .setTitle("Értesítés, ha ennyi ideje nem volt szopi")
            .setItems(options) { _, which ->
                FeedAlert.setHours(this, hours[which])
                if (hours[which] > 0 && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
                }
                showBanner(
                    if (hours[which] == 0) "Etetés emlékeztető kikapcsolva" else "Értesítés ${hours[which]} óra szünet után",
                    canUndo = false,
                )
            }
            .show()
    }

    private fun askMomPee() {
        val ml = numberField("ml")
        AlertDialog.Builder(this)
            .setTitle("Anya pisi")
            .setView(padded(ml))
            .setPositiveButton("Mentés") { _, _ ->
                val v = ml.text.toString().trim()
                if (v.isNotEmpty()) log("Anya pisi $v ml")
            }
            .setNegativeButton("Mégse", null)
            .show()
        ml.requestFocus()
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
                val grams = g.toIntOrNull()
                if (grams != null) {
                    remember(Feeding.logNow(this, "Súly $grams g"), "Súly $grams g",
                        " · ${Baby.percentileText(this, grams)} percentilis")
                }
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

    private fun c(id: Int) = getColor(id)

    private fun card(title: String, vararg content: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(c(R.color.card))
            cornerRadius = dp(24).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(this@MainActivity).apply {
            text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c(R.color.muted))
            setPadding(dp(4), 0, 0, dp(6))
        })
        content.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
    }

    private fun chip(text: String) {
        chips.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(c(R.color.text))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(c(R.color.chip))
                cornerRadius = dp(16).toFloat()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) })
    }

    private fun link(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(c(R.color.muted))
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { onClick() }
    }

    private fun numberField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false // otherwise a wrapped label shifts its button down
        buttons.forEach {
            addView(it, LinearLayout.LayoutParams(0, dp(88), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
    }

    private fun bigButton(
        label: String,
        color: Int,
        icon: Int,
        textColor: Int = Color.WHITE,
        onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null // flat, no elevation shadow
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // shrink long labels (e.g. "Mindkettő") instead of wrapping them
        setAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, TypedValue.COMPLEX_UNIT_SP)
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(10), dp(6), dp(8))
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
        compoundDrawablePadding = dp(2)
        styleButton(this, color, textColor)
        setOnClickListener { onClick() }
    }

    private fun styleButton(b: Button, color: Int, textColor: Int) {
        b.background = rounded(color)
        b.setTextColor(textColor)
        b.compoundDrawableTintList = ColorStateList.valueOf(textColor)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
    }

    private fun gradient(from: Int, to: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(from, to),
    ).apply { cornerRadius = dp(20).toFloat() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    private companion object {
        val ACTIVE = Color.parseColor("#D6336C")
        val STOP = Color.parseColor("#4A4650")
        val PEE = Color.parseColor("#F2C14E")
        val PEE_TEXT = Color.parseColor("#3A2E05")
        val PEE_DARK = Color.parseColor("#C9972E")
        val POO = Color.parseColor("#8B5E3C")
        val FORMULA = Color.parseColor("#3D7EA6")
        val MILK = Color.parseColor("#C2577D")
        val PILL = Color.parseColor("#6B5CA5")
        val MOM = Color.parseColor("#3E8C82")
        val VITAMIN = Color.parseColor("#E0892E")
        val GREY = Color.parseColor("#66707D")
        val DONE = Color.parseColor("#2E7D32")
        val DUE = Color.parseColor("#C62828")
        const val MIN = 60_000L
    }
}
