package net.rsprox.proxy.dungeon.analysis

/**
 * One question asked of many dungeon floors, e.g. "what are each resource's depletion chances?".
 *
 * To add a new analysis: implement this, turn the dataset into observations, and hand them to the
 * helpers on [AnalysisReport] ([AnalysisReport.linearChance], [AnalysisReport.categorical],
 * [AnalysisReport.regression], [AnalysisReport.proportion], [AnalysisReport.rate]), which do the
 * statistics and record findings/tables in a consistent form; then register it in [ALL_ANALYSES].
 */
internal interface DungeonAnalysis {
    /** Short, unique, command-line-friendly id. */
    val id: String
    val title: String
    val description: String

    fun analyse(dataset: DungeonDataset, report: AnalysisReport)
}

internal val ALL_ANALYSES: List<DungeonAnalysis> =
    listOf(
        ResourceDepletionAnalysis(),
        ResourceSpawnAnalysis(),
        BossTypeAnalysis(),
        NpcSpawnCountAnalysis(),
        NpcStatsAnalysis(),
        ObstructionAnalysis(),
        KeyDoorAnalysis(),
    )

/**
 * A rough, human-oriented label for how far a finding can be trusted, derived from how wide its
 * confidence interval is relative to the range the value could take.
 */
internal enum class Confidence {
    INSUFFICIENT,
    LOW,
    MODERATE,
    HIGH,
    ;

    companion object {
        /** [relativeWidth] is the 95% interval's width as a fraction of the value's plausible range. */
        fun of(sampleSize: Int, relativeWidth: Double, minimumSample: Int = 5): Confidence =
            when {
                sampleSize < minimumSample || relativeWidth.isNaN() -> INSUFFICIENT
                relativeWidth > 0.5 -> LOW
                relativeWidth > 0.15 -> MODERATE
                else -> HIGH
            }
    }
}

internal class Finding(
    /** What the finding is about, e.g. "mining tier 1". */
    val subject: String,
    /** What was measured, e.g. "depletion chance". */
    val measure: String,
    val estimate: String,
    /** The 95% confidence interval of [estimate], when it has one. */
    val interval: String?,
    val sampleSize: Int,
    /** The p-value of this finding's hypothesis test, when it has one (see [hypothesis]). */
    val pValue: Double?,
    /** The null hypothesis [pValue] is against, e.g. "chance doesn't depend on level". */
    val hypothesis: String?,
    val confidence: Confidence,
    val detail: String? = null,
) {
    /** [pValue] after Holm-Bonferroni correction across every tested finding in the same report. */
    var adjustedPValue: Double? = null

    val significant: Boolean? get() = adjustedPValue?.let { !it.isNaN() && it < SIGNIFICANCE_LEVEL }

    companion object {
        const val SIGNIFICANCE_LEVEL: Double = 0.05
    }
}

internal class ReportTable(val title: String, val headers: List<String>, val rows: List<List<String>>)

internal class AnalysisReport(val id: String, val title: String, val description: String) {
    val notes: MutableList<String> = mutableListOf()
    val findings: MutableList<Finding> = mutableListOf()
    val tables: MutableList<ReportTable> = mutableListOf()

    fun note(text: String) {
        notes += text
    }

    fun finding(finding: Finding) {
        findings += finding
    }

    fun table(table: ReportTable) {
        tables += table
    }

