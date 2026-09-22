package net.rsprox.proxy.cli

import com.github.ajalt.clikt.parameters.options.option
import net.rsprot.protocol.message.IncomingMessage
import net.rsprox.protocol.common.CoordGrid
import net.rsprox.protocol.game.incoming.model.locs.OpLocV1
import net.rsprox.protocol.game.incoming.model.locs.OpLocV2
import net.rsprox.protocol.game.incoming.model.locs.OpLocT
import net.rsprox.protocol.game.incoming.model.npcs.OpNpcV1
import net.rsprox.protocol.game.incoming.model.npcs.OpNpcV2
import net.rsprox.protocol.game.incoming.model.npcs.OpNpcT
import net.rsprox.protocol.game.incoming.model.objs.OpObjV1
import net.rsprox.protocol.game.incoming.model.objs.OpObjV2
import net.rsprox.protocol.game.incoming.model.objs.OpObjT
import net.rsprox.protocol.game.incoming.model.players.OpPlayer
import net.rsprox.protocol.game.incoming.model.players.OpPlayerT
import net.rsprox.protocol.game.incoming.model.resumed.ResumePauseButton
import net.rsprox.protocol.game.outgoing.model.IncomingZoneProt
import net.rsprox.protocol.game.outgoing.model.camera.CamLookAtV2
import net.rsprox.protocol.game.outgoing.model.camera.CamLookAtV3
import net.rsprox.protocol.game.outgoing.model.camera.CamMoveToV2
import net.rsprox.protocol.game.outgoing.model.camera.CamMoveToV3
import net.rsprox.protocol.game.outgoing.model.camera.CamReset
import net.rsprox.protocol.game.outgoing.model.camera.CamShake
import net.rsprox.protocol.game.outgoing.model.info.npcinfo.NpcInfo
import net.rsprox.protocol.game.outgoing.model.info.npcinfo.NpcUpdateType
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
import net.rsprox.protocol.game.outgoing.model.IncomingServerGameMessage
import net.rsprox.protocol.game.outgoing.model.zone.header.UpdateZonePartialEnclosed
import net.rsprox.protocol.game.outgoing.model.zone.payload.*
import net.rsprox.proxy.cli.BinaryToCodeCommand.NpcChat.Companion.regex
import net.rsprox.protocol.rs3.game.incoming.model.dialog.ResumePauseButton as Rs3ResumePauseButton
import net.rsprox.protocol.rs3.game.incoming.model.locs.OpLoc as Rs3OpLoc
import net.rsprox.protocol.rs3.game.incoming.model.locs.OpLocT as Rs3OpLocT
import net.rsprox.protocol.rs3.game.incoming.model.npcs.OpNpc as Rs3OpNpc
import net.rsprox.protocol.rs3.game.incoming.model.npcs.OpNpcT as Rs3OpNpcT
import net.rsprox.protocol.rs3.game.incoming.model.objs.OpObj as Rs3OpObj
import net.rsprox.protocol.rs3.game.incoming.model.objs.OpObjT as Rs3OpObjT
import net.rsprox.protocol.rs3.game.incoming.model.players.OpPlayer as Rs3OpPlayer
import net.rsprox.protocol.rs3.game.incoming.model.players.OpPlayerT as Rs3OpPlayerT
import net.rsprox.protocol.rs3.game.outgoing.model.camera.CamLookAt as Rs3CamLookAt
import net.rsprox.protocol.rs3.game.outgoing.model.camera.CamMoveTo as Rs3CamMoveTo
import net.rsprox.protocol.rs3.game.outgoing.model.camera.CamReset as Rs3CamReset
import net.rsprox.protocol.rs3.game.outgoing.model.camera.CamShake as Rs3CamShake
import net.rsprox.protocol.rs3.game.outgoing.model.info.npcinfo.NpcInfo as Rs3NpcInfo
import net.rsprox.protocol.rs3.game.outgoing.model.info.npcinfo.NpcUpdateType as Rs3NpcUpdateType
import net.rsprox.protocol.rs3.game.outgoing.model.info.npcinfo.extendedinfo.AnimationExtendedInfo as Rs3AnimationExtendedInfo
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.PlayerInfo as Rs3PlayerInfo
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.PlayerUpdateType as Rs3PlayerUpdateType
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.extendedinfo.PlayerExtendedInfo as Rs3PlayerExtendedInfo
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfCloseSub as Rs3IfCloseSub
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfMoveSub as Rs3IfMoveSub
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfOpenSub as Rs3IfOpenSub
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfOpenTop as Rs3IfOpenTop
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfSetAnim as Rs3IfSetAnim
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfSetGraphic as Rs3IfSetGraphic
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfSetHide as Rs3IfSetHide
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfSetModel as Rs3IfSetModel
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfSetObject as Rs3IfSetObject
import net.rsprox.protocol.rs3.game.outgoing.model.interfaces.IfSetText as Rs3IfSetText
import net.rsprox.protocol.rs3.game.outgoing.model.inv.UpdateInvFull as Rs3UpdateInvFull
import net.rsprox.protocol.rs3.game.outgoing.model.inv.UpdateInvPartial as Rs3UpdateInvPartial
import net.rsprox.protocol.rs3.game.outgoing.model.misc.client.TickEnd as Rs3TickEnd
import net.rsprox.protocol.rs3.game.outgoing.model.misc.player.MessageGame as Rs3MessageGame
import net.rsprox.protocol.rs3.game.outgoing.model.misc.player.UpdateStat as Rs3UpdateStat
import net.rsprox.protocol.rs3.game.outgoing.model.sound.MidiJingle as Rs3MidiJingle
import net.rsprox.protocol.rs3.game.outgoing.model.sound.MidiSong as Rs3MidiSong
import net.rsprox.protocol.rs3.game.outgoing.model.sound.SynthSound as Rs3SynthSound
import net.rsprox.protocol.rs3.game.outgoing.model.varbit.Varbit as Rs3Varbit
import net.rsprox.protocol.rs3.game.outgoing.model.varbit.VarbitLarge as Rs3VarbitLarge
import net.rsprox.protocol.rs3.game.outgoing.model.varbit.VarbitSmall as Rs3VarbitSmall
import net.rsprox.protocol.rs3.game.outgoing.model.varp.VarpLarge as Rs3VarpLarge
import net.rsprox.protocol.rs3.game.outgoing.model.varp.VarpLong as Rs3VarpLong
import net.rsprox.protocol.rs3.game.outgoing.model.varp.VarpSmall as Rs3VarpSmall
import net.rsprox.protocol.rs3.game.outgoing.model.zone.header.UpdateZonePartialEnclosed as Rs3UpdateZonePartialEnclosed
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.LocAddChange as Rs3LocAddChange
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.LocAnim as Rs3LocAnim
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.LocDel as Rs3LocDel
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.MapAnim as Rs3MapAnim
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.MapAnimV1 as Rs3MapAnimV1
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.MapAnimV2 as Rs3MapAnimV2
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.MapProjAnim as Rs3MapProjAnim
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.MapProjAnimV2 as Rs3MapProjAnimV2
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.ObjAdd as Rs3ObjAdd
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.ObjDel as Rs3ObjDel
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.SoundAreaV1 as Rs3SoundAreaV1
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.SoundAreaV2 as Rs3SoundAreaV2
import net.rsprox.proxy.cli.ConfigLoader.loadOsrs
import net.rsprox.proxy.cli.ConfigLoader.loadReal
import net.rsprox.proxy.cli.ConfigLoader.loadRealMap
import net.rsprox.proxy.cli.ConfigLoader.loadRs3
import net.rsprox.proxy.cli.ConfigLoader.loadRs3Map
import net.rsprox.transcriber.state.Inventory
import net.rsprox.transcriber.state.Player
import net.rsprox.transcriber.state.SessionState
import net.rsprox.transcriber.text.TextServerPacketTranscriber.Stat
import java.io.File

