package net.rsprox.proxy.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.google.gson.GsonBuilder
import net.rsprox.proxy.config.BINARY_PATH
import net.rsprox.proxy.dungeon.analysis.ALL_ANALYSES
import net.rsprox.proxy.dungeon.analysis.AnalysisReport
import net.rsprox.proxy.dungeon.analysis.DungeonDataset
import net.rsprox.proxy.dungeon.analysis.ReportRenderer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Aggregates the per-floor JSON written by [DungeonStatisticsCommand] across many dumps and estimates
 * the underlying chances (resource depletion, spawn types, npc counts/levels, obstruction clears...)
 * along with how confident those estimates are. Writes `dungeon-analysis.md` (readable) and
 * `dungeon-analysis.json` (the same findings, machine-readable) and prints the Markdown.
 */
public class DungeonAnalysisCommand : CliktCommand(name = "dungeonanalysis"), Runnable {
    private val inDir by option("-in", help = "Directory of dungeonstats floor .json files")
    private val outDir by option("-out", help = "Directory to write the report to")
    private val only by option("-only", help = "Comma-separated analysis ids to run (default: all)")

    override fun run() {
        val input = inDir?.let { Path.of(it) } ?: BINARY_PATH.resolveSibling("dungeon-stats")
        val output = outDir?.let { Path.of(it) } ?: BINARY_PATH.resolveSibling("dungeon-analysis")
        val dataset = DungeonDataset.load(input) { path, e -> echo("Skipping unreadable $path: ${e.message}", err = true) }
        if (dataset.floors.isEmpty()) {
            echo("No dungeon floors found in $input", err = true)
            return
        }
        val selected = only?.split(",")?.map { it.trim() }?.toSet()
        val analyses = ALL_ANALYSES.filter { selected == null || it.id in selected }
        if (analyses.isEmpty()) {
            echo("No analyses match '$only'; available: ${ALL_ANALYSES.joinToString { it.id }}", err = true)
            return
        }
        val reports =
            analyses.map { analysis ->
                AnalysisReport(analysis.id, analysis.title, analysis.description).also {
                    analysis.analyse(dataset, it)
                    it.adjustPValues()
                }
            }
        val markdown = ReportRenderer.markdown(dataset, reports)
        output.createDirectories()
        output.resolve("dungeon-analysis.md").writeText(markdown)
        output.resolve("dungeon-analysis.json").writeText(GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(reports))
        echo(markdown)
        echo("Wrote report for ${dataset.floors.size} floor(s) to $output")
    }
}

public fun main(args: Array<String>) {
    DungeonAnalysisCommand().main(args)
}
