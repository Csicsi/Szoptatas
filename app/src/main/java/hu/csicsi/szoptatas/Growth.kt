package hu.csicsi.szoptatas

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * WHO Child Growth Standards (2006), weight-for-age, boys.
 * LMS values: weekly 0–13 weeks, then monthly up to 24 months (source tables
 * wfa_boys_0_13_zscores / wfa_boys_0_5_zscores). Interpolated linearly by age in days.
 */
object Growth {
    private const val DAYS_PER_MONTH = 30.4375

    // ageDays, L, M (kg), S
    private val TABLE: List<DoubleArray> = buildList {
        val weeks = arrayOf(
            doubleArrayOf(0.3487, 3.3464, 0.14602), doubleArrayOf(0.2776, 3.4879, 0.14483),
            doubleArrayOf(0.2581, 3.7529, 0.14142), doubleArrayOf(0.2442, 4.0603, 0.13807),
            doubleArrayOf(0.2331, 4.3671, 0.13497), doubleArrayOf(0.2237, 4.6590, 0.13215),
            doubleArrayOf(0.2155, 4.9303, 0.12960), doubleArrayOf(0.2081, 5.1817, 0.12729),
            doubleArrayOf(0.2014, 5.4149, 0.12520), doubleArrayOf(0.1952, 5.6319, 0.12330),
            doubleArrayOf(0.1894, 5.8346, 0.12157), doubleArrayOf(0.1840, 6.0242, 0.12001),
            doubleArrayOf(0.1789, 6.2019, 0.11860), doubleArrayOf(0.1740, 6.3690, 0.11732),
        )
        weeks.forEachIndexed { w, lms -> add(doubleArrayOf(w * 7.0, lms[0], lms[1], lms[2])) }
        val months = arrayOf(
            doubleArrayOf(4.0, 0.1553, 7.0023, 0.11316), doubleArrayOf(5.0, 0.1395, 7.5105, 0.11080),
            doubleArrayOf(6.0, 0.1257, 7.9340, 0.10958), doubleArrayOf(7.0, 0.1134, 8.2970, 0.10902),
            doubleArrayOf(8.0, 0.1021, 8.6151, 0.10882), doubleArrayOf(9.0, 0.0917, 8.9014, 0.10881),
            doubleArrayOf(10.0, 0.0820, 9.1649, 0.10891), doubleArrayOf(11.0, 0.0730, 9.4122, 0.10906),
            doubleArrayOf(12.0, 0.0644, 9.6479, 0.10925), doubleArrayOf(15.0, 0.0413, 10.3108, 0.11007),
            doubleArrayOf(18.0, 0.0211, 10.9385, 0.11119), doubleArrayOf(21.0, 0.0029, 11.5486, 0.11261),
            doubleArrayOf(24.0, -0.0137, 12.1515, 0.11426),
        )
        months.forEach { add(doubleArrayOf(it[0] * DAYS_PER_MONTH, it[1], it[2], it[3])) }
    }

    val maxAgeDays: Double get() = TABLE.last()[0]

    private fun lms(ageDays: Double): DoubleArray {
        val a = ageDays.coerceIn(0.0, maxAgeDays)
        val hi = TABLE.indexOfFirst { it[0] >= a }.coerceAtLeast(1)
        val p0 = TABLE[hi - 1]
        val p1 = TABLE[hi]
        val t = if (p1[0] == p0[0]) 0.0 else (a - p0[0]) / (p1[0] - p0[0])
        return DoubleArray(3) { i -> p0[i + 1] + t * (p1[i + 1] - p0[i + 1]) }
    }

    /** Weight (kg) at the given z-score. */
    fun weightAt(ageDays: Double, z: Double): Double {
        val (l, m, s) = lms(ageDays).let { Triple(it[0], it[1], it[2]) }
        return if (abs(l) < 1e-9) m * exp(s * z) else m * (1 + l * s * z).pow(1 / l)
    }

    fun zScore(ageDays: Double, kg: Double): Double {
        val (l, m, s) = lms(ageDays).let { Triple(it[0], it[1], it[2]) }
        return if (abs(l) < 1e-9) ln(kg / m) / s else ((kg / m).pow(l) - 1) / (l * s)
    }

    fun percentile(ageDays: Double, kg: Double): Double = normalCdf(zScore(ageDays, kg)) * 100

    /** z for the usual chart lines P3, P15, P50, P85, P97. */
    val CURVES = listOf(3 to -1.881, 15 to -1.036, 50 to 0.0, 85 to 1.036, 97 to 1.881)

    private fun normalCdf(z: Double): Double = 0.5 * (1 + erf(z / sqrt(2.0)))

    // Abramowitz–Stegun 7.1.26, error < 1.5e-7
    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1 else 1
        val ax = abs(x)
        val t = 1 / (1 + 0.3275911 * ax)
        val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }
}
