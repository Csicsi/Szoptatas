package hu.csicsi.szoptatas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Stacked bar chart, one bar per day; total printed above each bar. */
class BarChartView(ctx: Context) : View(ctx) {
    var labels: List<String> = emptyList()
    var series: List<Pair<Int, List<Float>>> = emptyList()
    var unit: String = ""
    /** Text colour per series; when set, each segment shows its own number. */
    var segmentText: List<Int>? = null

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = ctx.dpf(11f)
        color = ctx.getColor(R.color.muted)
    }
    private val value = Paint(text).apply { color = ctx.getColor(R.color.text) }
    private val inside = Paint(text).apply {
        textSize = ctx.dpf(11f)
        isFakeBoldText = true
    }
    private val rect = RectF()

    fun set(
        labels: List<String>,
        series: List<Pair<Int, List<Float>>>,
        unit: String = "",
        segmentText: List<Int>? = null,
    ) {
        this.labels = labels
        this.series = series
        this.unit = unit
        this.segmentText = segmentText
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpf(160f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val n = labels.size
        if (n == 0) return
        val totals = (0 until n).map { i -> series.sumOf { it.second.getOrElse(i) { 0f }.toDouble() }.toFloat() }
        val maxV = max(1f, totals.maxOrNull() ?: 1f)
        val top = context.dpf(18f)
        val bottom = height - context.dpf(18f)
        val slot = width.toFloat() / n
        val bw = slot * 0.62f
        val r = context.dpf(5f)
        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            var y = bottom
            val showSegments = segmentText != null && n <= 14
            series.forEachIndexed { si, (color, values) ->
                val v = values.getOrElse(i) { 0f }
                if (v <= 0f) return@forEachIndexed
                val h = (bottom - top) * v / maxV
                bar.color = color
                rect.set(cx - bw / 2, y - h, cx + bw / 2, y)
                c.drawRoundRect(rect, r, r, bar)
                if (showSegments && h >= context.dpf(13f)) {
                    inside.color = segmentText!![si]
                    c.drawText(trim(v), cx, y - h / 2 + inside.textSize / 3, inside)
                }
                y -= h
            }
            if (totals[i] > 0) {
                val t = if (totals[i] >= 100) totals[i].toInt().toString() else trim(totals[i])
                c.drawText(t, cx, y - context.dpf(4f), value)
            }
            if (n <= 14 || i % 2 == 0) c.drawText(labels[i], cx, height - context.dpf(4f), text)
        }
    }

    private fun trim(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
}

/** Weight against WHO percentile curves; x = age in days. */
class WeightChartView(ctx: Context) : View(ctx) {
    private var points: List<Pair<Double, Double>> = emptyList() // ageDays, kg
    private var maxDay = 28.0

    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(1.2f)
        color = ctx.getColor(R.color.muted)
        alpha = 110
    }
    private val median = Paint(curve).apply {
        strokeWidth = ctx.dpf(1.6f)
        pathEffect = DashPathEffect(floatArrayOf(ctx.dpf(6f), ctx.dpf(4f)), 0f)
        alpha = 170
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(2.5f)
        color = Color.parseColor("#D6336C")
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D6336C") }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dpf(10f)
        color = ctx.getColor(R.color.muted)
    }

    fun set(points: List<Pair<Double, Double>>) {
        this.points = points
        val last = points.maxOfOrNull { it.first } ?: 0.0
        maxDay = max(28.0, ceil((last + 7) / 7) * 7).coerceAtMost(Growth.maxAgeDays)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpf(220f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val left = context.dpf(30f)
        val right = width - context.dpf(30f)
        val top = context.dpf(8f)
        val bottom = height - context.dpf(18f)
        val minKg = (Growth.weightAt(0.0, -2.2) - 0.2).coerceAtLeast(0.0)
        val maxKg = Growth.weightAt(maxDay, 2.2) + 0.2
        fun x(d: Double) = (left + (right - left) * d / maxDay).toFloat()
        fun y(kg: Double) = (bottom - (bottom - top) * (kg - minKg) / (maxKg - minKg)).toFloat()

        // kg grid labels
        var kg = ceil(minKg * 2) / 2
        while (kg <= maxKg) {
            c.drawText("%.1f".format(kg), context.dpf(2f), y(kg) + context.dpf(3f), label)
            kg += if (maxKg - minKg > 4) 1.0 else 0.5
        }
        // week labels
        var d = 0.0
        val step = if (maxDay > 120) 28.0 else 7.0
        while (d <= maxDay) {
            val w = (d / 7).toInt()
            c.drawText("${w}. hét", x(d) - context.dpf(10f), height - context.dpf(4f), label)
            d += step
        }
        // WHO curves
        Growth.CURVES.forEach { (p, z) ->
            val path = Path()
            var day = 0.0
            while (day <= maxDay) {
                val px = x(day)
                val py = y(Growth.weightAt(day, z))
                if (day == 0.0) path.moveTo(px, py) else path.lineTo(px, py)
                day += 0.5
            }
            c.drawPath(path, if (p == 50) median else curve)
            c.drawText("P$p", right + context.dpf(3f), y(Growth.weightAt(maxDay, z)) + context.dpf(3f), label)
        }
        // measurements
        if (points.isNotEmpty()) {
            val path = Path()
            points.sortedBy { it.first }.forEachIndexed { i, (day2, w) ->
                if (i == 0) path.moveTo(x(day2), y(w)) else path.lineTo(x(day2), y(w))
            }
            c.drawPath(path, line)
            points.forEach { (day2, w) -> c.drawCircle(x(day2), y(w), context.dpf(4f), dot) }
        }
    }
}

