package net.rsprox.proxy.dungeon.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/** One row of regression data: the [outcome] and each named predictor's value. */
internal class RegressionRow(val outcome: Double, val predictors: Map<String, Double>)

internal class RegressionCoefficient(
    val name: String,
    val estimate: Double,
    val standardError: Double,
    val pValue: Double,
    /** Residual degrees of freedom, for the t-based [interval]. */
    val degreesOfFreedom: Int,
) {
    val interval: Interval
        get() {
            val t = Statistics.studentTQuantile975(degreesOfFreedom)
            return Interval(estimate - t * standardError, estimate + t * standardError)
        }
}

internal class RegressionFit(
    val coefficients: List<RegressionCoefficient>,
    val rSquared: Double,
    val n: Int,
    /** Overall F-test p-value that any predictor explains the outcome. */
    val modelPValue: Double,
    /** Predictors dropped before fitting because they never varied (or were collinear) in this data. */
    val dropped: List<String>,
)

/**
 * Ordinary least squares with an intercept. Predictors that don't vary in the data (common while
 * every dump is from one player, e.g. party size is always 1) can't be estimated, so they're dropped
 * and reported in [RegressionFit.dropped] rather than failing the whole fit.
 */
internal object LinearRegression {
    fun fit(rows: List<RegressionRow>, predictors: List<String>): RegressionFit? {
        val dropped = mutableListOf<String>()
        val kept = predictors.filter { name ->
            val values = rows.map { it.predictors[name] ?: Double.NaN }
            val usable = values.none { it.isNaN() } && values.distinct().size > 1
            if (!usable) dropped += name
            usable
        }.toMutableList()
        while (true) {
            val p = kept.size + 1
            if (rows.size <= p) return null
            val x = rows.map { row -> doubleArrayOf(1.0) + kept.map { row.predictors.getValue(it) }.toDoubleArray() }
            val y = rows.map { it.outcome }
            val xtx = Array(p) { i -> DoubleArray(p) { j -> x.sumOf { it[i] * it[j] } } }
            val inverse = invert(xtx)
            if (inverse == null) {
                // Collinear: drop the last remaining predictor and retry.
                if (kept.isEmpty()) return null
                dropped += kept.removeAt(kept.lastIndex)
                continue
            }
            val xty = DoubleArray(p) { i -> x.indices.sumOf { r -> x[r][i] * y[r] } }
            val beta = DoubleArray(p) { i -> (0 until p).sumOf { j -> inverse[i][j] * xty[j] } }
            val fitted = x.map { row -> row.indices.sumOf { row[it] * beta[it] } }
            val residualSs = y.indices.sumOf { (y[it] - fitted[it]).let { e -> e * e } }
            val meanY = y.average()
            val totalSs = y.sumOf { (it - meanY) * (it - meanY) }
            val df = rows.size - p
            val sigma2 = residualSs / df
            val coefficients =
                (0 until p).map { i ->
                    val se = sqrt(sigma2 * inverse[i][i])
                    val t = beta[i] / se
                    RegressionCoefficient(
                        name = if (i == 0) "(intercept)" else kept[i - 1],
                        estimate = beta[i],
                        standardError = se,
                        pValue = if (se == 0.0) (if (beta[i] == 0.0) 1.0 else 0.0) else Statistics.studentTPValue(t, df),
                        degreesOfFreedom = df,
                    )
                }
            val rSquared = if (totalSs == 0.0) Double.NaN else 1.0 - residualSs / totalSs
            val modelP =
                if (kept.isEmpty() || totalSs == 0.0) {
                    Double.NaN
                } else {
                    val f = ((totalSs - residualSs) / kept.size) / sigma2
                    fTestPValue(f, kept.size, df)
                }
            return RegressionFit(coefficients, rSquared, rows.size, modelP, dropped)
        }
    }

    private fun fTestPValue(f: Double, df1: Int, df2: Int): Double {
        if (f.isNaN()) return Double.NaN
        if (f.isInfinite()) return 0.0
        return Statistics.regularizedBeta(df2 / (df2 + df1 * f), df2 / 2.0, df1 / 2.0)
    }

    /** Gauss-Jordan inverse with partial pivoting; null when (numerically) singular. */
    private fun invert(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val n = matrix.size
        val a = Array(n) { i -> matrix[i].copyOf() + DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        val scale = matrix.maxOf { row -> row.maxOf { abs(it) } }.coerceAtLeast(1.0)
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-10 * scale) return null
            val tmp = a[col]
            a[col] = a[pivot]
            a[pivot] = tmp
            val divisor = a[col][col]
            for (j in 0 until 2 * n) a[col][j] /= divisor
            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col]
                if (factor == 0.0) continue
                for (j in 0 until 2 * n) a[row][j] -= factor * a[col][j]
            }
        }
        return Array(n) { i -> a[i].copyOfRange(n, 2 * n) }
    }
}
