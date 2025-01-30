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
import net.rsprox.protocol.game.outgoing.model.IncomingZoneProt
import net.rsprox.protocol.game.outgoing.model.camera.CamReset
import net.rsprox.protocol.game.outgoing.model.info.npcinfo.extendedinfo.*
import net.rsprox.protocol.game.outgoing.model.info.playerinfo.PlayerInfo
import net.rsprox.protocol.game.outgoing.model.info.playerinfo.PlayerUpdateType
import net.rsprox.protocol.game.outgoing.model.info.playerinfo.extendedinfo.*
import net.rsprox.protocol.game.outgoing.model.info.shared.extendedinfo.*
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
            if (path.nameWithoutExtension == "canoes-20250130T133949-0ddf543") {
                simpleTranscribe(path, blob, decoderLoader, provider)
            }
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
        val sessionState = SessionState(DefaultSettingSetStore(binaryPath))
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
            }
        }
        echo("Binary file decoded ${binaryPath.nameWithoutExtension}")
    }

    private fun packetToCode(sessionState: SessionState, packet: IncomingMessage) {
        when (packet) {
            is OpLoc -> println("operateObject(id = \"${objectId(packet.id)}\", x = ${packet.x}, y = ${packet.z}, option = ${packet.op}) // ${packet.id}")
            is OpObj -> println("operateItem(id = \"${itemId(packet.id)}\", x = ${packet.x}, y = ${packet.z}, option = ${packet.op}) // ${packet.id}")
            is OpPlayer -> {
                val player = sessionState.getPlayer(packet.index)
                println("operatePlayer(tile = ${coordToTile(player.coord)}, option = ${packet.op})")
            }
            is OpNpc -> {
                val npc = sessionState.getActiveWorld().getNpc(packet.index)
                println("operateNPC(id = \"${npcId(npc.id)}\", tile = ${coordToTile(npc.coord)}, control = ${packet.controlKey}, option = ${packet.op}) // ${npc.id}")
            }
            // interfaces
            // inventory
            is UpdateInvFull -> {
                println("${indent}player.inventory(\"${invId(packet.inventoryId)}\").apply {")
                indent = "        "
                for ((i, update) in packet.objs.withIndex()) {
                    println("${indent}set($i, \"${itemId(update.id)}\", ${update.count}) // ${update.id}")
                }
                indent = "    "
                println("${indent}}")
            }
            is UpdateInvPartial -> {
                println("${indent}player.inventory(\"${invId(packet.inventoryId)}\").apply {")
                indent = "        "
                for (update in packet.objs) {
                    println("${indent}set(${update.slot}, \"${itemId(update.id)}\", ${update.count}) // ${update.id}")
                }
                indent = "    "
                println("${indent}}")
            }
            // player
            is MessageGame -> println("${indent}player.message(\"${packet.message}\", type = ${messageType(packet.type)}${if (packet.name != null) ", name = ${packet.name}" else ""})")
            is RunClientScript -> println(
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
            is PlayerInfo -> handlePlayerInfo(sessionState, packet)
            // camera
            is CamReset -> println("${indent}player.clearCamera()")
            // varp
            is VarpSmall -> if (packet.id != 3077 && packet.id != 3076 && packet.id != 3079 && packet.id != 1042) {
                println("${indent}player[\"${varpId(packet.id)}\"] = ${packet.value} // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}")
            }
            is VarpLarge -> if (packet.id != 3077 && packet.id != 3076 && packet.id != 3079 && packet.id != 1042) {
                println("${indent}player[\"${varpId(packet.id)}\"] = ${packet.value} // https://chisel.weirdgloop.org/varbs/display?varplayer=${packet.id}")
            }
            // sound
            is MidiJingle -> println("${indent}player.jingle(\"${jingleId(packet.id)}\") // ${packet.id}")
            is MidiSongV2 -> println("${indent}player.midi(\"${packet.id}\", fadeInDelay = ${packet.fadeInDelay}, fadeInSpeed = ${packet.fadeInSpeed}, fadeOutDelay = ${packet.fadeOutDelay}, fadeOutSpeed = ${packet.fadeOutSpeed})")
            is SynthSound -> println("${indent}player.playSound(\"${soundId(packet.id)}\", delay = ${packet.delay}, loops = ${packet.loops}) // ${packet.id}")
            is UpdateZonePartialEnclosed -> {
                val zoneX = packet.zoneX
                val zoneY = packet.zoneZ
                val level = packet.level
                println("$indent // zone update ($zoneX, $zoneY, ${packet.level})")
                for (child in packet.packets) {
                    zonePackets(child, zoneX, zoneY, level)
                }
            }
            is UpdateStatV2 -> {
                val oldXp = sessionState.getExperience(packet.stat)
                val skill = Stat.entries.first { it.id == packet.stat }
                val skillName = skill.prettyName.first().uppercase() + skill.prettyName.drop(1)
                if (packet.currentLevel != packet.invisibleBoostedLevel) {
                    println("${indent}player.levels.set(Skill.skillName, ${packet.currentLevel}) // invis: ${packet.invisibleBoostedLevel}")
                }
                if (packet.experience - (oldXp ?: 0) != 0) {
                    println("${indent}player.exp(Skill.${skillName}, ${packet.experience - (oldXp ?: 0)})")
                }
            }
            else -> {
//                println(packet)
            }
        }
    }

    private fun zonePackets(packet: IncomingZoneProt, zoneX: Int, zoneY: Int, level: Int) {
        when (packet) {
            is LocAddChangeV1 -> println("${indent}objects.spawn(\"${objectId(packet.id)}\", tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}) shape = ${packet.shape}, rotation = ${packet.rotation}) // ${packet.id}")
            is LocAddChangeV2 -> println("${indent}objects.spawn(\"${objectId(packet.id)}\", tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}) shape = ${packet.shape}, rotation = ${packet.rotation}) // ${packet.id}")
            is LocAnim -> println("${indent}obj.animate(\"${animationId(packet.id)}\", Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), shape = ${packet.shape}, rotation = ${packet.rotation})")
            is LocDel -> println("${indent}obj.remove(Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), shape = ${packet.shape}, rotation = ${packet.rotation})")
            is MapAnim -> println("${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).animate(\"${animationId(packet.id)}\", height = ${packet.height}, delay = ${packet.delay})")
            is MapProjAnim -> println("${indent}Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}).shoot(${gfxId(packet.id)}, Delta(${packet.deltaX}, ${packet.deltaZ}), angle = ${packet.angle}, progress = ${packet.progress}, startTime = ${packet.startTime}, endTime = ${packet.endTime}, startHeight = ${packet.startHeight}, endHeight = ${packet.endHeight}, sourceIndex = ${packet.sourceIndex}, targetIndex = ${packet.targetIndex})")
            is ObjAdd -> println("${indent}items.spawn(${itemId(packet.id)}, ${packet.quantity}, Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), timeUntilPublic = ${packet.timeUntilPublic}, timeUntilDespawn = ${packet.timeUntilDespawn}, ownershipType = ${packet.ownershipType}, neverBecomesPublic = ${packet.neverBecomesPublic})")
            is ObjDel -> println("${indent}items.remove(${itemId(packet.id)}, amount = ${packet.quantity}, tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}))")
            is SoundArea -> println("${indent}areaSound(${soundId(packet.id)}, delay = ${packet.delay}, tile = Tile(${zoneX + packet.xInZone}, ${zoneY + packet.zInZone}), loops = ${packet.loops}, radius = ${packet.radius}, size = ${packet.size})")
        }
    }

    private fun handlePlayerInfo(sessionState: SessionState, packet: PlayerInfo) {
        for ((index, update) in packet.updates) {
            when (update) {
                is PlayerUpdateType.HighResolutionIdle -> {
                    if (update.extendedInfo.isNotEmpty()) {
                        for (info in update.extendedInfo) {
                            handleExtendedInfo(sessionState, info)
                        }
                    }
                }
                is PlayerUpdateType.LowResolutionMovement -> {}
                is PlayerUpdateType.HighResolutionMovement -> {
                    val player = sessionState.getPlayer(index)
                    println("${indent}player.walkTo(${coordToTile(update.coord)}) // from ${coordToTile(player.coord)}")
                    for (info in update.extendedInfo) {
                        handleExtendedInfo(sessionState, info)
                    }
                }
                is PlayerUpdateType.HighResolutionToLowResolution -> {}
                PlayerUpdateType.LowResolutionIdle -> {}
                is PlayerUpdateType.LowResolutionToHighResolution -> {}
            }
        }
    }

    private fun coordToTile(coord: CoordGrid) = "Tile(${coord.x}, ${coord.z}${if (coord.level != 0) ", ${coord.level}" else ""})"

    private fun handleExtendedInfo(sessionState: SessionState, info: ExtendedInfo) {
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
            is ChatExtendedInfo -> println("${indent}player.forceChat = \"${info.text}\" // ${info.colour} ${info.effects} ${info.modIcon}")
            is FaceAngleExtendedInfo -> println(
                "${indent}player.face(${
                    angleToDir(info.angle)
                })"
            )
            is MoveSpeedExtendedInfo -> println("${indent}player.movementType = ${moveType(info.speed)}")
            is NameExtrasExtendedInfo -> {}
            is TemporaryMoveSpeedExtendedInfo -> println("${indent}player.temporaryMoveType = ${moveType(info.speed)}")
            // Shared
            is ExactMoveExtendedInfo -> println("${indent}player.exactMove(startDelta = Delta(${info.deltaX1}, ${info.deltaZ1}), startDelay = ${info.delay1}, endDelay = ${info.delay2}, endDelta = Delta(${info.deltaX2}, ${info.deltaZ2}), direction = ${angleToDir(info.direction)})")
            is FacePathingEntityExtendedInfo -> {
                if (info.index == 0xFFFFFF) {
                    println("${indent}player.clearWatch()")
                } else if (info.index >= 0x10000) {
                    val player = sessionState.getPlayer(info.index - 0x10000)
                    println("${indent}player.watch(${player.name}, ${coordToTile(player.coord)})")
                } else {
                    val npc = sessionState.getActiveWorld().getNpc(info.index)
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
            is SayExtendedInfo -> println("${indent}player.forceChat = \"${info.text}\"")
            is SequenceExtendedInfo -> println("${indent}player.setAnimation(\"${animationId(info.id)}\"${if (info.delay != 0) ", delay = ${info.delay}" else ""}) // ${info.id}")
            is SpotanimExtendedInfo -> for ((slot, anim) in info.spotanims) {
                println(buildString {
                    append(indent)
                    append("player.setGraphic(id = \"animationId(anim.id)\"")
                    if (anim.height != 0) {
                        append(", height = ${anim.height}")
                    }
                    if (anim.delay != 0) {
                        append(", delay = ${anim.delay}")
                    }
                    append(") // slot $slot")
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

    private val animations = loadOsrs("animation-2024-02-27")
    private val items = loadOsrs("item")
    private val objects = loadOsrs("object")
    private val graphics = loadOsrs("graphics-2024-02-27")
    private val npcs = loadOsrs("npc")
    private val scripts = loadOsrs("clientscript")
    private val sounds = loadOsrs("sound")
    private val varps = loadOsrs("varp")
    private val inventories = loadOsrs("inventory")
    private val jingles = loadOsrs("jingle")

    private fun scriptId(id: Int): String = scripts.getOrDefault(id, id.toString())
    private fun npcId(id: Int): String = npcs.getOrDefault(id, id.toString())
    private fun objectId(id: Int): String = objects.getOrDefault(id, id.toString())
    private fun animationId(id: Int): String = animations.getOrDefault(id, id.toString())
    private fun gfxId(id: Int): String = graphics.getOrDefault(id, id.toString())
    private fun itemId(id: Int): String = items.getOrDefault(id, id.toString())
    private fun soundId(id: Int): String = sounds.getOrDefault(id, id.toString())
    private fun varpId(id: Int): String = varps.getOrDefault(id, id.toString())
    private fun invId(id: Int): String = inventories.getOrDefault(id, id.toString())
    private fun jingleId(id: Int): String = jingles.getOrDefault(id, id.toString())


    private companion object {
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
    }
}

public fun main(args: Array<String>) {
    BinaryToCodeCommand().main(args)
}
