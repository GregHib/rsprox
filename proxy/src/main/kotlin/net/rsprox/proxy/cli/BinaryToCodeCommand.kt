package net.rsprox.proxy.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import net.rsprot.protocol.message.IncomingMessage
import net.rsprox.cache.Js5MasterIndex
import net.rsprox.cache.resolver.HistoricCacheResolver
import net.rsprox.protocol.common.CoordGrid
import net.rsprox.protocol.game.incoming.model.locs.OpLoc
import net.rsprox.protocol.game.incoming.model.npcs.OpNpc
import net.rsprox.protocol.game.incoming.model.objs.OpObj
import net.rsprox.protocol.game.incoming.model.players.OpPlayer
import net.rsprox.protocol.game.incoming.model.resumed.ResumePauseButton
import net.rsprox.protocol.game.outgoing.model.IncomingZoneProt
import net.rsprox.protocol.game.outgoing.model.camera.CamReset
import net.rsprox.protocol.game.outgoing.model.info.npcinfo.NpcInfo
import net.rsprox.protocol.game.outgoing.model.info.npcinfo.extendedinfo.*
import net.rsprox.protocol.game.outgoing.model.info.playerinfo.PlayerInfo
import net.rsprox.protocol.game.outgoing.model.info.playerinfo.PlayerUpdateType
import net.rsprox.protocol.game.outgoing.model.info.playerinfo.extendedinfo.*
import net.rsprox.protocol.game.outgoing.model.info.shared.extendedinfo.*
import net.rsprox.protocol.game.outgoing.model.interfaces.*
import net.rsprox.protocol.game.outgoing.model.inv.UpdateInvFull
import net.rsprox.protocol.game.outgoing.model.inv.UpdateInvPartial
import net.rsprox.protocol.game.outgoing.model.misc.client.ServerTickEnd
import net.rsprox.protocol.game.outgoing.model.misc.player.MessageGame
import net.rsprox.protocol.game.outgoing.model.misc.player.RunClientScript
import net.rsprox.protocol.game.outgoing.model.misc.player.UpdateStatV2
import net.rsprox.protocol.game.outgoing.model.sound.MidiJingle
import net.rsprox.protocol.game.outgoing.model.sound.MidiSongV2
import net.rsprox.protocol.game.outgoing.model.sound.SynthSound
import net.rsprox.protocol.game.outgoing.model.varp.VarpLarge
import net.rsprox.protocol.game.outgoing.model.varp.VarpSmall
import net.rsprox.protocol.game.outgoing.model.zone.header.UpdateZonePartialEnclosed
import net.rsprox.protocol.game.outgoing.model.zone.payload.*
import net.rsprox.proxy.binary.BinaryBlob
import net.rsprox.proxy.cache.StatefulCacheProvider
import net.rsprox.proxy.config.BINARY_PATH
import net.rsprox.proxy.config.FILTERS_DIRECTORY
import net.rsprox.proxy.config.SETTINGS_DIRECTORY
import net.rsprox.proxy.filters.DefaultPropertyFilterSetStore
import net.rsprox.proxy.huffman.HuffmanProvider
import net.rsprox.proxy.plugin.DecoderLoader
import net.rsprox.proxy.plugin.DecodingSession
import net.rsprox.proxy.settings.DefaultSettingSetStore
import net.rsprox.proxy.util.NopSessionMonitor
import net.rsprox.shared.StreamDirection
import net.rsprox.transcriber.state.Inventory
import net.rsprox.transcriber.state.Player
import net.rsprox.transcriber.state.SessionState
import net.rsprox.transcriber.state.SessionTracker
import net.rsprox.transcriber.text.TextServerPacketTranscriber.Stat
import java.io.File
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

@Suppress("DuplicatedCode")
public class BinaryToCodeCommand : CliktCommand(name = "tocode") {
    private val name by option("-name")
    private var indent = ""

    /*
         * | Id | Client Angle |  Direction |
 * |:--:|:------------:|:----------:|
 * |  0 |      768     | North-West |
 * |  1 |     1024     |    North   |
 * |  2 |     1280     | North-East |
 * |  3 |      512     |    West    |
 * |  4 |     1536     |    East    |
 * |  5 |      256     | South-West |
 * |  6 |       0      |    South   |
 * |  7 |     1792     | South-East |
     */
    override fun run() {
        val decoderLoader = DecoderLoader()
        HuffmanProvider.load()
        val provider = StatefulCacheProvider(HistoricCacheResolver())
        val filters = DefaultPropertyFilterSetStore.load(FILTERS_DIRECTORY)
        val settings = DefaultSettingSetStore.load(SETTINGS_DIRECTORY)
        val fileTreeWalk =
            BINARY_PATH
                .toFile()
                .walkTopDown()
                .filter { it.extension == "bin" }
                .map { it.toPath() }
                .map { it to BinaryBlob.decode(it, filters, settings) }
                .sortedBy { it.second.header.revision }
        for ((path, blob) in fileTreeWalk) {
            if (path.nameWithoutExtension == "prince-ali-rescue-full-20250514T133541-0ddf543") {
                simpleTranscribe(path, blob, decoderLoader, provider)
                break
            }
        }
        println("==== Dialogues ====")
        for (dialogue in dialogues) {
            var next: Dialogue? = dialogue
            while (next != null) {
                println(next.print(1))
                next = next.next
            }
            println("==============")
        }
    }

