package net.rsprox.proxy.dungeon.analysis

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tests whether a categorical outcome (e.g. which boss spawned) is independent of a categorical
 * covariate (e.g. the floor's complexity), from a list of (covariate, outcome) observations.
 *
 * The usual Pearson chi-square approximation is unreliable with sparse tables, which is exactly the
 * situation while only a handful of dumps exist, so the reported [pValue] is a Monte Carlo permutation
 * test of the same statistic (shuffling outcomes against covariates) whenever any expected cell count
 * is below 5; that stays valid at any sample size, at the cost of a little randomness (seeded, so
 * repeat runs over the same data agree).
 */
internal class ContingencyTest(
    observations: List<Pair<String, String>>,
    permutations: Int = DEFAULT_PERMUTATIONS,
    seed: Long = 0x5eed,
) {
    val rows: List<String> = observations.map { it.first }.distinct().sorted()
    val columns: List<String> = observations.map { it.second }.distinct().sorted()
    val counts: Array<IntArray> = Array(rows.size) { IntArray(columns.size) }
    val total: Int = observations.size

    val statistic: Double
    val degreesOfFreedom: Int = (rows.size - 1) * (columns.size - 1)
    val sparse: Boolean
    val pValue: Double
    val method: String

    /** Effect size in [0, 1]: 0 = independent, 1 = the covariate fully determines the outcome. */
    val cramersV: Double

    init {
        val rowIndex = rows.withIndex().associate { it.value to it.index }
        val columnIndex = columns.withIndex().associate { it.value to it.index }
        val rowCodes = IntArray(total)
        val columnCodes = IntArray(total)
        for ((i, pair) in observations.withIndex()) {
            rowCodes[i] = rowIndex.getValue(pair.first)
            columnCodes[i] = columnIndex.getValue(pair.second)
            counts[rowCodes[i]][columnCodes[i]]++
        }
        val rowTotals = counts.map { it.sum() }
        val columnTotals = columns.indices.map { c -> counts.sumOf { it[c] } }
        statistic = chiSquare(counts, rowTotals, columnTotals)
        sparse = rows.indices.any { r -> columns.indices.any { c -> rowTotals[r].toDouble() * columnTotals[c] / total < 5.0 } }
        if (degreesOfFreedom <= 0 || total == 0) {
            pValue = Double.NaN
            method = "untestable"
        } else if (!sparse) {
            pValue = Statistics.chiSquarePValue(statistic, degreesOfFreedom)
            method = "chi-square"
        } else {
            val random = Random(seed)
            val shuffled = columnCodes.copyOf()
            val table = Array(rows.size) { IntArray(columns.size) }
            var atLeastAsExtreme = 0
            repeat(permutations) {
                shuffled.shuffle(random)
                for (row in table) row.fill(0)
                for (i in 0 until total) table[rowCodes[i]][shuffled[i]]++
                if (chiSquare(table, rowTotals, columnTotals) >= statistic - 1e-9) atLeastAsExtreme++
            }
            pValue = (atLeastAsExtreme + 1.0) / (permutations + 1.0)
            method = "permutation"
        }
        val k = min(rows.size, columns.size) - 1
        cramersV = if (k <= 0 || total == 0) Double.NaN else sqrt(statistic / (total * k))
    }

    private fun chiSquare(table: Array<IntArray>, rowTotals: List<Int>, columnTotals: List<Int>): Double {
        var sum = 0.0
        for (r in table.indices) {
            for (c in columnTotals.indices) {
                val expected = rowTotals[r].toDouble() * columnTotals[c] / total
                if (expected <= 0.0) continue
                val diff = table[r][c] - expected
                sum += diff * diff / expected
            }
        }
        return sum
    }

    /** The table as rows of "covariate, count per outcome..., total", ready for a [ReportTable]. */
    fun toTable(title: String, rowLabel: String): ReportTable =
        ReportTable(
            title = title,
            headers = listOf(rowLabel) + columns + "n",
            rows = rows.indices.map { r -> listOf(rows[r]) + counts[r].map { it.toString() } + counts[r].sum().toString() },
        )

    companion object {
        const val DEFAULT_PERMUTATIONS: Int = 5_000
    }
}

/**
 * Tests whether [counts] (observations per category) fit the [expected] probabilities, e.g. whether
 * every key colour is equally likely. Like [ContingencyTest], this uses a seeded Monte Carlo p-value
 * (sampling from the expected distribution) when any expected count is below 5, and the chi-square
 * approximation otherwise.
 */
internal class GoodnessOfFitTest(
    val counts: Map<String, Int>,
    val expected: Map<String, Double>,
    simulations: Int = ContingencyTest.DEFAULT_PERMUTATIONS,
    seed: Long = 0x5eed,
) {
    val total: Int = counts.values.sum()
    val categories: List<String> = expected.keys.toList()
    val degreesOfFreedom: Int = categories.size - 1
    val statistic: Double = chiSquare(categories.map { counts[it] ?: 0 }.toIntArray())
    val pValue: Double
    val method: String

    init {
        val sparse = categories.any { expected.getValue(it) * total < 5.0 }
        if (degreesOfFreedom <= 0 || total == 0) {
            pValue = Double.NaN
            method = "untestable"
        } else if (!sparse) {
            pValue = Statistics.chiSquarePValue(statistic, degreesOfFreedom)
            method = "chi-square"
        } else {
            val random = Random(seed)
            val cumulative = categories.runningFold(0.0) { acc, c -> acc + expected.getValue(c) }.drop(1)
            val sample = IntArray(categories.size)
            var atLeastAsExtreme = 0
            repeat(simulations) {
                sample.fill(0)
                repeat(total) {
                    val r = random.nextDouble() * cumulative.last()
                    sample[cumulative.indexOfFirst { r < it }.coerceAtLeast(0)]++
                }
                if (chiSquare(sample) >= statistic - 1e-9) atLeastAsExtreme++
            }
            pValue = (atLeastAsExtreme + 1.0) / (simulations + 1.0)
            method = "monte carlo"
        }
    }

    private fun chiSquare(observed: IntArray): Double {
        val sumExpected = expected.values.sum()
        var sum = 0.0
        for ((i, category) in categories.withIndex()) {
            val e = expected.getValue(category) / sumExpected * total
            if (e <= 0.0) continue
            val diff = observed[i] - e
            sum += diff * diff / e
        }
        return sum
    }

    companion object {
        fun uniform(counts: Map<String, Int>, categories: List<String>): GoodnessOfFitTest =
            GoodnessOfFitTest(counts, categories.associateWith { 1.0 / categories.size })
    }
}
