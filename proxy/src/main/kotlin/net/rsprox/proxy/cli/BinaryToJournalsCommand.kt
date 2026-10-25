package net.rsprox.proxy.cli

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import net.rsprot.protocol.message.IncomingMessage
import net.rsprox.protocol.game.outgoing.model.interfaces.IfSetText
import net.rsprox.protocol.game.outgoing.model.misc.player.RunClientScript
import net.rsprox.proxy.cli.ConfigLoader.loadRealMap
import net.rsprox.proxy.cli.ConfigLoader.loadRs3
import net.rsprox.proxy.cli.ConfigLoader.loadRs3Map
import net.rsprox.transcriber.state.SessionState
import java.io.File
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfSetText as Rs3IfSetText

/**
 * Pulls out any Journal entry pages from a given binary file
 */
@Suppress("DuplicatedCode")
public class BinaryToJournalsCommand : Transcriber(name = "journals") {
    private val name by option("-name")
    private val staged by option("-staged").flag()
    private val interfaces = loadRealMap("iftypes", "leak-2025-04")
    private val rs3Components = loadRs3Map("component")

    public fun componentId(id: Int, component: Int, rs3: Boolean = false): String {
        val interfaces = if (rs3) rs3Components else interfaces
        return interfaces.getOrDefault("$id:${component}", "$id:${component}")
    }

    override fun filter(path: File): Boolean {
        return path.nameWithoutExtension == name
    }

    override fun run() {
        super.run()
        println("==== Journals ====")
        for ((group, journals) in journals.distinct().groupBy { it.title }) {
            val id = group.lowercase()
                .replace(" ", "_")
                .removePrefix("<col=7f0000>")
                .removeSuffix("</col>")
            if (staged) {
                printStaged(id, group, journals)
            } else {
                printWhen(id, group, journals)
            }
        }
    }

    private fun trimmedLines(journal: Journal): List<String> {
        return (journal.start..(journal.lines.keys.maxOrNull() ?: journal.start))
            .map { journal.lines[it] ?: "" }
            .dropLastWhile { it.isBlank() }
    }

    private fun printWhen(id: String, title: String, journals: List<Journal>) {
        println("questJournalOpen(\"$id\") {")
        println("    val lines = when (quest(\"$id\")) {")
        for ((index, journal) in journals.withIndex()) {
            println("        \"stage_${index}\" -> listOf(")
            for (line in trimmedLines(journal)) {
                println("            \"${line}\",")
            }
            println("        )")
        }
        println("    }")
        println("player.questJournal(\"${title}\", lines)")
        println("}")
    }

    /**
     * Splits lines into blocks, each block ending in the blank line that follows it.
     */
    private fun blocks(lines: List<String>): List<List<String>> {
        val blocks = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) {
                if (current.isEmpty() && blocks.isNotEmpty()) {
                    blocks.last().add("")
                } else {
                    current.add("")
                    blocks.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) {
            blocks.add(current)
        }
        return blocks
    }

    private fun isStruck(block: List<String>): Boolean {
        val text = block.filter { it.isNotBlank() }
        return text.isNotEmpty() && text.all { it.startsWith("<str>") }
    }

    /**
     * Prints one if-condition per distinct block per block position, so that the lines shared by many stages
     * (such as completed, struck-through steps) are only written once.
     */
    private fun printStaged(id: String, title: String, journals: List<Journal>) {
        val stages = journals.map { blocks(trimmedLines(it)) }
        val positions = stages.maxOfOrNull { it.size } ?: 0
        println("questJournalOpen(\"$id\") {")
        println("    val lines = mutableListOf<String>()")
        println("    val stage = questStage(\"$id\")")
        for (position in 0 until positions) {
            val variants = mutableListOf<Triple<Int, Int, List<String>>>()
            for ((stage, blocks) in stages.withIndex()) {
                val block = blocks.getOrNull(position) ?: continue
                val last = variants.lastOrNull()
                if (last != null && last.second == stage - 1 && last.third == block) {
                    variants[variants.size - 1] = Triple(last.first, stage, block)
                } else {
                    variants.add(Triple(stage, stage, block))
                }
            }
            for ((index, variant) in variants.withIndex()) {
                val (from, to, block) = variant
                val condition = when {
                    // Struck-through lines stay once they are completed
                    isStruck(block) && to == stages.lastIndex -> "stage >= $from"
                    from == to -> "stage == $from"
                    else -> "stage in $from..$to"
                }
                val keyword = if (index > 0 && !isStruck(block) && !isStruck(variants[index - 1].third)) "} else if" else "if"
                if (keyword == "if") {
                    if (index > 0) println("    }")
                    println("    if ($condition) {")
                } else {
                    println("    } else if ($condition) {")
                }
                for (line in block) {
                    println("        lines.add(\"${line}\")")
                }
                if (index == variants.lastIndex) println("    }")
            }
        }
        println("    questJournal(\"${title}\", lines)")
        println("}")
    }

    private data class Journal(
        var title: String = "",
        var lines: MutableMap<Int, String> = mutableMapOf(),
        // The key of the first line, osrs lines are keyed by component id, rs3 by quest journal line number
        val start: Int = 10,
    )

    private val journals = mutableListOf<Journal>()

    override fun processPacket(tick: Int, sessionState: SessionState, packet: IncomingMessage) {
        when (packet) {
            is IfSetText -> {
                when (val component = componentId(packet.interfaceId, packet.componentId, rs3 = false)) {
                    "questjournal:title", "questjournal:textlayer" -> journals.add(Journal(title = packet.text))
                    else -> if (component.startsWith("questjournal:qj")) {
                        val journal = journals.lastOrNull() ?: return
                        journal.lines[packet.componentId] =
                            packet.text
                                .replace("<col=000080>", "<navy>")
                                .replace("<col=800000>", "<maroon>")
                    } else {
                        println("${packet.interfaceId}:${packet.componentId} ${componentId(packet.interfaceId, packet.componentId)} = \"${packet.text}\"")
                    }
                }
            }
            is Rs3IfSetText -> {
                val interfaceId = (packet.componentHash ushr 16).toInt()
                val componentId = (packet.componentHash and 0xFFFF).toInt()
                val component = componentId(interfaceId, componentId, rs3 = true)
                // The journal is first cleared by setting every line to '', which must not touch the previous journal
                if (component.startsWith("quest_journal:qj") && packet.text.isNotEmpty()) {
                    val line = component.removePrefix("quest_journal:qj").toIntOrNull() ?: return
                    // Line 1 is sent before the title script, so it marks the start of a new journal
                    if (line == 1) {
                        journals.add(Journal(start = 1))
                    }
                    val journal = journals.lastOrNull() ?: return
                    journal.lines[line] =
                        packet.text
                            .replace("<col=EB981F>", "<navy>")
                            .replace("<col=EBE076>", "<maroon>")
                            .replace("<col=999999>", "<str>")
                            .replace("<col=FFFFFF>", "<red>")
                }
            }
            is RunClientScript -> {
                if (packet.id == 4021) {
                    val title = packet.values.first() as String
                    val last = journals.lastOrNull()
                    if (last != null && last.title.isEmpty()) {
                        last.title = title
                    } else {
                        journals.add(Journal(title = title, start = 1))
                    }
                }
            }
            else -> {
//                println(packet)
            }
        }
    }
}

public fun main() {
    BinaryToJournalsCommand().main(arrayOf("-name", "20261015T160142-377bffb", "-staged"))
}