    private fun simpleTranscribe(
        binaryPath: Path,
        binary: BinaryBlob,
        decoderLoader: DecoderLoader,
        statefulCacheProvider: StatefulCacheProvider,
    ) {
        statefulCacheProvider.update(
            Js5MasterIndex.trimmed(
                binary.header.revision,
                binary.header.js5MasterIndex,
            ),
        )
        decoderLoader.load(statefulCacheProvider)
        val latestPlugin = decoderLoader.getDecoder(binary.header.revision)
        val session = DecodingSession(binary, latestPlugin)
        val sessionState = SessionState(binary.header.revision, DefaultSettingSetStore(binaryPath))
        val sessionTracker =
            SessionTracker(
                sessionState,
                statefulCacheProvider.get(),
                NopSessionMonitor,
            )
        var tick = 0
        for ((direction, prot, packet) in session.sequence()) {
            if (tick != 0) {
                packetToCode(sessionState, packet)
            }
            when (direction) {
                StreamDirection.CLIENT_TO_SERVER -> {
                    sessionTracker.onClientPacket(packet, prot)
                    sessionTracker.beforeTranscribe(packet)
                    sessionTracker.afterTranscribe(packet)
                }
                StreamDirection.SERVER_TO_CLIENT -> {
                    sessionTracker.onServerPacket(packet, prot)
                    sessionTracker.beforeTranscribe(packet)
                    sessionTracker.afterTranscribe(packet)
                }
            }

            if (packet is ServerTickEnd) {
                tick++
                println("Tick [$tick]")
                indent = "    "
//                if (tick == 100) {
//                    break
//                }
            }
        }
        echo("Binary file decoded ${binaryPath.nameWithoutExtension}")
    }

    private sealed class Dialogue {
        abstract var text: String
        var next: Dialogue? = null
        abstract fun print(indent: Int): String
        val actions = mutableListOf<String>()
        fun StringBuilder.indent(indent: Int) {
            for (action in actions) {
                append(" ".repeat(indent * 4))
                appendLine(action)
            }
            append(" ".repeat(indent * 4))
        }
    }

    private data class Statement(
        override var text: String = "",
        var clickToContinue: Boolean = false,
    ) : Dialogue() {
        override fun print(indent: Int) = buildString {
            indent(indent)
            append("statement(\"")
            append(text.replace("<br>", " "))
            append("\"")
            if (!clickToContinue) {
                append(", clickToContinue = false")
            }
            append(")")
        }
    }

    private data class NpcChat(
        override var text: String = "",
        var npc: Int = -1,
        var animation: Int = -1,
        var name: String = "",
        var clickToContinue: Boolean = false,
    ) : Dialogue() {
        override fun print(indent: Int) = buildString {
            indent(indent)
            append("npc")
            if (animation != -1) {
                append("<")
                append(dialogueAnim(animation))
                append(">")
            }
            append("(\"")
            append(text.replace("<br>", " "))
            append("\"")
            if (!clickToContinue) {
                append(", clickToContinue = false")
            }
            append(")")
        }
    }

    private data class PlayerChat(
        override var text: String = "",
        var animation: Int = -1,
        var name: String = "",
        var clickToContinue: Boolean = false,
    ) : Dialogue() {
        override fun print(indent: Int) = buildString {
            indent(indent)
            append("player")
            if (animation != -1) {
                append("<")
                append(dialogueAnim(animation))
                append(">")
            }
            append("(\"")
            append(text.replace("<br>", " "))
            append("\"")
            if (!clickToContinue) {
                append(", clickToContinue = false")
            }
            append(")")
        }
    }

    private data class ItemBox(override var text: String = "", var item: Int = -1, var zoom: Int = -1) : Dialogue() {
        override fun print(indent: Int) = buildString {
            indent(indent)
            append("item(\"")
            append(itemId(item))
            append("\", ")
            append(zoom)
            append(", \"")
            append(text.replace("<br>", " "))
            append("\") // ")
            append(item)
        }
    }

