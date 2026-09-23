package net.rsprox.proxy.dungeon.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A two-sided confidence interval. */
internal data class Interval(val low: Double, val high: Double) {
    val width: Double get() = high - low

    fun format(decimals: Int = 3): String = "[${low.fmt(decimals)}, ${high.fmt(decimals)}]"
}

internal fun Double.fmt(decimals: Int = 3): String =
    when {
        isNaN() -> "NaN"
        isInfinite() -> if (this > 0) "inf" else "-inf"
        else -> "%.${decimals}f".format(this)
    }

internal fun Double.formatP(): String =
    when {
        isNaN() -> "n/a"
        this < 0.0001 -> "<0.0001"
        else -> "%.4f".format(this)
    }

/**
 * Plain numerical routines with no external dependency: the special functions needed for chi-square
 * and Student-t p-values, plus the standard binomial/Poisson intervals.
 */
internal object Statistics {
    /** z for a two-sided 95% interval. */
    const val Z_95: Double = 1.959963984540054

    /** chi-square critical values at 95% for 1 and 2 degrees of freedom. */
    const val CHI2_95_DF1: Double = 3.841458820694124
    const val CHI2_95_DF2: Double = 5.991464547107979

    private val LANCZOS =
        doubleArrayOf(
            0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
            1.5056327351493116e-7,
        )

    fun lnGamma(x: Double): Double {
        if (x < 0.5) return ln(Math.PI / abs(kotlin.math.sin(Math.PI * x))) - lnGamma(1.0 - x)
        val z = x - 1.0
        var a = LANCZOS[0]
        val t = z + 7.5
        for (i in 1 until LANCZOS.size) a += LANCZOS[i] / (z + i)
        return 0.5 * ln(2 * Math.PI) + (z + 0.5) * ln(t) - t + ln(a)
    }

    /** Regularized upper incomplete gamma Q(a, x). */
    fun regularizedGammaQ(a: Double, x: Double): Double {
        if (x <= 0.0) return 1.0
        if (x < a + 1.0) return 1.0 - gammaSeries(a, x)
        return gammaContinuedFraction(a, x)
    }

    private fun gammaSeries(a: Double, x: Double): Double {
        var sum = 1.0 / a
        var term = sum
        var n = a
        repeat(10_000) {
            n += 1.0
            term *= x / n
            sum += term
            if (abs(term) < abs(sum) * 1e-15) return sum * exp(-x + a * ln(x) - lnGamma(a))
        }
        return sum * exp(-x + a * ln(x) - lnGamma(a))
    }

    private fun gammaContinuedFraction(a: Double, x: Double): Double {
        val tiny = 1e-300
        var b = x + 1.0 - a
        var c = 1.0 / tiny
        var d = 1.0 / b
        var h = d
        for (i in 1..10_000) {
            val an = -i * (i - a)
            b += 2.0
            d = an * d + b
            if (abs(d) < tiny) d = tiny
            c = b + an / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val delta = d * c
            h *= delta
            if (abs(delta - 1.0) < 1e-15) break
        }
        return exp(-x + a * ln(x) - lnGamma(a)) * h
    }

    /** Regularized incomplete beta I_x(a, b). */
    fun regularizedBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val front = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1.0 - x))
        return if (x < (a + 1.0) / (a + b + 2.0)) {
            front * betaContinuedFraction(x, a, b) / a
        } else {
            1.0 - front * betaContinuedFraction(1.0 - x, b, a) / b
        }
    }

    private fun betaContinuedFraction(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-300
        var c = 1.0
        var d = 1.0 - (a + b) * x / (a + 1.0)
        if (abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d
        for (m in 1..10_000) {
            val m2 = 2.0 * m
            var aa = m * (b - m) * x / ((a + m2 - 1.0) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1.0))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val delta = d * c
            h *= delta
            if (abs(delta - 1.0) < 1e-15) break
        }
        return h
    }

    /** P(X >= statistic) for X ~ chi-square(df). */
    fun chiSquarePValue(statistic: Double, df: Int): Double {
        if (df <= 0 || statistic.isNaN()) return Double.NaN
        if (statistic <= 0.0) return 1.0
        return regularizedGammaQ(df / 2.0, statistic / 2.0)
    }

    /** The chi-square(df) quantile at [probability], by bisection on [chiSquarePValue]. */
    fun chiSquareQuantile(probability: Double, df: Int): Double {
        var low = 0.0
        var high = max(1.0, df.toDouble())
        while (1.0 - chiSquarePValue(high, df) < probability) high *= 2.0
        repeat(200) {
            val mid = (low + high) / 2.0
            if (1.0 - chiSquarePValue(mid, df) < probability) low = mid else high = mid
        }
        return (low + high) / 2.0
    }

    /** Two-sided p-value of a Student-t statistic. */
    fun studentTPValue(t: Double, df: Int): Double {
        if (df <= 0 || t.isNaN()) return Double.NaN
        if (t.isInfinite()) return 0.0
        return regularizedBeta(df / (df + t * t), df / 2.0, 0.5)
    }

    /** Wilson score interval for a binomial proportion; well-behaved even at 0 or n successes. */
    fun wilson(successes: Int, trials: Int, z: Double = Z_95): Interval {
        if (trials <= 0) return Interval(0.0, 1.0)
        val n = trials.toDouble()
        val p = successes / n
        val denominator = 1.0 + z * z / n
        val centre = (p + z * z / (2 * n)) / denominator
        val half = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denominator
        return Interval(max(0.0, centre - half), min(1.0, centre + half))
    }

    /** Exact (Garwood) 95% interval for a Poisson rate: [count] events over [exposure] units. */
    fun poissonRate(count: Int, exposure: Double): Interval {
        if (exposure <= 0.0) return Interval(0.0, Double.POSITIVE_INFINITY)
        val low = if (count == 0) 0.0 else chiSquareQuantile(0.025, 2 * count) / 2.0
        val high = chiSquareQuantile(0.975, 2 * count + 2) / 2.0
        return Interval(low / exposure, high / exposure)
    }

    fun mean(values: Collection<Double>): Double = if (values.isEmpty()) Double.NaN else values.sum() / values.size

    fun standardDeviation(values: Collection<Double>): Double {
        if (values.size < 2) return Double.NaN
        val mean = mean(values)
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    /** t-based 95% interval for the mean of [values]. */
    fun meanInterval(values: Collection<Double>): Interval {
        val mean = mean(values)
        if (values.size < 2) return Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        val se = standardDeviation(values) / sqrt(values.size.toDouble())
        val t = studentTQuantile975(values.size - 1)
        return Interval(mean - t * se, mean + t * se)
    }

    /** The two-sided 95% critical value of Student-t(df), by bisection. */
    fun studentTQuantile975(df: Int): Double {
        var low = 0.0
        var high = 1000.0
        repeat(200) {
            val mid = (low + high) / 2.0
            if (studentTPValue(mid, df) > 0.05) low = mid else high = mid
        }
        return (low + high) / 2.0
    }
}
