package hu.csicsi.szoptatas

import android.app.Activity
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** History: daily charts, feeding gaps and weight against the WHO curves. */
class DashboardActivity : Activity() {

    private lateinit var content: LinearLayout
    private val rangeButtons = mutableListOf<TextView>()
    private var days = 7
    private var selectedDay = Stats.startOfDay(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "Áttekintés"
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.text))
            setPadding(dp(4), 0, 0, dp(8))
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
            listOf(7, 14, 30).forEach { n ->
                val b = TextView(this@DashboardActivity).apply {
                    text = "$n nap"
                    textSize = 14f
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { days = n; render() }
                }
                rangeButtons += b
                addView(b, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) })
            }
        })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        }.also { it.padForSystemBars() })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        rangeButtons.forEachIndexed { i, b ->
            val on = listOf(7, 14, 30)[i] == days
            b.setTextColor(if (on) Color.WHITE else getColor(R.color.text))
            b.background = GradientDrawable().apply {
                setColor(if (on) ACCENT else getColor(R.color.chip))
                cornerRadius = dp(16).toFloat()
            }
        }
        content.removeAllViews()

        val stats = Stats.lastDays(this, days)
        val fmt = SimpleDateFormat("d.", Locale.getDefault())
        val labels = stats.map { fmt.format(Date(it.dayStart)) }

        weightCard()

        // daily timeline: when feeds happened, how long, how close together
        val timelineDays = minOf(days, 14)
        content.addView(card(
            "Etetések napi idővonala",
            TimelineView(this).apply {
                selected = selectedDay
                set(Stats.timeline(this@DashboardActivity, timelineDays))
                onDayClick = { selectedDay = it; render() }
            },
            note("Koppints egy napra a részletekhez."),
            legend(
                TimelineView.LEFT to "szopi bal",
                TimelineView.RIGHT to "szopi jobb",
                TimelineView.UNKNOWN to "szopi (oldal nélkül)",
            ),
            legend(TimelineView.FORMULA to "tápszer (üveg)", TimelineView.MILK to "anyatej (üveg)"),
            legend(LONG_GAP to "szünet 2,5 ó felett"),
        ))

        dayDetailCard()

        val gaps = Stats.feedGaps(this)
        val gapText = if (gaps.isEmpty()) "Az elmúlt 24 órában nincs elég adat." else {
            "Elmúlt 24 óra: ${gaps.size + 1} etetés (szopi + üveg) · átlagos szünet ${hm(gaps.average().roundToInt().toLong())} · leghosszabb ${hm(gaps.max())}"
        }
        content.addView(card(
            "Szopi naponta",
            BarChartView(this).apply { set(labels, listOf(ACCENT to stats.map { it.feeds.toFloat() })) },
            note(stats.filter { it.feedMinutes > 0 }.let { d ->
                if (d.isEmpty()) "Időtartam: nincs -tól-ig adat."
                else "Átlagosan ${d.map { it.feedMinutes }.average().roundToInt()} perc szopi naponta"
            }),
            note(gapText),
        ))

        content.addView(card(
            "Pelus naponta",
            BarChartView(this).apply {
                set(
                    labels,
                    listOf(
                        PEE to stats.map { it.wet.toFloat() },
                        POO to stats.map { it.dirty.toFloat() },
                    ),
                    segmentText = listOf(Color.parseColor("#3A2E05"), Color.WHITE),
                )
            },
            legend(PEE to "pisis", POO to "kakis"),
            note("Ma: ${stats.last().wet} pisis · ${stats.last().dirty} kakis"),
        ))

        if (stats.any { it.formulaMl + it.breastMilkMl > 0 }) {
            content.addView(card(
                "Táp és anyatej (ml)",
                BarChartView(this).apply {
                    set(labels, listOf(
                        FORMULA to stats.map { it.formulaMl.toFloat() },
                        MILK to stats.map { it.breastMilkMl.toFloat() },
                    ))
                },
                legend(FORMULA to "tápszer", MILK to "anyatej"),
            ))
        }

        if (stats.any { it.momPeeMl > 0 }) {
            content.addView(card(
                "Anya pisi (ml)",
                BarChartView(this).apply { set(labels, listOf(MOM to stats.map { it.momPeeMl.toFloat() })) },
            ))
        }
    }

    /** One day as a list: each feed with its minutes, and the break before the next one. */
    private fun dayDetailCard() {
        val today = Stats.startOfDay(System.currentTimeMillis())
        val (before, items) = Stats.dayDetail(this, selectedDay)
        val dayName = SimpleDateFormat("MMM d. EEEE", Locale("hu")).format(Date(selectedDay)) +
            if (selectedDay == today) " (ma)" else ""

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(navButton("‹") { selectedDay -= DAY; render() })
            addView(TextView(this@DashboardActivity).apply {
                text = dayName
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.text))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(navButton("›") { if (selectedDay < today) { selectedDay += DAY; render() } }.apply {
                alpha = if (selectedDay < today) 1f else 0.3f
            })
        }

        val feeds = items.filter { it.bottle == null }
        val known = feeds.filter { it.hasLength }
        val totalMin = known.sumOf { (it.end - it.start) / 60_000 }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        val gapsMin = mutableListOf<Long>()
        var prev = before
        items.forEach { item ->
            prev?.let { p ->
                val gap = (item.start - p.end) / 60_000
                if (gap > 0) {
                    gapsMin += gap
                    list.addView(gapRow(gap))
                }
            }
            list.addView(itemRow(item))
            prev = item
        }
        if (items.isEmpty()) list.addView(note("Erre a napra nincs etetés rögzítve."))

        val head = buildList {
            add("${feeds.size} szopi" + if (known.isNotEmpty()) " · ${hm(totalMin)} összesen · átlag ${hm(totalMin / known.size)}" else "")
            val bottles = items.count { it.bottle != null }
            if (bottles > 0) add("$bottles üveg")
            val longGaps = gapsMin.count { it >= LONG_GAP_MIN }
            if (longGaps > 0) add("$longGaps hosszú szünet (2,5 ó+) · összesen ${hm(gapsMin.filter { it >= LONG_GAP_MIN }.sum())}")
            if (gapsMin.isNotEmpty()) add("átlag szünet ${hm(gapsMin.average().roundToInt().toLong())} · leghosszabb ${hm(gapsMin.max())}")
        }.joinToString("\n")

        content.addView(card("Nap részletei", nav, note(head), list))
    }

    private fun itemRow(item: TimelineItem): View {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val mins = (item.end - item.start) / 60_000
        val color = when {
            item.bottle != null -> if (item.bottle.startsWith("táp")) TimelineView.FORMULA else TimelineView.MILK
            item.side == Side.LEFT -> TimelineView.LEFT
            item.side == Side.RIGHT -> TimelineView.RIGHT
            else -> TimelineView.UNKNOWN
        }
        val time = when {
            item.bottle != null || !item.hasLength -> fmt.format(Date(item.start))
            item.running -> "${fmt.format(Date(item.start))}–most"
            else -> "${fmt.format(Date(item.start))}–${fmt.format(Date(item.end))}"
        }
        val what = when {
            item.bottle != null -> "${item.bottle} (üveg)"
            !item.hasLength -> "${item.side?.label ?: "szopi"} · ? p"
            else -> "${item.side?.label ?: "szopi"} · $mins p" + if (item.running) " (fut)" else ""
        }
        // bar length: 60 min = full width, so long feeds stand out
        val frac = if (item.bottle != null || !item.hasLength) 0f else (mins / 60f).coerceIn(0.04f, 1f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@DashboardActivity).apply {
                text = time
                textSize = 14f
                setTextColor(getColor(R.color.text))
            }, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@DashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@DashboardActivity).apply {
                    text = what
                    textSize = 14f
                    setTextColor(getColor(R.color.text))
                })
                if (frac > 0f) {
                    addView(LinearLayout(this@DashboardActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        weightSum = 1f
                        addView(View(this@DashboardActivity).apply {
                            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat() }
                        }, LinearLayout.LayoutParams(0, dp(8), frac))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(3) })
                } else {
                    addView(View(this@DashboardActivity).apply {
                        background = GradientDrawable().apply { setColor(color); shape = GradientDrawable.OVAL }
                    }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { topMargin = dp(3) })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Breaks of 2.5 h+ are marked orange: a rough way to spot the longer naps. */
    private fun gapRow(minutes: Long): View {
        val long = minutes >= LONG_GAP_MIN
        val label = TextView(this).apply {
            text = if (long) "hosszú szünet ${hm(minutes)}" else "szünet ${hm(minutes)}"
            textSize = if (long) 13f else 12f
            if (long) {
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply { setColor(LONG_GAP); cornerRadius = dp(10).toFloat() }
            } else {
                setTextColor(getColor(R.color.muted))
            }
        }
        return LinearLayout(this).apply {
            setPadding(dp(96), dp(2), 0, dp(2))
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun navButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 24f
        gravity = android.view.Gravity.CENTER
        setTextColor(getColor(R.color.text))
        background = GradientDrawable().apply { setColor(getColor(R.color.chip)); shape = GradientDrawable.OVAL }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    private fun weightCard() {
        val birth = birthDate()
        val points = Stats.weights(this, birth - 2 * DAY)
            .map { (it.time - birth).toDouble() / DAY to it.grams / 1000.0 }
            .filter { it.first >= 0 }
        val chart = WeightChartView(this).apply { set(points) }
        val last = points.maxByOrNull { it.first }
        val summary = if (last == null) "Még nincs súly rögzítve." else {
            val p = Growth.percentile(last.first, last.second)
            val pText = when {
                p < 1 -> "<1."
                p > 99 -> ">99."
                else -> "${p.roundToInt()}."
            }
            "Utolsó: ${(last.second * 1000).roundToInt()} g · ${last.first.toInt()}. nap · $pText percentilis"
        }
        val birthText = TextView(this).apply {
            text = "Születés: ${SimpleDateFormat("yyyy.MM.dd.", Locale.getDefault()).format(Date(birth))} (módosítás)"
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            setPadding(dp(4), dp(6), 0, 0)
            setOnClickListener { pickBirthDate() }
        }
        content.addView(card(
            "Súly",
            chart,
            TextView(this).apply {
                text = summary
                textSize = 15f
                setTextColor(getColor(R.color.text))
                setPadding(dp(4), dp(8), 0, 0)
            },
            note("WHO 2006 növekedési görbe, fiúk (P3, P15, P50, P85, P97). " +
                "Az első napokban a súlycsökkenés normális; kérdés esetén a gyerekorvos dönt."),
            birthText,
        ))
    }

    // ---- birth date ----

    private fun birthDate(): Long = Baby.birth(this)

    private fun pickBirthDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = birthDate() }
        DatePickerDialog(this, { _, y, m, d ->
            Baby.setBirth(this, Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
            render()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ---- view helpers ----

    private fun card(title: String, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(getColor(R.color.card))
            cornerRadius = dp(24).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(14))
        addView(TextView(this@DashboardActivity).apply {
            text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.muted))
            setPadding(dp(4), 0, 0, dp(8))
        })
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.muted))
        setPadding(dp(4), dp(6), 0, 0)
    }

    private fun legend(vararg items: Pair<Int, String>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(8), 0, 0)
        items.forEach { (color, name) ->
            addView(View(this@DashboardActivity).apply {
                background = GradientDrawable().apply { setColor(color); cornerRadius = dp(3).toFloat() }
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6); topMargin = dp(3) })
            addView(TextView(this@DashboardActivity).apply {
                text = name
                textSize = 13f
                setTextColor(getColor(R.color.muted))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(16) })
        }
    }

    private fun hm(minutes: Long) = if (minutes >= 60) "${minutes / 60} ó ${minutes % 60} p" else "$minutes p"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        val ACCENT = Color.parseColor("#D6336C")
        val LONG_GAP = Color.parseColor("#E0892E")
        const val LONG_GAP_MIN = 150L
        val PEE = Color.parseColor("#F2C14E")
        val POO = Color.parseColor("#8B5E3C")
        val FORMULA = Color.parseColor("#3D7EA6")
        val MILK = Color.parseColor("#C2577D")
        val MOM = Color.parseColor("#3E8C82")
    }
}