    private data class MakeAmount(
        override var text: String = "",
        val items: MutableList<Item> = mutableListOf(),
    ) : Dialogue() {
        data class Item(val name: String, val id: Int)

        override fun print(indent: Int) = buildString {
            indent(indent)
            append("makeAmount(text = \"")
            append(text)
            append("\", items = listOf(")
            var first = true
            for (item in items) {
                if (item.id == -1) {
                    continue
                }
                if (!first) {
                    append(", ")
                }
                append("\"")
                append(itemId(item.id))
                append("\"")
                first = false
            }
            append(")) // ${items.filter { it.id != -1 }.map { it.id }.joinToString(", ")}")
        }
    }

    private data object EndDialogue : Dialogue() {
        override var text: String = "End"
        override fun print(indent: Int) = ""
    }

    private data class DoubleItemBox(
        override var text: String = "",
        var item1: Int = -1,
        var item2: Int = -1,
        var zoom1: Int = -1,
        var zoom2: Int = -1,
    ) : Dialogue() {
        override fun print(indent: Int) = buildString {
            indent(indent)
            append("items(\"")
            append(itemId(item1))
            append("\", \"")
            append(itemId(item2))
            append("\", \"")
            append(text.replace("<br>", " "))
            append("\") // $item1 $zoom1 $item2 $zoom2")
        }
    }

    private data class Choice(override var text: String = "", val options: MutableList<String> = mutableListOf()) : Dialogue() {

        override fun print(indent: Int) = buildString {
            indent(indent)
            append("choice")
            if (text != "Select an option") {
                append("(\"${text}\")")
            }
            appendLine(" {")
            for (option in options) {
                append(" ".repeat((1 + indent) * 4))
                append("option")
                var next = optionDialogues[option]
                val visited = optionVisited.contains(option)
                optionVisited.add(option)
                if (next != null && next is PlayerChat && next.text == option) {
                    append("<")
                    append(dialogueAnim(next.animation))
                    append(">")
                    next = next.next
                }
                append("(\"")
                append(option)
                append("\")")
                if (next != null && next != EndDialogue && !visited) {
                    appendLine(" {")
                    while (next != null && next != EndDialogue) {
                        appendLine(next.print(indent + 2))
                        next = next.next
                    }
                    append(" ".repeat((1 + indent) * 4))
                    appendLine("}")
                } else {
                    appendLine()
                }
            }
            append(" ".repeat(indent * 4))
            append("}")
        }
    }

    private var root: Dialogue? = null
    private var choice: Int? = null
    private var previous: Dialogue? = null
    private var dialogue: Dialogue? = null
    private val actions = mutableListOf<String>()
    private val ignoredVariables = setOf(
        1021, // toplevel_temp
        3078, // date_minutes
        2673, // skillmulti_previousselection
        277, // dragonresist
        1575, // inferno_temp_noprotect_transmit
    )