    /**
     * Holm-Bonferroni: each analysis runs many tests (every covariate against every outcome), so
     * without a correction one in twenty would look "significant" by chance alone.
     */
    fun adjustPValues() {
        val tested = findings.filter { it.pValue != null && !it.pValue.isNaN() }.sortedBy { it.pValue }
        var runningMax = 0.0
        for ((rank, finding) in tested.withIndex()) {
            val adjusted = (finding.pValue!! * (tested.size - rank)).coerceAtMost(1.0)
            runningMax = maxOf(runningMax, adjusted)
            finding.adjustedPValue = runningMax
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers shared by the analyses.
    // ---------------------------------------------------------------------------------------------

    /**
     * Fits a [LinearChanceModel] (chance = low..high out of 255, interpolated by level) to [data] and
     * records the result. [eventName] describes what a success is, e.g. "deplete".
     */
    fun linearChance(subject: String, eventName: String, data: LevelTrialSet, model: LinearChanceModel = LinearChanceModel()) {
        val fit = model.fit(data)
        if (fit == null) {
            finding(Finding(subject, "$eventName chance", "no trials", null, 0, null, null, Confidence.INSUFFICIENT))
            return
        }
        val scale = model.scale
        val levelNote = "levels ${fit.minObservedLevel}..${fit.maxObservedLevel} (${fit.distinctLevels} distinct)"
        if (fit.identifiable) {
            val width = maxOf(fit.lowInterval.last - fit.lowInterval.first, fit.highInterval.last - fit.highInterval.first)
            finding(
                Finding(
                    subject = subject,
                    measure = "$eventName chance at level ${model.minLevel}..${model.maxLevel} (/$scale)",
                    estimate = "${fit.low}..${fit.high}",
                    interval = "low ${fit.lowInterval.format()}, high ${fit.highInterval.format()}",
                    sampleSize = fit.trials,
                    pValue = fit.levelDependencePValue,
                    hypothesis = "chance is the same at every level",
                    confidence = Confidence.of(fit.trials, width.toDouble() / scale),
                    detail = "${fit.events} ${eventName}s in ${fit.trials} trials, $levelNote",
                ),
            )
        }
        // The level-independent chance is always determined, and is the one to read when every
        // observation came from the same level (low/high are then unidentifiable on their own).
        val constantWidth = fit.constantInterval.last - fit.constantInterval.first
        finding(
            Finding(
                subject = subject,
                measure = "$eventName chance, ignoring level (/$scale)",
                estimate = "${fit.constant} (${(fit.constant.toDouble() / scale).fmt()})",
                interval = fit.constantInterval.format(),
                sampleSize = fit.trials,
                pValue = null,
                hypothesis = null,
                confidence = Confidence.of(fit.trials, constantWidth.toDouble() / scale),
                detail =
                    if (fit.identifiable) {
                        "${fit.events} ${eventName}s in ${fit.trials} trials, $levelNote"
                    } else {
                        "${fit.events} ${eventName}s in ${fit.trials} trials, all at level ${fit.minObservedLevel}: " +
                            "low/high can't be separated until more levels are observed"
                    },
            ),
        )
    }

    /**
     * Records how often each outcome occurred (with Wilson intervals), then tests whether the outcome
     * depends on each covariate (see [ContingencyTest]), recording a finding per covariate and a table
     * for any that tests significant before correction.
     */
    fun <T> categorical(
        subject: String,
        outcomeName: String,
        observations: List<T>,
        outcome: (T) -> String?,
        covariates: List<Covariate<T>>,
    ) {
        val labelled = observations.mapNotNull { obs -> outcome(obs)?.let { obs to it } }
        if (labelled.isEmpty()) {
            note("$subject: no observations of $outcomeName.")
            return
        }
        val n = labelled.size
        val frequencies = labelled.groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }
        table(
            ReportTable(
                "$subject: $outcomeName frequency",
                listOf(outcomeName, "count", "share", "95% CI"),
                frequencies.map { (value, count) ->
                    listOf(value, count.toString(), (count.toDouble() / n).fmt(), Statistics.wilson(count, n).format())
                },
            ),
        )
        for (covariate in covariates) {
            val pairs = labelled.mapNotNull { (obs, value) -> covariate.value(obs)?.let { it to value } }
            if (pairs.map { it.first }.distinct().size < 2 || pairs.map { it.second }.distinct().size < 2) continue
            val test = ContingencyTest(pairs)
            finding(
                Finding(
                    subject = subject,
                    measure = "$outcomeName depends on ${covariate.name}",
                    estimate = "Cramer's V ${test.cramersV.fmt(2)}",
                    interval = null,
                    sampleSize = test.total,
                    pValue = test.pValue,
                    hypothesis = "$outcomeName is independent of ${covariate.name}",
                    confidence = Confidence.of(test.total, if (test.sparse) 1.0 else 0.3, minimumSample = 10),
                    detail = "${test.method}, chi2=${test.statistic.fmt(2)}, df=${test.degreesOfFreedom}",
                ),
            )
            if (test.pValue < Finding.SIGNIFICANCE_LEVEL) {
                table(test.toTable("$subject: $outcomeName by ${covariate.name}", covariate.name))
            }
        }
    }

    /** Fits `outcome ~ predictors` and records each coefficient as a finding. */
    fun regression(subject: String, outcomeName: String, rows: List<RegressionRow>, predictors: List<String>) {
        val distinct = rows.map { it.outcome }.distinct()
        if (distinct.size == 1) {
            // Nothing to explain: every observation had the same value, which is the finding itself.
            finding(
                Finding(
                    subject = subject,
                    measure = "$outcomeName is constant",
                    estimate = distinct.single().fmt(),
                    interval = null,
                    sampleSize = rows.size,
                    pValue = null,
                    hypothesis = null,
                    confidence = Confidence.of(rows.size, 0.0),
                    detail = "same value in all ${rows.size} observations",
                ),
            )
            return
        }
        val fit = LinearRegression.fit(rows, predictors)
        if (fit == null) {
            note("$subject: not enough varied data to regress $outcomeName on ${predictors.joinToString()} (n=${rows.size}).")
            return
        }
        if (fit.dropped.isNotEmpty()) {
            note("$subject: ${fit.dropped.joinToString()} never varied (or was collinear) in the data, so its effect on $outcomeName is unknown.")
        }
        val outcomeSd = Statistics.standardDeviation(rows.map { it.outcome })
        for (coefficient in fit.coefficients) {
            val isIntercept = coefficient.name == "(intercept)"
            finding(
                Finding(
                    subject = subject,
                    measure = if (isIntercept) "$outcomeName intercept" else "$outcomeName per +1 ${coefficient.name}",
                    estimate = coefficient.estimate.fmt(),
                    interval = coefficient.interval.format(),
                    sampleSize = fit.n,
                    pValue = if (isIntercept) null else coefficient.pValue,
                    hypothesis = if (isIntercept) null else "${coefficient.name} has no effect on $outcomeName",
                    confidence =
                        Confidence.of(
                            fit.n,
                            if (outcomeSd > 0) coefficient.interval.width / (4 * outcomeSd) else Double.NaN,
                            minimumSample = 10,
                        ),
                    detail = "R^2=${fit.rSquared.fmt(2)}, model p=${fit.modelPValue.formatP()}",
                ),
            )
        }
    }

    /** Records a simple proportion with its Wilson interval. */
    fun proportion(subject: String, measure: String, successes: Int, trials: Int, detail: String? = null) {
        val interval = Statistics.wilson(successes, trials)
        finding(
            Finding(
                subject = subject,
                measure = measure,
                estimate = if (trials == 0) "n/a" else (successes.toDouble() / trials).fmt(),
                interval = interval.format(),
                sampleSize = trials,
                pValue = null,
                hypothesis = null,
                confidence = Confidence.of(trials, interval.width),
                detail = detail ?: "$successes of $trials",
            ),
        )
    }

    /** Records a Poisson rate ([count] events per unit of [exposure]) with its exact interval. */
    fun rate(subject: String, measure: String, count: Int, exposure: Int, detail: String? = null) {
        val interval = Statistics.poissonRate(count, exposure.toDouble())
        val estimate = if (exposure == 0) Double.NaN else count.toDouble() / exposure
        finding(
            Finding(
                subject = subject,
                measure = measure,
                estimate = estimate.fmt(),
                interval = interval.format(),
                sampleSize = exposure,
                pValue = null,
                hypothesis = null,
                confidence = Confidence.of(exposure, if (estimate > 0) interval.width / (2 * estimate) else Double.NaN),
                detail = detail ?: "$count over $exposure",
            ),
        )
    }
}