@Suppress("DuplicatedCode")
public class BinaryToCodeCommand : Transcriber(name = "tocode") {
    private val name by option("-name")
    private var indent = ""

    private val skipNpcs = false

    override fun filter(path: File): Boolean {
        return path.nameWithoutExtension == name
    }

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
        super.run()
        println("==== Dialogues ====")
        for (dialogue in dialogues) {
            var next: Dialogue? = dialogue
            while (next != null && next != EndDialogue) {
                println(next.print(1))
                next = next.next
            }
            println("==============")
        }
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
            append(text.replace("<br>", " ").replace(regex, ""))
            append("\"")
            if (!clickToContinue) {
                append(", clickToContinue = false")
            }
            append(")")
        }
        companion object {
            val regex = Regex("<p=[0-9]+>")
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
            append(text.replace("<br>", " ").replace(regex, ""))
            append("\"")
            if (!clickToContinue) {
                append(", clickToContinue = false")
            }
            append(")")
        }
    }

    private data class ItemBox(override var text: String = "", var item: Int = -1, var zoom: Int = -1, var sprite: Int = -1) : Dialogue() {
        override fun print(indent: Int) = buildString {
            indent(indent)
            append("item(")
            if (item != -1) {
                append("\"")
                append(itemId(item))
                append("\", \"")
            } else {
                append("\"")
            }
            append(text.replace("<br>", " "))
            append("\"")
            if (sprite != -1) {
                append(", sprite = $sprite")
            }
            append(") // ")
            if (item != -1) {
                append(item)
            }
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

    override fun processPacket(tick: Int, sessionState: SessionState, packet: IncomingMessage) {
        when (packet) {
            is OpNpcT -> {
                val npc = sessionState.getActiveWorld().getNpc(packet.index)
                println("itemOnNpcOperate(\"${itemId(packet.selectedObj)}\", \"${npcId(npc.id)}\") {}")
                resetDialogue()
            }
            is OpObjT -> {
                println("itemOnFloorItemOperate(\"${itemId(packet.selectedObj)}\", \"${objectId(packet.id)}\") {} // x = ${packet.x}, y = ${packet.z} id = ${packet.id}")
                resetDialogue()
            }
            is OpLocT -> {
                println("itemOnObjectOperate(\"${itemId(packet.selectedObj)}\", \"${objectId(packet.id)}\") {} // x = ${packet.x}, y = ${packet.z} id = ${packet.id}")
                resetDialogue()
            }
            is OpPlayerT -> {
                println("itemOnPlayerOperate(\"${itemId(packet.selectedObj)}\") {}")
                resetDialogue()
            }
            is OpLocV1 -> {
                println("objectOperate(\"${packet.op}\", \"${objectId(packet.id)}\") {} // x = ${packet.x}, y = ${packet.z}, option = ${packet.op} id = ${packet.id}")
                resetDialogue()
            }
            is OpObjV1 -> {
                println("floorItemOperate(id = \"${itemId(packet.id)}\", x = ${packet.x}, y = ${packet.z}, option = ${packet.op}) // ${packet.id}")
                resetDialogue()
            }
            is OpLocV2 -> {
                println("objectOperate(\"${packet.op}\", \"${objectId(packet.id)}\") {} // x = ${packet.x}, y = ${packet.z}, option = ${packet.op} id = ${packet.id}")
                resetDialogue()
            }
            is OpObjV2 -> {
                println("floorItemOperate(id = \"${itemId(packet.id)}\", x = ${packet.x}, y = ${packet.z}, option = ${packet.op}) // ${packet.id}")
                resetDialogue()
            }
            is OpPlayer -> {
                val player = sessionState.getPlayer(packet.index)
                println("playerOperate(tile = ${coordToTile(player.coord)}, option = ${packet.op})")
                resetDialogue()
            }
            is OpNpcV1 -> {
                val npc = sessionState.getActiveWorld().getNpc(packet.index)
                println("npcOperate(id = \"${npcId(npc.id)}\", tile = ${coordToTile(npc.coord)}, control = ${packet.controlKey}, option = ${packet.op}) // ${npc.id}")
                resetDialogue()
            }
            is OpNpcV2 -> {
                val npc = sessionState.getActiveWorld().getNpc(packet.index)
                println("npcOperate(id = \"${npcId(npc.id)}\", tile = ${coordToTile(npc.coord)}, control = ${packet.controlKey}, option = ${packet.op}) // ${npc.id}")
                resetDialogue()
            }
            // interfaces
            // inventory
            is UpdateInvFull -> {
                val inventory = sessionState.inventories.getOrPut(packet.inventoryId) { Inventory() }
                println("${indent}inventory(\"${invId(packet.inventoryId)}\").apply {")
                indent = "        "
                for ((i, update) in packet.objs.withIndex()) {
                    println("${indent}set($i, \"${itemId(update.id)}\", ${update.count}) // ${update.id}")
                }
                indent = "    "
                println("${indent}}")
            }
            is UpdateInvPartial -> {
                val inventory = sessionState.inventories.getOrPut(packet.inventoryId) { Inventory() }
                println("${indent}inventory(\"${invId(packet.inventoryId)}\").apply {")
                indent = "        "
                for (update in packet.objs) {
                    val invName = when (packet.inventoryId) {
                        93 -> "inventory"
                        94 -> "equipment"
                        else -> invId(packet.inventoryId)
                    }
                    val before = inventory.items[update.slot]?.id
                    if (update.id == -1 && before == null) {
                        actions.add("$invName.clear(${update.slot})")
                    } else if (update.id == -1 && before != null) {
                        actions.add("$invName.remove(\"${itemId(before)}\") // $before")
                    } else if (before != null && before != -1 && update.count == 1) {
                        actions.add("$invName.replace(\"${itemId(before)}\", \"${itemId(update.id)}\") // $before, ${update.id}")
                    } else {
                        actions.add("$invName.add(\"${itemId(update.id)}\"${if (update.count > 1) ", ${update.count}" else ""}) // ${update.id}")
                    }
                    println("${indent}set(${update.slot}, \"${itemId(update.id)}\", ${update.count}) // ${update.id}")
                }
                indent = "    "
                println("${indent}}")
            }
            // player
            is MessageGame -> println("${indent}message(\"${packet.message}\", type = ${messageType(packet.type)}${if (packet.name != null) ", name = ${packet.name}" else ""})")
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
                    5589 -> { // rs3 choice_v2 setup
                        if (dialogue == null) {
                            createRs3Dialogue(1188)
                        }
                        val count = packet.values[1] as Int
                        (dialogue as Choice).text = packet.values[0] as String
                        for (i in 0 until count) {
                            (dialogue as Choice).options.add(packet.values[2 + i] as String)
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
                                actions.add("open(\"fade_in\")")
                            } else if (packet.values[3] == 255) {
                                actions.add("open(\"fade_out\")")
                            }
                        }
                        println(
                            "${indent}sendScript(\"${scriptId(packet.id)}\"${
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
                for ((key, update) in packet.updates) {
                    val npc = sessionState.getActiveWorld().getNpc(key)
                    if (update is NpcUpdateType.Active) {
                        if (skipNpcs || update.extendedInfo.isEmpty()) continue
                        for (info in update.extendedInfo) {
                            when (info) {
                                is SequenceExtendedInfo -> println("${indent}npc.anim(\"${animationId(info.id)}\"${if (info.delay != 0) ", delay = ${info.delay}" else ""}) // ${npc.name} ${npc.id} ${info.id}")
                                is SpotanimExtendedInfo -> for (spot in info.spotanims.values) {
                                    println("${indent}npc.gfx(\"${gfxId(spot.id)}\"${if (spot.delay != 0) ", delay = ${spot.delay}" else ""}${if (spot.height != 0) ", height = ${spot.height}" else ""}) // ${npc.name} ${npc.id} ${spot.id}")
                                }
                                is FacePathingEntityExtendedInfo -> {
                                    val face = sessionState.getPlayerOrNull(info.index) ?: sessionState.getActiveWorld().getNpcOrNull(info.index)
                                    println("${indent}npc.face(${face}) // ${npc.name} ${npc.id}")
                                }
                                is SayExtendedInfo -> println("${indent}npc.say(\"${info.text}\") // ${npc.name} ${npc.id}")
                                is HitExtendedInfo -> {
                                    for (hit in info.hits) {
                                        println(
                                            "${indent}npc.hit(damage = ${hit.value}, offensiveType = \"${
                                                when (hit.type) {
                                                    else -> hit.type
                                                }
                                            }\") // ${npc.name} ${npc.id}"
                                        )
                                    }
                                }
//                                    else -> println("$npc - ${info}")
                            }
                        }
                    }
                }
            }
            // camera
            is CamLookAtV2 -> println("${indent}turnCamera(tile = Tile(${packet.x}, ${packet.z}), height = ${packet.height}, speed = ${packet.rate}, acceleration = ${packet.rate2})")
            is CamLookAtV3 -> println("${indent}turnCamera(tile = Tile(${packet.x}, ${packet.z}), height = ${packet.height}, speed = ${packet.rate}, acceleration = ${packet.rate2})")
            is CamMoveToV2 -> println("${indent}moveCamera(tile = Tile(${packet.x}, ${packet.z}), height = ${packet.height}, speed = ${packet.rate}, acceleration = ${packet.rate2})")
            is CamMoveToV3 -> println("${indent}moveCamera(tile = Tile(${packet.x}, ${packet.z}), height = ${packet.height}, speed = ${packet.rate}, acceleration = ${packet.rate2})")
            is CamShake -> println("${indent}shakeCamera(type = ${packet.type}, intensity = ${packet.randomAmount}, sine = ${packet.sineAmount}, frequency = ${packet.sineFrequency})")
            is CamReset -> println("${indent}clearCamera()")
            // varp
            is VarpSmall -> if (packet.id != 3077 && packet.id != 3076 && packet.id != 3079 && packet.id != 1042) {
                val action = "set(\"${varpId(packet.id)}\", ${packet.value}) // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}"
                println("${indent}$action")
                if (!ignoredVariables.contains(packet.id)) {
                    actions.add(action)
                }
            }
            is VarpLarge -> if (packet.id != 3077 && packet.id != 3076 && packet.id != 3079 && packet.id != 1042) {
                val action = "set(\"${varpId(packet.id)}\", ${packet.value}) // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}"
                println("${indent}$action")
                if (!ignoredVariables.contains(packet.id)) {
                    actions.add(action)
                }
            }
            // sound
            is MidiJingle -> println("${indent}jingle(\"${jingleId(packet.id)}\") // ${packet.id}")
            is MidiSongV2 -> println("${indent}midi(\"${packet.id}\", fadeInDelay = ${packet.fadeInDelay}, fadeInSpeed = ${packet.fadeInSpeed}, fadeOutDelay = ${packet.fadeOutDelay}, fadeOutSpeed = ${packet.fadeOutSpeed})")
            is SynthSound -> {
                val action = "sound(\"${soundId(packet.id)}\"${if (packet.delay == 0) "" else ", delay = ${packet.delay}"}${if (packet.loops == 1) "" else ", loops = ${packet.loops}"}) // ${packet.id}"
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
                    println("${indent}levels.set(Skill.${skillName}, ${packet.currentLevel}) // invis: ${packet.invisibleBoostedLevel}")
                }
                if (packet.experience - (oldXp ?: 0) != 0) {
                    println("${indent}exp(Skill.${skillName}, ${packet.experience - (oldXp ?: 0)})")
                }
            }
            is IfSetModelV1 -> {
                val component = componentId(packet.interfaceId, packet.componentId)
                println("interfaces.sendModel(\"$component\", ${packet.model}) // ${packet.interfaceId}:${packet.componentId}")
            }
            is IfSetModelV2 -> {
                val component = componentId(packet.interfaceId, packet.componentId)
                println("interfaces.sendModel(\"$component\", ${packet.model}) // ${packet.interfaceId}:${packet.componentId}")
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
                    else -> println(packet)
                }
            }
            is IfOpenSub -> {
                if (packet.destinationInterfaceId == 162 && (packet.destinationComponentId == 566 || packet.destinationComponentId == 567)) {
                    if (!createDialogue(packet.interfaceId)) {
                        println(packet)
                    }
                } else {
                    println("    open(\"${interfaceId(packet.interfaceId)}\") //${packet.interfaceId}")
                }
            }
            is IfSetAnim -> {
                val component = componentId(packet.interfaceId, packet.componentId)
                when (component) {
                    "chat_left:head" -> (dialogue as? NpcChat)?.animation = packet.anim
                    "chat_right:head" -> (dialogue as? PlayerChat)?.animation = packet.anim
                    else -> println(packet)
                }
            }
            is IfSetText -> {
                when (val component = componentId(packet.interfaceId, packet.componentId)) {
                    // Npc
                    "chat_left:name" -> (dialogue as? NpcChat)?.name = packet.text
                    "chat_left:continue" -> (dialogue as? NpcChat)?.clickToContinue = packet.text == "Click here to continue"
                    "chat_left:text" -> (dialogue as? NpcChat)?.text = packet.text
                    // Player
                    "chat_right:name" -> (dialogue as? PlayerChat)?.name = packet.text
                    "chat_right:text" -> (dialogue as? PlayerChat)?.text = packet.text
                    "chat_right:continue" -> (dialogue as? PlayerChat)?.clickToContinue = packet.text == "Click here to continue"
                    // Items
                    "objectbox:text" -> (dialogue as? ItemBox)?.text = packet.text
                    "objectbox_double:text" -> (dialogue as? DoubleItemBox)?.text = packet.text
                    // Statements
                    "messagebox:text" -> (dialogue as? Statement)?.text = packet.text
                    "messagebox:continue" -> (dialogue as? Statement)?.clickToContinue = packet.text == "Click here to continue"
                    else -> {
                        if (packet.text != "Click here to continue") {
                            println("    interfaces.sendText(${packet.interfaceId}, ${packet.componentId}, \"${packet.text}\") // $component")
                        }
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
//                            dialogues.add(dialogue)
//                            while (dialogue != null) {
//                                println(dialogue.print(1))
//                                dialogue = dialogue.next
//                            }
                            root = null
                        }
//                        println("==== End Dialogue ====")
                    }
                }
                println("    close(\"${interfaceId(packet.interfaceId)}\") //${packet.interfaceId}")
            }
            is IfSetHide -> println("interfaces.sendVisibility(${packet.interfaceId}, ${packet.componentId}, ${!packet.hidden})")
            is ServerTickEnd -> linkDialogueAndEndTick(tick)
            // ==== RS3 ====
            is Rs3OpNpcT -> {
                val npc = sessionState.getActiveWorld().getNpcOrNull(packet.index)
                println("itemOnNpcOperate(\"${itemId(packet.selectedObj, rs3 = true)}\", \"${npcId(npc?.id ?: -1, rs3 = true)}\") {}")
                resetDialogue()
            }
            is Rs3OpObjT -> {
                println("itemOnFloorItemOperate(\"${itemId(packet.selectedObj, rs3 = true)}\", \"${objectId(packet.id, rs3 = true)}\") {} // x = ${packet.x}, y = ${packet.z} id = ${packet.id}")
                resetDialogue()
            }
            is Rs3OpLocT -> {
                println("itemOnObjectOperate(\"${itemId(packet.selectedObj, rs3 = true)}\", \"${objectId(packet.id, rs3 = true)}\") {} // x = ${packet.x}, y = ${packet.z} id = ${packet.id}")
                resetDialogue()
            }
            is Rs3OpPlayerT -> {
                println("itemOnPlayerOperate(\"${itemId(packet.selectedObj, rs3 = true)}\") {}")
                resetDialogue()
            }
            is Rs3OpLoc -> {
                println("objectOperate(\"${packet.op}\", \"${objectId(packet.id, rs3 = true)}\") {} // x = ${packet.x}, y = ${packet.y}, option = ${packet.op} id = ${packet.id}")
                resetDialogue()
            }
            is Rs3OpObj -> {
                println("floorItemOperate(id = \"${itemId(packet.id, rs3 = true)}\", x = ${packet.x}, y = ${packet.y}, option = ${packet.op}) // ${packet.id}")
                resetDialogue()
            }
            is Rs3OpPlayer -> {
                val player = sessionState.getPlayerOrNull(packet.index)
                println("playerOperate(tile = ${if (player != null) coordToTile(player.coord) else "Tile(?, ?)"}, option = ${packet.op})")
                resetDialogue()
            }
            is Rs3OpNpc -> {
                val npc = sessionState.getActiveWorld().getNpcOrNull(packet.index)
                println("npcOperate(id = \"${npcId(npc?.id ?: -1, rs3 = true)}\", tile = ${if (npc != null) coordToTile(npc.coord) else "Tile(?, ?)"}, option = ${packet.op}) // ${npc?.id}")
                resetDialogue()
            }
            is Rs3ResumePauseButton -> {
                val iface = packet.combinedId ushr 16
                val comp = packet.combinedId and 0xFFFF
                val component = componentId(iface, comp, rs3 = true)
                val optionIndex = component.removePrefix("choice_v2:option_").toIntOrNull()
                if (optionIndex != null) {
                    // The option number is encoded in the clicked component itself, not packet.sub.
                    choice = optionIndex
                } else if (component in setOf(
                        "chat_v2_left:click_continue",
                        "objbox_v2:click_continue",
                        "chat_v2_right:click_continue",
                        "object_choice:button_graphics_2",
                        "makex2012:make_click",
                        "confirm_destroy_v2:button_all",
                        "mesbox_v2:click_continue",
                    )
                ) {
                    choice = 0
                } else {
                    println("Continue $component")
                }
            }
            // inventory
            is Rs3UpdateInvFull -> {
                println("${indent}inventory(\"${invId(packet.inventoryId)}\").apply {")
                indent = "        "
                for ((i, obj) in packet.objs.withIndex()) {
                    println("${indent}set($i, \"${itemId(obj.id, rs3 = true)}\", ${obj.count}) // ${obj.id}")
                }
                indent = "    "
                println("${indent}}")
            }
            is Rs3UpdateInvPartial -> {
                println("${indent}inventory(\"${invId(packet.inventoryId, rs3 = true)}\").apply {")
                indent = "        "
                for (obj in packet.objs) {
                    println("${indent}set(${obj.slot}, \"${itemId(obj.id, rs3 = true)}\", ${obj.count}) // ${obj.id}")
                }
                indent = "    "
                println("${indent}}")
            }
            // player
            is Rs3MessageGame -> println("${indent}message(\"${packet.message}\", type = ${packet.type}${if (packet.sender != null) ", name = ${packet.sender}" else ""})")
            is Rs3UpdateStat -> {
                val oldXp = sessionState.getExperience(packet.skillId)
                println("${indent}levels.set(${packet.skillId}, ${packet.level})")
                if (packet.xp - (oldXp ?: 0) != 0) {
                    println("${indent}exp(${packet.skillId}, ${packet.xp - (oldXp ?: 0)})")
                }
                sessionState.setExperience(packet.skillId, packet.xp)
            }
            is Rs3PlayerInfo -> handleRs3PlayerInfo(sessionState, packet)
            is Rs3NpcInfo -> {
                val world = sessionState.getActiveWorld()
                for ((index, update) in packet.updates) {
                    when (update) {
                        is Rs3NpcUpdateType.Add -> {
                            if (world.getNpcOrNull(index) == null) {
                                world.createNpc(index, update.id, null, 0, update.direction, CoordGrid(update.level, update.x, update.z))
                            }
                        }
                        is Rs3NpcUpdateType.Active -> {
                            val npc = world.getNpcOrNull(index)
                            world.updateNpc(index, CoordGrid(update.level, update.x, update.z))
                            if (skipNpcs || update.extendedInfo.isEmpty() || npc == null) continue
                            for (info in update.extendedInfo) {
                                when (info) {
                                    is Rs3AnimationExtendedInfo -> println("${indent}npc.anim(\"${animationId(info.animId, rs3 = true)}\") // ${npc.name} ${npc.id} ${info.animId}")
                                    else -> {}
                                }
                            }
                        }
                        Rs3NpcUpdateType.Remove -> world.removeNpc(index)
                        Rs3NpcUpdateType.Idle -> {}
                    }
                }
            }
            // camera
            is Rs3CamLookAt -> println("${indent}turnCamera(tile = Tile(${packet.localX}, ${packet.localZ}), height = ${packet.height}, speed = ${packet.speed}, acceleration = ${packet.accel})")
            is Rs3CamMoveTo -> println("${indent}moveCamera(tile = Tile(${packet.localX}, ${packet.localZ}), height = ${packet.height}, speed = ${packet.speed}, acceleration = ${packet.accel})")
            is Rs3CamShake -> println("${indent}shakeCamera(type = ${packet.axis}, intensity = ${packet.randomAmplitude}, sine = ${packet.sineAmplitude}, frequency = ${packet.frequency})")
            is Rs3CamReset -> println("${indent}clearCamera()")
            // varp / varbit
            is Rs3VarpSmall -> println("${indent}set(\"${varpId(packet.id, rs3 = true)}\", ${packet.value}) // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}")
            is Rs3VarpLarge -> println("${indent}set(\"${varpId(packet.id, rs3 = true)}\", ${packet.value}) // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}")
            is Rs3VarpLong -> println("${indent}set(\"${varpId(packet.id, rs3 = true)}\", ${packet.value}) // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}")
            is Rs3Varbit -> println("${indent}set(\"varbit_${packet.id}\", ${packet.value}) // https://chisel.weirdgloop.org/varbs/display?varbit=${packet.id}")
            is Rs3VarbitSmall -> println("${indent}set(\"varbit_${packet.id}\", ${packet.value}) // https://chisel.weirdgloop.org/varbs/display?varbit=${packet.id}")
            is Rs3VarbitLarge -> println("${indent}set(\"varbit_${packet.id}\", ${packet.value}) // https://chisel.weirdgloop.org/varbs/display?varbit=${packet.id}")
            // sound
            is Rs3MidiJingle -> println("${indent}jingle(\"${jingleId(packet.song)}\") // ${packet.song}")
            is Rs3MidiSong -> println("${indent}midi(\"${packet.id}\", volume = ${packet.volume})")
            is Rs3SynthSound -> {
                val action = "sound(\"${soundId(packet.id, rs3 = true)}\"${if (packet.delay == 0) "" else ", delay = ${packet.delay}"}${if (packet.loops == 1) "" else ", loops = ${packet.loops}"}) // ${packet.id}"
                println("${indent}$action")
                actions.add(action)
            }
            is Rs3UpdateZonePartialEnclosed -> {
                val zoneX = packet.zoneX
                val zoneY = packet.zoneZ
                val level = packet.level
                if (packet.packets.isNotEmpty()) {
                    println("$indent // zone update ($zoneX, $zoneY, $level)")
                }
                for (child in packet.packets) {
                    rs3ZonePackets(child, zoneX, zoneY, level)
                }
            }
            // interfaces / dialogues
            is Rs3IfSetModel -> {
                println("interfaces.sendModel(\"${rs3Component(packet.componentHash)}\", ${packet.modelId})")
            }
            is Rs3IfSetObject -> {
                val (iface, comp) = rs3ComponentIds(packet.componentHash)
                when (val component = componentId(iface, comp, rs3 = true)) {
//                    "objectbox:item" -> {
//                        (dialogue as? ItemBox)?.item = packet.objId
//                        (dialogue as? ItemBox)?.zoom = packet.count
//                    }
//                    "objectbox_double:model1" -> {
//                        (dialogue as? DoubleItemBox)?.item1 = packet.objId
//                        (dialogue as? DoubleItemBox)?.zoom1 = packet.count
//                    }
//                    "objectbox_double:model2" -> {
//                        (dialogue as? DoubleItemBox)?.item2 = packet.objId
//                        (dialogue as? DoubleItemBox)?.zoom2 = packet.count
//                    }
                    else -> println("interfaces.sendObject(\"${component}\", \"${itemId(packet.objId, rs3 = true)}\", ${packet.count}) // ${packet.objId}")
                }
            }
            is Rs3IfSetAnim -> {
                val (iface, comp) = rs3ComponentIds(packet.componentHash)
                when (val component = componentId(iface, comp, rs3 = true)) {
                    "chat_v2_left:chathead_1" -> { pendingRs3Anim[component] = packet.animId; (dialogue as? NpcChat)?.animation = packet.animId }
                    "chat_v2_right:chathead_1" -> { pendingRs3Anim[component] = packet.animId; (dialogue as? PlayerChat)?.animation = packet.animId }
                    else -> println("interfaces.sendAnim(\"${component}\", \"${animationId(packet.animId, rs3 = true)}\") // ${packet.animId}")
                }
            }
            is Rs3IfSetGraphic -> {
                val (iface, comp) = rs3ComponentIds(packet.componentHash)
                when (val component = componentId(iface, comp, rs3 = true)) {
                    "objbox_v2:graphic_box" -> { pendingRs3Sprite[component] = packet.graphicId }
                    else -> println("interfaces.sendSprite(\"${component.substringBefore(":")}\", \"${component.substringAfter(":")}\", ${packet.graphicId}")
                }
            }
            is Rs3IfSetText -> {
                val (iface, comp) = rs3ComponentIds(packet.componentHash)
                when (val component = componentId(iface, comp, rs3 = true)) {
                    // Npc
                    "chat_v2_left:title_text" -> { pendingRs3Text[component] = packet.text; (dialogue as? NpcChat)?.name = packet.text }
                    "chat_v2_left:click_continue" -> { pendingRs3Text[component] = packet.text }
                    "chat_v2_left:chat_text" -> { pendingRs3Text[component] = packet.text; (dialogue as? NpcChat)?.text = packet.text }
                    // Player
                    "chat_v2_right:title_text" -> { pendingRs3Text[component] = packet.text; (dialogue as? PlayerChat)?.name = packet.text }
                    "chat_v2_right:chat_text" -> { pendingRs3Text[component] = packet.text; (dialogue as? PlayerChat)?.text = packet.text }
                    "chat_v2_right:click_continue" -> { pendingRs3Text[component] = packet.text }
                    // Items
                    "objbox_v2:objbox_text" -> { pendingRs3Text[component] = packet.text; (dialogue as? ItemBox)?.text = packet.text }
//                    "objectbox_double:text" -> (dialogue as? DoubleItemBox)?.text = packet.text
                    // Statements
                    "mesbox_v2:mesbox_text" -> { pendingRs3Text[component] = packet.text; (dialogue as? Statement)?.text = packet.text }
                    "mesbox_v2:click_continue" -> { pendingRs3Text[component] = packet.text }
                    else -> println("interfaces.sendText(\"${component}\", \"${packet.text}\")")
                }
            }
            is Rs3IfSetHide -> {
                val (id, comp) = rs3ComponentIds(packet.componentHash)
                val component = componentId(id, comp, rs3 = true)
                when (component) {
                    "chat_v2_left:click_continue_button" -> pendingRs3Cont[component] = !packet.hidden
                    "chat_v2_right:click_continue_button" -> pendingRs3Cont[component] = !packet.hidden
                    "mesbox_v2:click_continue" -> pendingRs3Cont[component] = !packet.hidden
                    "objbox_v2:click_continue_button" -> {
            //                    (dialogue as? ItemBox)?.clickToContinue = !packet.hidden
                    }
                }
                println("interfaces.sendVisibility(\"${componentId(id, comp, rs3 = true)}\", ${!packet.hidden})")
            }
            is Rs3IfOpenSub -> {
                if (!createRs3Dialogue(packet.childId)) {
                    println("open(\"${interfaceId(packet.childId, rs3 = true)}\") // ${packet.childId} in ${rs3Component(packet.componentHash)}")
                }
            }
            is Rs3IfOpenTop -> println("openTop(\"${interfaceId(packet.interfaceId, rs3 = true)}\") // ${packet.interfaceId}")
            is Rs3IfCloseSub -> {
                val (id, comp) = rs3ComponentIds(packet.parentComponentHash)
                val component = componentId(id, comp)
                if (component == "chatbox:chatmodal") {
                    dialogue?.next = EndDialogue
                    root = null
                }
                println("close(\"${componentId(id, comp, rs3 = true)}\") // ${id}:$comp")
            }
            is Rs3IfMoveSub -> {
                val (id, comp) = rs3ComponentIds(packet.source)
                val (id2, comp2) = rs3ComponentIds(packet.destination)
                println("move(\"${componentId(id, comp, rs3 = true)}\", \"${componentId(id2, comp2, rs3 = true)}\")")
            }
            is Rs3TickEnd -> linkDialogueAndEndTick(tick)
            else -> {
//                println(packet)
            }
        }
    }

    private fun handleRs3PlayerInfo(sessionState: SessionState, packet: Rs3PlayerInfo) {
        for ((index, update) in packet.updates) {
            when (update) {
                is Rs3PlayerUpdateType.LowResolutionToHighResolution -> {
                    sessionState.overridePlayer(Player(index, sessionState.getLastKnownPlayerName(index) ?: "unknown", CoordGrid(update.level, update.x, update.z)))
                    for (info in update.extendedInfo) {
                        handleRs3ExtendedInfo(info, null)
                    }
                }
                is Rs3PlayerUpdateType.HighResolutionIdle -> {
                    for (info in update.extendedInfo) {
                        handleRs3ExtendedInfo(info, null)
                    }
                }
                is Rs3PlayerUpdateType.HighResolutionMovement -> {
                    if (index == sessionState.localPlayerIndex) {
                        val player = sessionState.getPlayerOrNull(index)
                        println("${indent}walkToDelay(Tile(${update.x}, ${update.z}${if (update.level != 0) ", ${update.level}" else ""})) // from ${if (player != null) coordToTile(player.coord) else "Tile(?, ?)"}")
                        for (info in update.extendedInfo) {
                            handleRs3ExtendedInfo(info, player)
                        }
                    }
                    sessionState.overridePlayer(
                        Player(
                            index,
                            sessionState.getPlayerOrNull(index)?.name ?: sessionState.getLastKnownPlayerName(index) ?: "unknown",
                            CoordGrid(update.level, update.x, update.z),
                        ),
                    )
                }
                else -> {
                    // LowResolutionMovement, HighResolutionToLowResolution: no extended info to preload
                }
            }
        }
    }

    private fun handleRs3ExtendedInfo(info: Rs3PlayerExtendedInfo, player: Player?) {
        when (info) {
            is Rs3PlayerExtendedInfo.Sequence -> {
                val id = info.ids.firstOrNull { it != -1 }
                if (id == null) {
                    println("${indent}clearAnim()")
                } else {
                    println("${indent}anim(\"${animationId(id)}\"${if (info.delay != 0) ", delay = ${info.delay}" else ""}) // $id")
                }
            }
            is Rs3PlayerExtendedInfo.SayV1 -> println("${indent}say(\"${info.text}\")")
            is Rs3PlayerExtendedInfo.SayV2 -> println("${indent}say(\"${info.text}\")")
            is Rs3PlayerExtendedInfo.FaceAngle -> println("${indent}face(${info.angle}) // rs3 angle units")
            is Rs3PlayerExtendedInfo.FaceEntity -> {
                if (info.target == 0xFFFF || info.target == -1) {
                    println("${indent}clearWatch()")
                } else {
                    println("${indent}watch(${info.target})")
                }
            }
            is Rs3PlayerExtendedInfo.ExactMove -> {
                val coord = player?.coord ?: return
                println(
                    "${indent}exactMoveDelay(Tile(${coord.x - info.deltaX1}, ${coord.z - info.deltaZ1}${if (coord.level == 0) "" else ", ${coord.level}"})${if (info.delay1 == 0) "" else ", startDelay = ${info.delay1}"}, delay = ${info.delay2}) // startDelta = Delta(${info.deltaX1}, ${info.deltaZ1}), endDelta = Delta(${info.deltaX2}, ${info.deltaZ2})",
                )
            }
            is Rs3PlayerExtendedInfo.Hits -> for (hit in info.hits) {
                println(
                    buildString {
                        append(indent)
                        append("hit(type = ${hit.type}, value = ${hit.value}")
                        if (hit.secondaryValue != -1) {
                            append(", soakType = ${hit.secondaryType}, soakValue = ${hit.secondaryValue}")
                        }
                        if (hit.delay != -1) {
                            append(", delay = ${hit.delay}")
                        }
                        append(")")
                    },
                )
            }
            is Rs3PlayerExtendedInfo.Spotanims -> for (spot in info.additions) {
                println("${indent}gfx(id = \"${animationId(spot.id)}\") // slot ${spot.slot} id ${spot.id}")
            }
            is Rs3PlayerExtendedInfo.Appearance -> println("${indent}flagAppearance() // ${info.name}")
            else -> {}
        }
    }

    private fun rs3Component(hash: Long): String {
        val packed = hash.toInt()
        return componentId(packed ushr 16, packed and 0xFFFF)
    }

    private fun rs3ComponentIds(hash: Long): Pair<Int, Int> {
        val packed = hash.toInt()
        return (packed ushr 16) to (packed and 0xFFFF)
    }

    /** RS3 counterpart of [createDialogue], driven by [Rs3DialogueConfig] instead of hardcoded ids. */
    private fun createRs3Dialogue(interfaceId: Int): Boolean {
        dialogue = when (interfaceId) {
            1184 -> NpcChat() // chat_v2_left
            1191 -> PlayerChat() // chat_v2_right
            1188 -> Choice() // choice_v2
            11 -> DoubleItemBox() // objectbox_double (unconfirmed rs3 id)
            1189 -> ItemBox() // objbox_v2
            1186 -> Statement() // mesbox_v2
            1370 -> MakeAmount() // makex2012
            else -> return false
        }
        if (root == null) {
            dialogues.add(dialogue!!)
            root = dialogue
        }
        applyPendingRs3DialogueFields()
        return true
    }

    private val pendingRs3Text = mutableMapOf<String, String>()
    private val pendingRs3Anim = mutableMapOf<String, Int>()
    private val pendingRs3Cont = mutableMapOf<String, Boolean>()
    private val pendingRs3Sprite = mutableMapOf<String, Int>()

    /**
     * RS3 sends [Rs3IfSetText]/[Rs3IfSetAnim] (and other component setters) for a
     * sub-interface's components *before* the [Rs3IfOpenSub] that actually opens it,
     * unlike OSRS. Values are buffered here and re-applied once the matching [Dialogue]
     * is created.
     */
    private fun applyPendingRs3DialogueFields() {
        when (val d = dialogue) {
            is NpcChat -> {
                pendingRs3Text["chat_v2_left:title_text"]?.let { d.name = it }
                pendingRs3Text["chat_v2_left:chat_text"]?.let { d.text = it }
                pendingRs3Cont["chat_v2_right:click_continue_button"]?.let { d.clickToContinue = it }
                pendingRs3Anim["chat_v2_left:chathead_1"]?.let { d.animation = it }
            }
            is PlayerChat -> {
                pendingRs3Text["chat_v2_right:title_text"]?.let { d.name = it }
                pendingRs3Text["chat_v2_right:chat_text"]?.let { d.text = it }
                pendingRs3Cont["chat_v2_right:click_continue_button"]?.let { d.clickToContinue = it }
                pendingRs3Anim["chat_v2_right:chathead_1"]?.let { d.animation = it }
            }
            is ItemBox -> {
                pendingRs3Text["objbox_v2:objbox_text"]?.let { d.text = it }
                pendingRs3Sprite["objbox_v2:graphic_box"]?.let { d.sprite = it }
            }
            is Statement -> {
                pendingRs3Text["mesbox_v2:mesbox_text"]?.let { d.text = it }
                pendingRs3Cont["mesbox_v2:click_continue"]?.let { d.clickToContinue = it }
            }
            else -> {}
        }
    }

    /** Shared by OSRS's ServerTickEnd and RS3's TickEnd: links the just-finished dialogue into the tree and advances the tick print. */
    private fun linkDialogueAndEndTick(tick: Int) {
        val current = dialogue
        // Link dialogues
        val previous = previous
        if (previous != null && current != null) {
            if (previous is Choice && choice != null && choice != -1 && choice != 0 && choice!! - 1 in previous.options.indices) {
                val option = previous.options[choice!! - 1]
                val existing = optionDialogues[option]
                if (existing == null) {
                    optionDialogues[option] = current
                }
            } else if (previous !is Choice) {
                if (previous.next == null) {
                    previous.next = current
                }
            } else {
                println("Can't link choice $previous $current")
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
        println("Tick [$tick]")
    }

    private fun rs3ZonePackets(packet: IncomingServerGameMessage, zoneX: Int, zoneY: Int, level: Int) {
        when (packet) {
            is Rs3LocAddChange -> println("${indent}objects.add(\"${objectId(packet.locId, rs3 = true)}\", tile = Tile(${packet.xInZone}, ${packet.zInZone}) shape = ${packet.shape}, rotation = ${packet.rotation}) // ${packet.locId}")
            is Rs3LocAnim -> println("${indent}obj.anim(\"${animationId(packet.id, rs3 = true)}\") // Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), shape = ${packet.shape}, rotation = ${packet.rotation}, id = ${packet.id}")
            is Rs3LocDel -> println("${indent}obj.remove(Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), shape = ${packet.shape}, rotation = ${packet.rotation})")
            is Rs3MapAnim -> println("${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).animate(\"${animationId(packet.id, rs3 = true)}\", height = ${packet.height}, delay = ${packet.delay}) // ${packet.id}")
            is Rs3MapAnimV1 -> println("${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).animate(\"${animationId(packet.id, rs3 = true)}\", height = ${packet.height}, delay = ${packet.delay}) // ${packet.id}")
            is Rs3MapAnimV2 -> println("${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).animate(\"${animationId(packet.id, rs3 = true)}\", height = ${packet.height}, delay = ${packet.delay}) // ${packet.id}")
            is Rs3MapProjAnim -> println(
                "${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).shoot(${gfxId(packet.id, rs3 = true)}, curve = ${packet.angle}, sizeOffset = ${packet.progress}, startTime = ${packet.startTime}, endTime = ${packet.endTime}, startHeight = ${packet.startHeight}, endHeight = ${packet.endHeight}, targetIndex = ${packet.target}) // ${packet.id}",
            )
            is Rs3MapProjAnimV2 -> println(
                "${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).shoot(${gfxId(packet.id, rs3 = true)}, curve = ${packet.angle}, sizeOffset = ${packet.progress}, startTime = ${packet.startTime}, endTime = ${packet.endTime}, startHeight = ${packet.startHeight}, endHeight = ${packet.endHeight}, targetIndex = ${packet.target}) // ${packet.id}",
            )
            is Rs3ObjAdd -> println("${indent}items.spawn(\"${itemId(packet.objId, rs3 = true)}\", ${packet.count}, Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}))")
            is Rs3ObjDel -> println("${indent}items.remove(\"${itemId(packet.objId, rs3 = true)}\", tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}))")
            is Rs3SoundAreaV1 -> println("${indent}areaSound(\"${soundId(packet.id, rs3 = true)}\", delay = ${packet.delay}, tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone})${if (packet.loops == 1) "" else ", loops = ${packet.loops}"}, radius = ${packet.range}) // ${packet.id}")
            is Rs3SoundAreaV2 -> println("${indent}areaSound(\"${soundId(packet.id, rs3 = true)}\", delay = ${packet.delay}, tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone})${if (packet.loops == 1) "" else ", loops = ${packet.loops}"}, radius = ${packet.range}) // ${packet.id}")
            else -> {}
        }
    }

    private fun resetDialogue() {
        previous = null
        dialogue = null
        root = null
        choice = null
        actions.clear()
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
            dialogues.add(dialogue!!)
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
            is MapAnim -> println("${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).animate(\"${animationId(packet.id)}\", height = ${packet.height}, delay = ${packet.delay}) // ${packet.id}")
            is MapProjAnimV1 -> println(
                "${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).shoot(${gfxId(packet.id)}, Delta(${packet.deltaX}, ${packet.deltaZ}), curve = ${packet.angle}, sizeOffset = ${packet.progress}, startTime = ${packet.startTime}, endTime = ${packet.endTime}, startHeight = ${packet.startHeight}, endHeight = ${packet.endHeight}, sourceIndex = ${packet.sourceIndex}, targetIndex = " +
                    "${packet.targetIndex}) // ${packet.id}"
            )
            is MapProjAnimV2 -> println(
                "${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).shoot(${gfxId(packet.id)}, sourceIndex = ${packet.sourceIndex}, targetIndex = ${packet.targetIndex}, curve = ${packet.angle}, sizeOffset = ${packet.progress}, startTime = ${packet.startTime}, endTime = ${packet.endTime}, startHeight = ${packet.startHeight}, endHeight = ${packet.endHeight}, sourceIndex = ${packet.sourceIndex}, " +
                    "targetIndex = " +
                    "${packet.targetIndex}) // ${packet.id}"
            )
            is ObjAdd -> println("${indent}items.spawn(\"${itemId(packet.id)}\", ${packet.quantity}, Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), timeUntilPublic = ${packet.timeUntilPublic}, timeUntilDespawn = ${packet.timeUntilDespawn}, ownershipType = ${packet.ownershipType}, neverBecomesPublic = ${packet.neverBecomesPublic})")
            is ObjDel -> println("${indent}items.remove(\"${itemId(packet.id)}\", amount = ${packet.quantity}, tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}))")
            is SoundArea -> println("${indent}areaSound(\"${soundId(packet.id)}\", delay = ${packet.delay}, tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone})${if (packet.loops == 1) "" else ", loops = ${packet.loops}"}, radius = ${packet.radius}, size = ${packet.size}) // ${packet.id}")
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
                        println("${indent}walkToDelay(${coordToTile(update.coord)}) // from ${coordToTile(player.coord)}")
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
            is AppearanceExtendedInfo -> println("${indent}flagAppearance() // $info")
            is ChatExtendedInfo -> println("${indent}say(\"${info.text}\") // ${info.colour} ${info.effects} ${info.modIcon}")
            is FaceAngleExtendedInfo -> println(
                "${indent}face(${
                    angleToDir(info.angle)
                })"
            )
            is MoveSpeedExtendedInfo -> println("${indent}movementType = ${moveType(info.speed)}")
            is NameExtrasExtendedInfo -> {}
            is TemporaryMoveSpeedExtendedInfo -> println("${indent}temporaryMoveType = ${moveType(info.speed)}")
            // Shared
            is ExactMoveExtendedInfo -> {
                val coord = player?.coord ?: return
                println(
                    "${indent}exactMoveDelay(Tile(${coord.x - info.deltaX1}, ${coord.z - info.deltaZ1}${if (coord.level == 0) "" else ", ${coord.level}"})${if (info.delay1 == 0) "" else ", startDelay = ${info.delay1}"}, delay = ${info.delay2}, direction = ${angleToDir(info.direction)}) // startDelta = Delta(${info.deltaX1}, ${info.deltaZ1}), endDelta = Delta(${info.deltaX2}, ${
                        info
                            .deltaZ2
                    })"
                )
            }
            is FacePathingEntityExtendedInfo -> {
                if (info.index == 0xFFFFFF) {
                    println("${indent}clearWatch()")
                } else if (info.index >= 0x10000) {
                    val player = sessionState.getPlayerOrNull(info.index - 0x10000) ?: return
                    println("${indent}watch(${player.name}, ${coordToTile(player.coord)})")
                } else {
                    val npc = sessionState.getActiveWorld().getNpc(info.index) ?: return
                    println("${indent}watch(${npcId(npc.id)}, ${coordToTile(npc.coord)}) // ${npc.id}")
                }
            }
            is HitExtendedInfo -> for (hit in info.hits) {
                println(buildString {
                    append(indent)
                    append("hit(type = ${hit.type}, value = ${hit.value}")
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
            is SayExtendedInfo -> println("${indent}say(\"${info.text}\")")
            is SequenceExtendedInfo -> {
                if (info.id == 65535) {
                    println("${indent}clearAnim()")
                } else {
                    println("${indent}anim(\"${animationId(info.id)}\"${if (info.delay != 0) ", delay = ${info.delay}" else ""}) // ${info.id}")
                }
            }
            is SpotanimExtendedInfo -> for ((slot, anim) in info.spotanims) {
                println(buildString {
                    append(indent)
                    append("gfx(id = \"${animationId(anim.id)}\"")
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
        private val animations = loadReal("seqtypes", "leak-2025-04")
        private val items = loadReal("objtypes", "leak-2025-04")
        private val objects = loadReal("loctypes", "leak-2025-04")
        private val graphics = loadReal("spottypes", "leak-2025-04")
        private val npcs = loadReal("npctypes", "leak-2025-04")
        private val scripts = loadOsrs("clientscript")
        private val sounds = loadOsrs("sound")
        private val varps = loadReal("varptypes", "leak-2025-04")
        private val inventories = loadReal("invtypes", "leak-2025-04")
        private val interfaces = loadRealMap("iftypes", "leak-2025-04")
        private val jingles = loadOsrs("jingle")

        private val rs3Animations = loadRs3("seq")
        private val rs3Items = loadRs3("obj")
        private val rs3Objects = loadRs3("loc")
        private val rs3Npcs = loadRs3("npc")
        private val rs3Sounds = loadRs3("sound")
        private val rs3Varps = loadRs3("var_player")
        private val rs3Inventories = loadRs3("inv")
        private val rs3Components = loadRs3Map("component")
        private val rs3Interfaces = loadRs3("interface")
        private val rs3Graphics = loadRs3("graphic")

        fun scriptId(id: Int, rs3: Boolean = false): String = scripts.getOrDefault(id, id.toString())
        fun npcId(id: Int, rs3: Boolean = false): String = (if (rs3) rs3Npcs else npcs).getOrDefault(id, id.toString())
        fun objectId(id: Int, rs3: Boolean = false): String = (if (rs3) rs3Objects else objects).getOrDefault(id, id.toString())
        fun animationId(id: Int, rs3: Boolean = false): String = (if (rs3) rs3Animations else animations).getOrDefault(id, id.toString())
        fun gfxId(id: Int, rs3: Boolean = false): String = (if (rs3) rs3Graphics else graphics).getOrDefault(id, id.toString())
        fun itemId(id: Int, rs3: Boolean = false): String = (if (rs3) rs3Items else items).getOrDefault(id, id.toString())
        fun soundId(id: Int, rs3: Boolean = false): String = (if (rs3) rs3Sounds else sounds).getOrDefault(id, id.toString())
        fun varpId(id: Int, rs3: Boolean = false): String = (if (rs3) rs3Varps else varps).getOrDefault(id, id.toString())
        fun invId(id: Int, rs3: Boolean = false): String = (if (rs3) rs3Inventories else inventories).getOrDefault(id, id.toString())
        fun jingleId(id: Int): String = jingles.getOrDefault(id, id.toString())
        fun componentId(id: Int, component: Int, rs3: Boolean = false): String {
            val interfaces = if (rs3) rs3Components else interfaces
            return interfaces.getOrDefault("$id:${component}", "$id:${component}")
        }

        fun interfaceId(id: Int, rs3: Boolean = false): String {
            if (rs3) {
                return rs3Interfaces.getOrDefault(id, id.toString())
            }
            val key = interfaces.keys.firstOrNull { it.startsWith("$id:") } ?: return id.toString()
            return interfaces[key]?.substringBefore(':') ?: id.toString()
        }

        fun dialogueAnim(id: Int): String {
            return when (id) {
                // rs3
                9827, 9828, 9829, 9830, 37930 -> "Quiz"
                9831, 9832, 9833, 9834 -> "Bored"
                9847, 9848, 9849, 9850 -> "Happy"
                9746, 9747, 9748, 9749 -> "Shock"
                9811, 9812, 9813, 9814 -> "Confused"
                9807, 9808, 9809, 9810, 37902 -> "Neutral"
                9836, 9837, 9838, 9839 -> "Shifty"
                9773, 9774, 9775, 9776 -> "Scared"
                9757, 9758, 9759, 9760 -> "Disheartened"
                9843, 9844, 9845, 9846, 37904 -> "Pleased"
                9835 -> "Drunk"
                9840 -> "Laugh"
                9842 -> "EvilLaugh"
                9761, 9762, 9763, 9764, 37911 -> "Sad"
                9785, 9786, 9787, 9788 -> "Angry"
                9781, 9782, 9783, 9784 -> "Frustrated"
                // osrs
                554, 555, 556, 557 -> "Quiz"
                562, 563, 564, 565 -> "Bored"
                567, 568, 569, 570 -> "Happy"
                571, 572, 573, 574 -> "Shock"
                575, 576, 577, 578 -> "Confused"
                588, 589, 590, 591 -> "Neutral"
                592, 593, 594, 595 -> "Shifty"
                596, 597, 598, 599 -> "Scared"
                600, 601, 602, 603 -> "Drunk"
                605, 606, 607, 608 -> "Laugh"
                609 -> "EvilLaugh"
                610, 611, 612, 613 -> "Sad"
                614, 615, 616, 617 -> "Angry"
                585 -> "TreeHappy"
                584 -> "TreeTalk"
                else -> id.toString()
            }
        }
    }
}

public fun main() {
    BinaryToCodeCommand().main(
        arrayOf(
            "-name",
            "20260921T160241-0ddf543"
        )
    )
}