    private fun packetToCode(sessionState: SessionState, packet: IncomingMessage) {
        when (packet) {
            is OpLoc -> println("objectOperate(\"${packet.op}\", \"${objectId(packet.id)}\") {} // x = ${packet.x}, y = ${packet.z}, option = ${packet.op} id = ${packet.id}")
            is OpObj -> println("floorItemOperate(id = \"${itemId(packet.id)}\", x = ${packet.x}, y = ${packet.z}, option = ${packet.op}) // ${packet.id}")
            is OpPlayer -> {
                val player = sessionState.getPlayer(packet.index)
                println("playerOperate(tile = ${coordToTile(player.coord)}, option = ${packet.op})")
            }
            is OpNpc -> {
                val npc = sessionState.getActiveWorld().getNpc(packet.index)
                println("npcOperate(id = \"${npcId(npc.id)}\", tile = ${coordToTile(npc.coord)}, control = ${packet.controlKey}, option = ${packet.op}) // ${npc.id}")
            }
            // interfaces
            // inventory
            is UpdateInvFull -> {
                val inventory = sessionState.inventories.getOrPut(packet.inventoryId) { Inventory() }
                println("${indent}player.inventory(\"${invId(packet.inventoryId)}\").apply {")
                indent = "        "
                for ((i, update) in packet.objs.withIndex()) {
                    println(inventory.items[i])
                    println("${indent}set($i, \"${itemId(update.id)}\", ${update.count}) // ${update.id}")
                }
                indent = "    "
                println("${indent}}")
            }
            is UpdateInvPartial -> {
                val inventory = sessionState.inventories.getOrPut(packet.inventoryId) { Inventory() }
                println("${indent}player.inventory(\"${invId(packet.inventoryId)}\").apply {")
                indent = "        "
                for (update in packet.objs) {
                    val invName = when (packet.inventoryId) {
                        93 -> "inventory"
                        94 -> "equipment"
                        else -> invId(packet.inventoryId)
                    }
                    val before = inventory.items[update.slot]?.id
                    if (update.id == -1 && before == null) {
                        actions.add("player.$invName.clear(${update.slot})")
                    } else if (update.id == -1 && before != null) {
                        actions.add("player.$invName.remove(\"${itemId(before)}\") // $before")
                    } else if (before != null && update.count == 1) {
                        actions.add("player.$invName.replace(\"${itemId(before)}\", \"${itemId(update.id)}\") // $before, ${update.id}")
                    } else {
                        actions.add("player.$invName.add(\"${itemId(update.id)}\"${if (update.count > 1) ", ${update.count}" else ""}) // ${update.id}")
                    }
                    println("${indent}set(${update.slot}, \"${itemId(update.id)}\", ${update.count}) // ${update.id}")
                }
                indent = "    "
                println("${indent}}")
            }
            // player
            is MessageGame -> println("${indent}player.message(\"${packet.message}\", type = ${messageType(packet.type)}${if (packet.name != null) ", name = ${packet.name}" else ""})")
            is RunClientScript -> {
                when (packet.id) {
                    58 -> {
                        if (dialogue == null) {
                            createDialogue(219)
                        }
                        (dialogue as Choice).text = packet.values[0] as String
                        for (option in packet.values[1].toString().split("|")) {
                            (dialogue as Choice).options.add(option)
                        }
                    }
                    2046 -> { // skillmulti_setup
                        if (dialogue == null) {
                            createDialogue(270)
                        }
                        val text = packet.values[1] as String
                        val parts = text.split("|")
                        (dialogue as MakeAmount).text = parts[0]
                        for (i in 1 until parts.size) {
                            val option = parts[i]
                            val id = packet.values[2 + i] as Int
                            (dialogue as MakeAmount).items.add(MakeAmount.Item(option, id))
                        }
                    }
                    else -> {
                        if (packet.id == 948) {
                            if (packet.values[1] == 255) {
                                actions.add("player.open(\"fade_in\")")
                            } else if (packet.values[3] == 255) {
                                actions.add("player.open(\"fade_out\")")
                            }
                        }
                        println(
                            "${indent}player.sendScript(\"${scriptId(packet.id)}\"${
                                if (packet.types.isEmpty()) "" else
                                    packet.types.mapIndexed { index, type ->
                                        when (type) {
                                            's' -> "\"${packet.values[index]}\""
                                            'l' -> "${packet.values[index]}.toLong()"
                                            else -> packet.values[index].toString()
                                        }
                                    }.joinToString(", ", ", ")
                            }) // ${packet.id}"
                        )
                    }
                }
            }
            is PlayerInfo -> handlePlayerInfo(sessionState, packet)
            is NpcInfo -> {
//                for ((key, update) in packet.updates) {
//                    if (update is NpcUpdateType) {
//                        println(update)
//                    }
//                }
            }
            // camera
            is CamReset -> println("${indent}player.clearCamera()")
            // varp
            is VarpSmall -> if (packet.id != 3077 && packet.id != 3076 && packet.id != 3079 && packet.id != 1042) {
                val action = "player[\"${varpId(packet.id)}\"] = ${packet.value} // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}"
                println("${indent}$action")
                if (!ignoredVariables.contains(packet.id)) {
                    actions.add(action)
                }
            }
            is VarpLarge -> if (packet.id != 3077 && packet.id != 3076 && packet.id != 3079 && packet.id != 1042) {
                val action = "player[\"${varpId(packet.id)}\"] = ${packet.value} // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}"
                println("${indent}$action")
                if (!ignoredVariables.contains(packet.id)) {
                    actions.add(action)
                }
            }
            // sound
            is MidiJingle -> println("${indent}player.jingle(\"${jingleId(packet.id)}\") // ${packet.id}")
            is MidiSongV2 -> println("${indent}player.midi(\"${packet.id}\", fadeInDelay = ${packet.fadeInDelay}, fadeInSpeed = ${packet.fadeInSpeed}, fadeOutDelay = ${packet.fadeOutDelay}, fadeOutSpeed = ${packet.fadeOutSpeed})")
            is SynthSound -> {
                val action = "player.sound(\"${soundId(packet.id)}\"${if (packet.delay == 0) "" else ", delay = ${packet.delay}"}${if (packet.loops == 1) "" else ", loops = ${packet.loops}"}) // ${packet.id}"
                println("${indent}$action")
                actions.add(action)
            }
            is UpdateZonePartialEnclosed -> {
                val zoneX = packet.zoneX
                val zoneY = packet.zoneZ
                val level = packet.level
                if (packet.packets.isNotEmpty()) {
                    println("$indent // zone update ($zoneX, $zoneY, ${packet.level})")
                }
                for (child in packet.packets) {
                    zonePackets(child, zoneX, zoneY, level)
                }
            }
            is UpdateStatV2 -> {
                val oldXp = sessionState.getExperience(packet.stat)
                val skill = Stat.entries.first { it.id == packet.stat }
                val skillName = skill.prettyName.first().uppercase() + skill.prettyName.drop(1)
                if (packet.currentLevel != packet.invisibleBoostedLevel) {
                    println("${indent}player.levels.set(Skill.${skillName}, ${packet.currentLevel}) // invis: ${packet.invisibleBoostedLevel}")
                }
                if (packet.experience - (oldXp ?: 0) != 0) {
                    println("${indent}player.exp(Skill.${skillName}, ${packet.experience - (oldXp ?: 0)})")
                }
            }
            is IfSetObject -> {
                val component = componentId(packet.interfaceId, packet.componentId)
                when (component) {
                    "objectbox:item" -> {
                        (dialogue as? ItemBox)?.item = packet.obj
                        (dialogue as? ItemBox)?.zoom = packet.count
                    }
                    "objectbox_double:model1" -> {
                        (dialogue as? DoubleItemBox)?.item1 = packet.obj
                        (dialogue as? DoubleItemBox)?.zoom1 = packet.count
                    }
                    "objectbox_double:model2" -> {
                        (dialogue as? DoubleItemBox)?.item2 = packet.obj
                        (dialogue as? DoubleItemBox)?.zoom2 = packet.count
                    }
                }
            }
            is IfOpenSub -> {
                if (packet.destinationInterfaceId == 162 && packet.destinationComponentId == 566) {
                    if (!createDialogue(packet.interfaceId)) {
                        println(packet)
                    }
                } else {
                    println("    player.open(\"${interfaceId(packet.interfaceId)}\") //${packet.interfaceId}")
                }
            }
            is IfSetAnim -> {
                val component = componentId(packet.interfaceId, packet.componentId)
                when (component) {
                    "chat_left:head" -> (dialogue as NpcChat).animation = packet.anim
                    "chat_right:head" -> (dialogue as PlayerChat).animation = packet.anim
                    else -> println(packet)
                }
            }
            is IfSetText -> {
                when (val component = componentId(packet.interfaceId, packet.componentId)) {
                    // Npc
                    "chat_left:name" -> (dialogue as NpcChat).name = packet.text
                    "chat_left:continue" -> (dialogue as NpcChat).clickToContinue = packet.text == "Click here to continue"
                    "chat_left:text" -> (dialogue as NpcChat).text = packet.text
                    // Player
                    "chat_right:name" -> (dialogue as PlayerChat).name = packet.text
                    "chat_right:text" -> (dialogue as PlayerChat).text = packet.text
                    "chat_right:continue" -> (dialogue as PlayerChat).clickToContinue = packet.text == "Click here to continue"
                    // Items
                    "objectbox:text" -> (dialogue as ItemBox).text = packet.text
                    "objectbox_double:text" -> (dialogue as DoubleItemBox).text = packet.text
                    // Statements
                    "messagebox:text" -> (dialogue as Statement).text = packet.text
                    "messagebox:continue" -> (dialogue as Statement).clickToContinue = packet.text == "Click here to continue"
                    else -> if (packet.text != "Click here to continue") {
                        println("    player.interfaces.sendText(${packet.interfaceId}, ${packet.componentId}, \"${packet.text}\") // $component")
                    }
                }
            }
            is ResumePauseButton -> {
                when (val component = componentId(packet.interfaceId, packet.componentId)) {
                    "chat_right:continue", "chat_left:continue", "objectbox_double:pausebutton", "chatmenu:options", "objectbox:universe" -> {
                        choice = packet.sub
                    }
                    else -> println("Continue $component")
                }
            }
            is IfCloseSub -> {
                val component = componentId(packet.interfaceId, packet.componentId)
                when (component) {
                    "chatbox:chatmodal" -> {
                        dialogue?.next = EndDialogue
//                        println("==== Dialogue ====")
                        val dialogue = root
                        if (dialogue != null) {
                            dialogues.add(dialogue)
//                            while (dialogue != null) {
//                                println(dialogue.print(1))
//                                dialogue = dialogue.next
//                            }
                            root = null
                        }
//                        println("==== End Dialogue ====")
                    }
                }
            }
            is IfSetHide -> println("player.interfaces.sendVisibility(${packet.interfaceId}, ${packet.componentId}, ${!packet.hidden})")
            is ServerTickEnd -> {
                val current = dialogue
                // Link dialogues
                val previous = previous
                if (previous != null && current != null) {
                    if (choice == -1 || choice == 0 || choice == null) { // continue
                        if (previous !is Choice) {
                            if (previous.next == null) {
                                previous.next = current
                            }
                        } else {
                            println("Can't link choice $previous $current")
                        }
                    } else {
                        val prevChoice = previous as Choice
                        val option = prevChoice.options[choice!! - 1]
                        val existing = optionDialogues[option]
                        if (existing == null) {
                            optionDialogues[option] = current
                        }
                    }
                }
                if (current != null) {
                    current.actions.addAll(actions)
                    println("    $current")
                    this.previous = current
                    dialogue = null
                }
                if (choice != null && current == null) {
                    dialogue = null
                    previous?.next = EndDialogue
                    this.previous = null
                    root = null
                }
                choice = null
                actions.clear()
            }
            else -> {
//                println(packet)
            }
        }
    }

    private fun createDialogue(interfaceId: Int): Boolean {
        dialogue = when (interfaceId) {
            231 -> NpcChat() // chat_left
            217 -> PlayerChat() // chat_right
            219 -> Choice() // chatmenu
            11 -> DoubleItemBox() // objectbox_double
            193 -> ItemBox() // objectbox
            229 -> Statement() //messagebox
            270 -> MakeAmount() //skillmulti
            else -> return false
        }
        if (root == null) {
            root = dialogue
        }
        return true
    }

    private fun zonePackets(packet: IncomingZoneProt, zoneX: Int, zoneY: Int, level: Int) {
        when (packet) {
            is LocAddChangeV1 -> println("${indent}objects.add(\"${objectId(packet.id)}\", tile = Tile(${packet.xInZone}, ${packet.zInZone}) shape = ${packet.shape}, rotation = ${packet.rotation}) // ${packet.id}")
            is LocAddChangeV2 -> println("${indent}objects.add(\"${objectId(packet.id)}\", tile = Tile(${packet.xInZone}, ${packet.zInZone}) shape = ${packet.shape}, rotation = ${packet.rotation}) // ${packet.id}")
            is LocAnim -> println("${indent}obj.anim(\"${animationId(packet.id)}\") // Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), shape = ${packet.shape}, rotation = ${packet.rotation}, id = ${packet.id}")
            is LocDel -> println("${indent}obj.remove(Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), shape = ${packet.shape}, rotation = ${packet.rotation})")
            is MapAnim -> println("${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).animate(\"${animationId(packet.id)}\", height = ${packet.height}, delay = ${packet.delay})")
            is MapProjAnim -> println(
                "${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).shoot(${gfxId(packet.id)}, Delta(${packet.deltaX}, ${packet.deltaZ}), angle = ${packet.angle}, progress = ${packet.progress}, startTime = ${packet.startTime}, endTime = ${packet.endTime}, startHeight = ${packet.startHeight}, endHeight = ${packet.endHeight}, sourceIndex = ${packet.sourceIndex}, targetIndex = " +
                    "${packet.targetIndex}) // ${packet.id}"
            )
            is ObjAdd -> println("${indent}items.spawn(\"${itemId(packet.id)}\", ${packet.quantity}, Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), timeUntilPublic = ${packet.timeUntilPublic}, timeUntilDespawn = ${packet.timeUntilDespawn}, ownershipType = ${packet.ownershipType}, neverBecomesPublic = ${packet.neverBecomesPublic})")
            is ObjDel -> println("${indent}items.remove(\"${itemId(packet.id)}\", amount = ${packet.quantity}, tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}))")
            is SoundArea -> println("${indent}areaSound(\"${soundId(packet.id)}\", delay = ${packet.delay}, tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone})${if (packet.loops == 1) "" else ", loops = ${packet.loops}"}, radius = ${packet.radius}, size = ${packet.size})")
        }
    }

    private fun handlePlayerInfo(sessionState: SessionState, packet: PlayerInfo) {
        for ((index, update) in packet.updates) {
            when (update) {
                is PlayerUpdateType.HighResolutionIdle -> {
                    if (update.extendedInfo.isNotEmpty()) {
                        for (info in update.extendedInfo) {
                            handleExtendedInfo(sessionState, info, null)
                        }
                    }
                }
                is PlayerUpdateType.LowResolutionMovement -> {}
                is PlayerUpdateType.HighResolutionMovement -> {
                    if (index == sessionState.localPlayerIndex) {
                        val player = sessionState.getPlayer(index)
                        println("${indent}player.walkToDelay(${coordToTile(update.coord)}) // from ${coordToTile(player.coord)}")
                        for (info in update.extendedInfo) {
                            handleExtendedInfo(sessionState, info, player)
                        }
                    }
                }
                is PlayerUpdateType.HighResolutionToLowResolution -> {}
                PlayerUpdateType.LowResolutionIdle -> {}
                is PlayerUpdateType.LowResolutionToHighResolution -> {}
            }
        }
    }

    private fun coordToTile(coord: CoordGrid) = "Tile(${coord.x}, ${coord.z}${if (coord.level != 0) ", ${coord.level}" else ""})"

    private fun handleExtendedInfo(sessionState: SessionState, info: ExtendedInfo, player: Player?) {
        when (info) {
            // NPC
            is BaseAnimationSetExtendedInfo -> println("${indent}npc.transform(\"${npcId(info.runAnim ?: info.walkAnim!!)}\")")
            is BodyCustomisationExtendedInfo -> {}
            is CombatLevelChangeExtendedInfo -> {}
            is EnabledOpsExtendedInfo -> {}
            is FaceCoordExtendedInfo -> println("${indent}npc.face(${info.x}, ${info.z})")
            is HeadCustomisationExtendedInfo -> {}
            is NameChangeExtendedInfo -> {}
            is OldSpotanimExtendedInfo -> println("${indent}npc.transform(\"${npcId(info.id)}\", height = ${info.height})")
            is TransformationExtendedInfo -> println("${indent}npc.transform(\"${npcId(info.id)}\")")
            // Player
            is AppearanceExtendedInfo -> println("${indent}player.flagAppearance() // $info")
            is ChatExtendedInfo -> println("${indent}player.say(\"${info.text}\") // ${info.colour} ${info.effects} ${info.modIcon}")
            is FaceAngleExtendedInfo -> println(
                "${indent}player.face(${
                    angleToDir(info.angle)
                })"
            )
            is MoveSpeedExtendedInfo -> println("${indent}player.movementType = ${moveType(info.speed)}")
            is NameExtrasExtendedInfo -> {}
            is TemporaryMoveSpeedExtendedInfo -> println("${indent}player.temporaryMoveType = ${moveType(info.speed)}")
            // Shared
            is ExactMoveExtendedInfo -> {
                val coord = player!!.coord
                println(
                    "${indent}player.exactMoveDelay(Tile(${coord.x - info.deltaX1}, ${coord.z - info.deltaZ1}${if (coord.level == 0) "" else ", ${coord.level}"})${if (info.delay1 == 0) "" else ", startDelay = ${info.delay1}"}, delay = ${info.delay2}, direction = ${angleToDir(info.direction)}) // startDelta = Delta(${info.deltaX1}, ${info.deltaZ1}), endDelta = Delta(${info.deltaX2}, ${
                        info
                            .deltaZ2
                    })"
                )
            }
            is FacePathingEntityExtendedInfo -> {
                if (info.index == 0xFFFFFF) {
                    println("${indent}player.clearWatch()")
                } else if (info.index >= 0x10000) {
                    val player = sessionState.getPlayer(info.index - 0x10000)
                    println("${indent}player.watch(${player.name}, ${coordToTile(player.coord)})")
                } else {
                    val npc = sessionState.getActiveWorld().getNpc(info.index) ?: return
                    println("${indent}player.watch(${npcId(npc.id)}, ${coordToTile(npc.coord)}) // ${npc.id}")
                }
            }
            is HitExtendedInfo -> for (hit in info.hits) {
                println(buildString {
                    append(indent)
                    append("player.hit(type = ${hit.type}, value = ${hit.value}")
                    if (hit.soakType != -1) {
                        append(", soakType = ${hit.soakType}")
                    }
                    if (hit.soakValue != -1) {
                        append(", soakValue = ${hit.soakValue}")
                    }
                    if (hit.delay != -1) {
                        append(", delay = ${hit.delay}")
                    }
                    append(")")
                })
            }
            is SayExtendedInfo -> println("${indent}player.say(\"${info.text}\")")
            is SequenceExtendedInfo -> {
                if (info.id == 65535) {
                    println("${indent}player.clearAnim()")
                } else {
                    println("${indent}player.anim(\"${animationId(info.id)}\"${if (info.delay != 0) ", delay = ${info.delay}" else ""}) // ${info.id}")
                }
            }
            is SpotanimExtendedInfo -> for ((slot, anim) in info.spotanims) {
                println(buildString {
                    append(indent)
                    append("player.gfx(id = \"${animationId(anim.id)}\"")
                    if (anim.height != 0) {
                        append(", height = ${anim.height}")
                    }
                    if (anim.delay != 0) {
                        append(", delay = ${anim.delay}")
                    }
                    append(") // slot $slot id ${anim.id}")
                })
            }
            is TintingExtendedInfo -> {}
        }
    }

    private fun moveType(speed: Int): String {
        return when (speed) {
            0 -> "MoveType.None"
            1 -> "MoveType.Walk"
            2 -> "MoveType.Run"
            127 -> "MoveType.Teleport"
            else -> speed.toString()
        }
    }

    private fun angleToDir(angle: Int): String {
        return when (angle) {
            0 -> "Direction.SOUTH"
            512 -> "Direction.EAST"
            1024 -> "Direction.NORTH"
            1536 -> "Direction.WEST"
            else -> angle.toString()

        }
    }

    private fun messageType(type: Int): String {
        return when (type) {
            0 -> "ChatType.Game"
            2 -> "ChatType.Chat"
            4 -> "ChatType.Engine"
            5 -> "ChatType.Logout"
            27 -> "ChatType.ItemExamine"
            28 -> "ChatType.ObjectExamine"
            99 -> "ChatType.Console"
            109, 105 -> "ChatType.Filter"
            115 -> "ChatType.Broadcast"
            else -> type.toString()

        }
    }

    private companion object {
        private var dialogues = mutableSetOf<Dialogue>()
        private var optionDialogues = mutableMapOf<String, Dialogue>()
        private var optionVisited = mutableSetOf<String>()
        private val animations = loadReal("seqtypes")
        private val items = loadReal("objtypes")
        private val objects = loadReal("loctypes")
        private val graphics = loadReal("spottypes")
        private val npcs = loadReal("npctypes")
        private val scripts = loadOsrs("clientscript")
        private val sounds = loadOsrs("sound")
        private val varps = loadReal("varptypes")
        private val inventories = loadReal("invtypes")
        private val jingles = loadOsrs("jingle")
        private val interfaces = loadRealMap("iftypes")

        fun scriptId(id: Int): String = scripts.getOrDefault(id, id.toString())
        fun npcId(id: Int): String = npcs.getOrDefault(id, id.toString())
        fun objectId(id: Int): String = objects.getOrDefault(id, id.toString())
        fun animationId(id: Int): String = animations.getOrDefault(id, id.toString())
        fun gfxId(id: Int): String = graphics.getOrDefault(id, id.toString())
        fun itemId(id: Int): String = items.getOrDefault(id, id.toString())
        fun soundId(id: Int): String = sounds.getOrDefault(id, id.toString())
        fun varpId(id: Int): String = varps.getOrDefault(id, id.toString())
        fun invId(id: Int): String = inventories.getOrDefault(id, id.toString())
        fun jingleId(id: Int): String = jingles.getOrDefault(id, id.toString())
        fun componentId(id: Int, component: Int): String = interfaces.getOrDefault("$id:${component}", "$id:${component}")
        fun interfaceId(id: Int): String {
            val key = interfaces.keys.firstOrNull { it.startsWith("$id:") } ?: return id.toString()
            return interfaces[key]?.substringBefore(':') ?: id.toString()
        }

        fun dialogueAnim(id: Int): String {
            return when (id) {
                554, 555, 556, 557 -> "Quiz"
                562, 563, 564, 565 -> "RollEyes"
                567, 568, 569, 570 -> "Happy"
                571, 572, 573, 574 -> "Surprised"
                575, 576, 577, 578 -> "Uncertain"
                588, 589, 590, 591 -> "Talk"
                592, 593, 594, 595 -> "Shifty"
                596, 597, 598, 599 -> "Afraid"
                600, 601, 602, 603 -> "Drunk"
                605, 606, 607, 608 -> "Chuckle"
                609 -> "EvilLaugh"
                610, 611, 612, 613 -> "Upset"
                614, 615, 616, 617 -> "Angry"
                else -> id.toString()
            }
        }

        private fun loadQuickly(name: String): Map<Int, String> {
            val file = File("${System.getProperty("user.home")}/IdeaProjects/void/data/definitions/$name.yml")
            val map = mutableMapOf<Int, String>()
            var name = ""
            for (line in file.readLines()) {
                if (line.startsWith("  id:")) {
                    val (_, id) = line.split(": ")
                    map[id.trim().toInt()] = name
                } else if (!line.startsWith("#") && !line.startsWith(" ")) {
                    val parts = line.split(": ")
                    if (parts.size == 2) {
                        val id = if (parts[1].contains("#")) {
                            parts[1].split("#").first()
                        } else {
                            parts[1]
                        }.trim().toInt()
                        map[id] = parts[0]
                    } else {
                        name = parts[0]
                    }
                }
            }
            return map
        }

        private fun loadOsrs(name: String): Map<Int, String> {
            val file = File("${System.getProperty("user.home")}/Documents/RSPS/kris/mappings/$name.rscm")
            val map = mutableMapOf<Int, String>()
            for (line in file.readLines()) {
                if (line.isBlank()) {
                    continue
                }
                val (string, int) = line.split(":")
                map[int.toInt()] = string
            }
            return map
        }

        private fun loadReal(name: String): Map<Int, String> {
            val file = File("${System.getProperty("user.home")}/Documents/Void/data/leak-2025-04/$name.txt")
            val map = mutableMapOf<Int, String>()
            for (line in file.readLines()) {
                if (line.isBlank()) {
                    continue
                }
                val (int, string) = line.split("\t")
                map[int.toInt()] = string
            }
            return map
        }

        private fun loadRealMap(name: String): Map<String, String> {
            val file = File("${System.getProperty("user.home")}/Documents/Void/data/leak-2025-04/$name.txt")
            val map = mutableMapOf<String, String>()
            for (line in file.readLines()) {
                if (line.isBlank()) {
                    continue
                }
                val (key, string) = line.split("\t")
                map[key] = string
            }
            return map
        }
    }
}

public fun main(args: Array<String>) {
    BinaryToCodeCommand().main(args)
}
