package net.rsprox.proxy.dungeon.analysis

/** Renders analysis reports as Markdown: findings first (the answers), then supporting tables. */
internal object ReportRenderer {
    fun markdown(dataset: DungeonDataset, reports: List<AnalysisReport>): String =
        buildString {
            appendLine("# Dungeon statistics analysis")
            appendLine()
            val floors = dataset.floors
            appendLine(
                "${floors.size} floor(s) from ${floors.map { it.output.sourceFile }.distinct().size} dump(s), " +
                    "${dataset.rooms.size} room(s). Floors ${floors.minOfOrNull { it.floor }}..${floors.maxOfOrNull { it.floor }}.",
            )
            appendLine()
            appendLine(
                "Intervals are 95%. `p` is the raw p-value against the stated null hypothesis; `adj p` is " +
                    "Holm-Bonferroni corrected across the tests in that section, and only `adj p` < " +
                    "${Finding.SIGNIFICANCE_LEVEL} is marked significant. Confidence is a rough label from " +
                    "sample size and interval width.",
            )
            for (report in reports) {
                appendLine()
                appendLine("## ${report.title}")
                appendLine()
                appendLine(report.description)
                if (report.notes.isNotEmpty()) {
                    appendLine()
                    for (note in report.notes) appendLine("- $note")
                }
                if (report.findings.isNotEmpty()) {
                    appendLine()
                    table(
                        this,
                        listOf("subject", "measure", "estimate", "95% CI", "n", "p", "adj p", "sig", "confidence", "detail"),
                        report.findings.map { f ->
                            listOf(
                                f.subject,
                                f.measure,
                                f.estimate,
                                f.interval ?: "",
                                f.sampleSize.toString(),
                                f.pValue?.formatP() ?: "",
                                f.adjustedPValue?.formatP() ?: "",
                                when (f.significant) {
                                    true -> "yes"
                                    false -> "no"
                                    null -> ""
                                },
                                f.confidence.name.lowercase(),
                                f.detail ?: "",
                            )
                        },
                    )
                }
                for (t in report.tables) {
                    appendLine()
                    appendLine("### ${t.title}")
                    appendLine()
                    table(this, t.headers, t.rows)
                }
            }
        }

    private fun table(builder: StringBuilder, headers: List<String>, rows: List<List<String>>) {
        fun escape(cell: String) = cell.replace("|", "\\|")
        builder.appendLine("| " + headers.joinToString(" | ") { escape(it) } + " |")
        builder.appendLine("|" + headers.joinToString("|") { "---" } + "|")
        for (row in rows) builder.appendLine("| " + row.joinToString(" | ") { escape(it) } + " |")
    }
}
