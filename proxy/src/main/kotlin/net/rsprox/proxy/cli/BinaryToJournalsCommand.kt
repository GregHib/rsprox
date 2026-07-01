package net.rsprox.proxy.cli

import com.github.ajalt.clikt.parameters.options.option
import net.rsprot.protocol.message.IncomingMessage
import net.rsprox.protocol.game.outgoing.model.interfaces.IfSetText
import net.rsprox.proxy.cli.ConfigLoader.loadRealMap
import net.rsprox.transcriber.state.SessionState
import java.io.File

/**
 * Pulls out any Journal entry pages from a given binary file
 */
@Suppress("DuplicatedCode")
public class BinaryToJournalsCommand : Transcriber(name = "journals") {
    private val name by option("-name")
    private val interfaces = loadRealMap("iftypes")

    private fun componentId(id: Int, component: Int): String = interfaces.getOrDefault("$id:${component}", "$id:${component}")

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
            println("questJournalOpen(\"$id\") {")
            println("    val lines = when (player.quest(\"$id\")) {")
            for ((index, journal) in journals.withIndex()) {
                println("        \"stage_${index}\" -> listOf(")
                for (i in 10..journal.lines.keys.max()) {
                    val line = journal.lines[i] ?: ""
                    println("            \"${line}\",")
                }
                println("        )")
            }
            println("    }")
            println("player.questJournal(\"${group}\", lines)")
            println("}")
        }
    }

    private data class Journal(
        var title: String = "",
        var lines: MutableMap<Int, String> = mutableMapOf()
    )

    private val journals = mutableListOf<Journal>()

    override fun processPacket(tick: Int, sessionState: SessionState, packet: IncomingMessage) {
        when (packet) {
            is IfSetText -> {
                when (val component = componentId(packet.interfaceId, packet.componentId)) {
                    "questjournal:title", "questjournal:textlayer" -> journals.add(Journal(title = packet.text))
                    else -> if (component.startsWith("questjournal:qj")) {
                        val journal = journals.lastOrNull() ?: return
                        journal.lines[packet.componentId] =
                            packet.text
                                .replace("<col=000080>", "<navy>")
                                .replace("<col=800000>", "<maroon>")
                    } else {
                        println("${packet.interfaceId}:${packet.componentId} ${componentId(packet.interfaceId, packet.componentId)} = ${packet.text}")
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
    BinaryToJournalsCommand().main(arrayOf("-name", "20260403T123516-0ddf543"))
}
