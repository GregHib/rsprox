package net.rsprox.proxy.dungeon.analysis

import kotlin.math.ln

/** How often an event did and didn't happen, over every trial made at one skill [level]. */
internal class LevelTrials(val level: Int) {
    var events: Int = 0
    var nonEvents: Int = 0
    val trials: Int get() = events + nonEvents
}

/** Accumulates [LevelTrials] by level; the input to [LinearChanceModel.fit]. */
internal class LevelTrialSet {
    private val byLevel: MutableMap<Int, LevelTrials> = sortedMapOf()

    val levels: Collection<LevelTrials> get() = byLevel.values
    val events: Int get() = byLevel.values.sumOf { it.events }
    val trials: Int get() = byLevel.values.sumOf { it.trials }

    fun add(level: Int, events: Int, nonEvents: Int) {
        val entry = byLevel.getOrPut(level) { LevelTrials(level) }
        entry.events += events
        entry.nonEvents += nonEvents
    }

    /**
     * One run of Bernoulli trials that stops at the first event (e.g. gathering from a node until it
     * depletes, or clicking an obstruction until it clears): [trials] attempts, the last of which was
     * the event if [endedInEvent], otherwise the run was cut short (censored) with no event seen.
     */
    fun addRun(level: Int, trials: Int, endedInEvent: Boolean) {
        if (trials <= 0) return
        if (endedInEvent) add(level, 1, trials - 1) else add(level, 0, trials)
    }
}

/**
 * The chance of an event is `interpolate(low, high, level) / scale`, where `low` is the chance out of
 * [scale] at [minLevel], `high` the chance at [maxLevel], and the value is linearly interpolated using
 * integer arithmetic between the two - the usual Jagex skilling formula. Levels outside the range are
 * extrapolated along the same line and the resulting probability clamped to [0, 1].
 *
 * [fit] finds `low`/`high` by maximum likelihood over every integer pair in `0..scale` (the parameters
 * are small integers, so an exhaustive grid is both exact and cheap), and puts a 95% profile-likelihood
 * interval around each. Because every trial is independent given the level, the observations reduce
 * to event/non-event counts per level, which is all the grid search needs.
 */
internal class LinearChanceModel(
    val scale: Int = 255,
    val minLevel: Int = 1,
    val maxLevel: Int = 99,
) {
    fun chance(low: Int, high: Int, level: Int): Int = low + (high - low) * (level - minLevel) / (maxLevel - minLevel)

    fun probability(low: Int, high: Int, level: Int): Double = (chance(low, high, level).toDouble() / scale).coerceIn(0.0, 1.0)

    fun fit(data: LevelTrialSet): LinearChanceFit? {
        val levels = data.levels.filter { it.trials > 0 }
        if (levels.isEmpty()) return null
        val size = scale + 1
        val logLikelihood = DoubleArray(size * size)
        var best = Double.NEGATIVE_INFINITY
        var bestLow = 0
        var bestHigh = 0
        for (low in 0..scale) {
            for (high in 0..scale) {
                val value = logLikelihood(low, high, levels)
                logLikelihood[low * size + high] = value
                if (value > best) {
                    best = value
                    bestLow = low
                    bestHigh = high
                }
            }
        }
        // Profile likelihoods: the best fit achievable with one parameter pinned.
        val lowProfile = DoubleArray(size) { low -> (0..scale).maxOf { high -> logLikelihood[low * size + high] } }
        val highProfile = DoubleArray(size) { high -> (0..scale).maxOf { low -> logLikelihood[low * size + high] } }
        val constantProfile = DoubleArray(size) { c -> logLikelihood[c * size + c] }
        val bestConstant = constantProfile.indices.maxBy { constantProfile[it] }

        // Likelihood ratio test of H0: low == high (no level dependence) against the free model.
        val ratio = 2.0 * (best - constantProfile[bestConstant])
        val distinctLevels = levels.size
        val levelDependence = if (distinctLevels >= 2) Statistics.chiSquarePValue(ratio, 1) else Double.NaN

        return LinearChanceFit(
            model = this,
            low = bestLow,
            high = bestHigh,
            lowInterval = profileInterval(lowProfile, best),
            highInterval = profileInterval(highProfile, best),
            constant = bestConstant,
            constantInterval = profileInterval(constantProfile, constantProfile[bestConstant]),
            levelDependencePValue = levelDependence,
            minObservedLevel = levels.minOf { it.level },
            maxObservedLevel = levels.maxOf { it.level },
            distinctLevels = distinctLevels,
            events = data.events,
            trials = data.trials,
        )
    }

    private fun logLikelihood(low: Int, high: Int, levels: List<LevelTrials>): Double {
        var total = 0.0
        for (entry in levels) {
            val p = probability(low, high, entry.level)
            if (entry.events > 0) {
                if (p <= 0.0) return Double.NEGATIVE_INFINITY
                total += entry.events * ln(p)
            }
            if (entry.nonEvents > 0) {
                if (p >= 1.0) return Double.NEGATIVE_INFINITY
                total += entry.nonEvents * ln(1.0 - p)
            }
        }
        return total
    }

    /** Every parameter value whose profile is within the chi-square(1) 95% cutoff of the maximum. */
    private fun profileInterval(profile: DoubleArray, max: Double): IntRange {
        val cutoff = max - Statistics.CHI2_95_DF1 / 2.0
        val inside = profile.indices.filter { profile[it] >= cutoff }
        return inside.first()..inside.last()
    }
}

internal class LinearChanceFit(
    val model: LinearChanceModel,
    /** Maximum-likelihood chance (out of [LinearChanceModel.scale]) at [LinearChanceModel.minLevel]. */
    val low: Int,
    /** Maximum-likelihood chance (out of [LinearChanceModel.scale]) at [LinearChanceModel.maxLevel]. */
    val high: Int,
    val lowInterval: IntRange,
    val highInterval: IntRange,
    /** The best single chance ignoring level entirely, and its interval; always well-determined. */
    val constant: Int,
    val constantInterval: IntRange,
    /** Likelihood-ratio test p-value that chance depends on level at all; NaN with a single level. */
    val levelDependencePValue: Double,
    val minObservedLevel: Int,
    val maxObservedLevel: Int,
    val distinctLevels: Int,
    val events: Int,
    val trials: Int,
) {
    /**
     * With every trial at one level, only the chance *at that level* is determined: any low/high line
     * through that point fits equally well, so [lowInterval]/[highInterval] are meaningless on their
     * own. The wider the spread of observed levels, the tighter low/high become.
     */
    val identifiable: Boolean get() = distinctLevels >= 2

    fun describe(): String {
        val scale = model.scale
        return "low=$low/$scale ${lowInterval.format()} high=$high/$scale ${highInterval.format()}"
    }
}

internal fun IntRange.format(): String = "[$first, $last]"
