package net.rsprox.proxy.cli

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import net.rsprot.protocol.message.IncomingMessage
import net.rsprox.transcriber.state.SessionState
import java.io.File
import java.nio.file.Path
import java.util.TreeMap
import kotlin.io.path.nameWithoutExtension

/**
 * Searches every packet of every binary for a string and reports which binaries contain it,
 * and how many times it was found in each.
 *
 * Usage: `-search "Welcome to Daemonheim"`, optionally with `-i` (ignore case), `-names a,b` (only
 * search those binaries) and `-packets` (also break the count down by packet type).
 *
 * The search runs over each packet's `toString()`, so it matches any text or value the packet holds.
 */
public class StringSearchCommand : Transcriber(name = "search") {
    private val search by option("-search", help = "String to search for").required()
    private val ignoreCase by option("-i", help = "Ignore case").flag()
    private val names by option("-names", help = "Comma separated binary names (without .bin)")
    private val packets by option("-packets", help = "Break counts down by packet type").flag()

    private class Result {
        var total = 0
        val byPacket = TreeMap<String, Int>()
    }

    private val results = TreeMap<String, Result>()
    private var current: Result? = null

    override fun filter(path: File): Boolean {
        val wanted = names?.split(",")?.map { it.trim() } ?: return true
        return path.nameWithoutExtension in wanted
    }

    override fun onBinary(path: Path) {
        current = Result().also { results[path.nameWithoutExtension] = it }
    }

    override fun processPacket(tick: Int, sessionState: SessionState, packet: IncomingMessage) {
        val count = occurrences(packet.toString())
        if (count == 0) return
        val result = current ?: return
        result.total += count
        result.byPacket.merge(packet.javaClass.simpleName, count, Int::plus)
    }

    private fun occurrences(text: String): Int {
        var count = 0
        var index = text.indexOf(search, ignoreCase = ignoreCase)
        while (index != -1) {
            count++
            index = text.indexOf(search, index + search.length, ignoreCase)
        }
        return count
    }

    override fun run() {
        super.run()
        val found = results.filterValues { it.total > 0 }
        println()
        println("==== Search results for \"$search\" ====")
        for ((name, result) in found.entries.sortedByDescending { it.value.total }) {
            println("$name: ${result.total}")
            if (packets) {
                for ((packet, count) in result.byPacket.entries.sortedByDescending { it.value }) {
                    println("    $packet: $count")
                }
            }
        }
        println("Found ${found.values.sumOf { it.total }} match(es) in ${found.size} of ${results.size} binaries")
    }
}

public fun main() {
    StringSearchCommand().main(arrayOf("-search", "pyre logs"))
}