/**
 * One row per day (newest on top), 0–24 h across. Breastfeeds are bars coloured by side,
 * bottles are dots, so clusters and gaps are visible at a glance.
 */
class TimelineView(ctx: Context) : View(ctx) {
    private var days: List<TimelineDay> = emptyList()
    var selected: Long = -1
    var onDayClick: ((Long) -> Unit)? = null

    private val rowH = ctx.dpf(24f)
    private val labelW = ctx.dpf(34f)
    private val top = ctx.dpf(16f)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.chip) }
    private val grid = Paint().apply {
        color = ctx.getColor(R.color.muted)
        alpha = 60
        strokeWidth = ctx.dpf(1f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dpf(10f)
        color = ctx.getColor(R.color.muted)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(1.5f)
        color = ctx.getColor(R.color.card)
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6336C")
        alpha = 40
    }
    private val longGap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0892E")
        strokeWidth = ctx.dpf(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val fmt = java.text.SimpleDateFormat("d. EEE", java.util.Locale("hu"))

    fun set(days: List<TimelineDay>) {
        this.days = days
        requestLayout()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (top + days.size * rowH + context.dpf(4f)).toInt())
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action == android.view.MotionEvent.ACTION_UP) {
            val i = ((e.y - top) / rowH).toInt()
            days.getOrNull(i)?.let { onDayClick?.invoke(it.dayStart) }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(c: Canvas) {
        val left = labelW
        val right = width - context.dpf(4f)
        val dayMs = 24f * 60 * 60 * 1000
        fun x(dayStart: Long, t: Long) = left + (right - left) * ((t - dayStart).coerceIn(0, dayMs.toLong()) / dayMs)

        // hour grid
        for (h in 0..24 step 3) {
            val gx = left + (right - left) * h / 24f
            c.drawLine(gx, top - context.dpf(2f), gx, top + days.size * rowH, grid)
            if (h % 6 == 0) c.drawText("$h", gx - context.dpf(3f), top - context.dpf(5f), label)
        }

        days.forEachIndexed { i, d ->
            val cy = top + i * rowH + rowH / 2
            if (d.dayStart == selected) {
                rect.set(0f, cy - rowH / 2, right, cy + rowH / 2)
                c.drawRoundRect(rect, context.dpf(6f), context.dpf(6f), highlight)
            }
            c.drawText(fmt.format(java.util.Date(d.dayStart)), 0f, cy + context.dpf(3.5f), label)
            rect.set(left, cy - context.dpf(3f), right, cy + context.dpf(3f))
            c.drawRoundRect(rect, context.dpf(3f), context.dpf(3f), track)

            // long breaks (2.5 h+) as an orange line, to spot the longer naps
            val sorted = d.items.sortedBy { it.start }
            sorted.zipWithNext { a, b ->
                if (b.start - a.end >= 150 * 60_000L) {
                    c.drawLine(x(d.dayStart, a.end), cy, x(d.dayStart, b.start), cy, longGap)
                }
            }
            d.items.filter { it.bottle == null }.forEach { f ->
                bar.color = when (f.side) {
                    Side.LEFT -> LEFT
                    Side.RIGHT -> RIGHT
                    null -> UNKNOWN
                }
                if (f.running) bar.alpha = 170
                val x0 = x(d.dayStart, f.start)
                val x1 = maxOf(x(d.dayStart, f.end), x0 + context.dpf(3f))
                rect.set(x0, cy - context.dpf(7f), x1, cy + context.dpf(7f))
                c.drawRoundRect(rect, context.dpf(4f), context.dpf(4f), bar)
                bar.alpha = 255
            }
            d.items.filter { it.bottle != null }.forEach { b ->
                bar.color = if (b.bottle!!.startsWith("táp")) FORMULA else MILK
                val bx = x(d.dayStart, b.start)
                c.drawCircle(bx, cy, context.dpf(5f), bar)
                c.drawCircle(bx, cy, context.dpf(5f), ring)
            }
        }
    }

    companion object {
        val LEFT = Color.parseColor("#D6336C")
        val RIGHT = Color.parseColor("#8E4FB8")
        val UNKNOWN = Color.parseColor("#B07A93")
        val FORMULA = Color.parseColor("#3D7EA6")
        val MILK = Color.parseColor("#E8A0BC")
    }
}
