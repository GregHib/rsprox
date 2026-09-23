package net.rsprox.proxy.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.google.gson.GsonBuilder
import net.rsprox.proxy.binary.BinaryBlob
import net.rsprox.proxy.binary.isRuneScape3
import net.rsprox.proxy.cli.ConfigLoader.loadRs3
import net.rsprox.proxy.config.BINARY_PATH
import net.rsprox.proxy.config.FILTERS_DIRECTORY
import net.rsprox.proxy.config.SETTINGS_DIRECTORY
import net.rsprox.proxy.dungeon.DoorOutput
import net.rsprox.proxy.dungeon.FloorItemOutput
import net.rsprox.proxy.dungeon.FloorOutput
import net.rsprox.proxy.dungeon.NpcOutput
import net.rsprox.proxy.dungeon.NpcStatOutput
import net.rsprox.proxy.dungeon.ResourceOutput
import net.rsprox.proxy.dungeon.RoomOutput
import net.rsprox.proxy.filters.DefaultPropertyFilterSetStore
import net.rsprox.proxy.huffman.HuffmanProvider
import net.rsprox.proxy.rs3.binary.Rs3BinaryTranscriber
import net.rsprox.proxy.settings.DefaultSettingSetStore
import net.rsprox.shared.filters.PropertyFilterSetStore
import net.rsprox.shared.settings.SettingSetStore
import net.rsprox.transcriber.rs3.state.Rs3World
import net.rsprox.transcriber.state.Player
import net.rsprox.transcriber.state.SessionState
import net.rsprox.protocol.common.CoordGrid
import net.rsprox.protocol.rs3.game.incoming.model.buttons.If3Button as Rs3If3Button
import net.rsprox.protocol.rs3.game.incoming.model.locs.OpLoc as Rs3OpLoc
import net.rsprox.protocol.rs3.game.outgoing.model.info.npcinfo.NpcInfo as Rs3NpcInfo
import net.rsprox.protocol.rs3.game.outgoing.model.info.npcinfo.NpcUpdateType as Rs3NpcUpdateType
import net.rsprox.protocol.rs3.game.outgoing.model.info.npcinfo.extendedinfo.NpcExtendedInfo as Rs3NpcExtendedInfo
import net.rsprox.protocol.rs3.game.outgoing.model.info.npcinfo.extendedinfo.NpcMask as Rs3NpcMask
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.PlayerInfo as Rs3PlayerInfo
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.PlayerUpdateType as Rs3PlayerUpdateType
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.extendedinfo.PlayerExtendedInfo as Rs3PlayerExtendedInfo
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.util.PlayerInfoInitBlock as Rs3PlayerInfoInitBlock
import net.rsprox.protocol.rs3.game.outgoing.model.map.RebuildNormal as Rs3RebuildNormal
import net.rsprox.protocol.rs3.game.outgoing.model.map.RebuildRegion as Rs3RebuildRegion
import net.rsprox.protocol.rs3.game.outgoing.model.misc.client.TickEnd as Rs3TickEnd
import net.rsprox.protocol.rs3.game.outgoing.model.misc.player.MessageGame as Rs3MessageGame
import net.rsprox.protocol.rs3.game.outgoing.model.misc.player.UpdateStat as Rs3UpdateStat
import net.rsprox.protocol.rs3.game.outgoing.model.sound.MidiSong as Rs3MidiSong
import net.rsprox.protocol.rs3.game.outgoing.model.varc.VarcLarge as Rs3VarcLarge
import net.rsprox.protocol.rs3.game.outgoing.model.varc.VarcSmall as Rs3VarcSmall
import net.rsprox.protocol.rs3.game.outgoing.model.varc.VarcStrSmall as Rs3VarcStrSmall
import net.rsprox.protocol.rs3.game.outgoing.model.varp.VarpLarge as Rs3VarpLarge
import net.rsprox.protocol.rs3.game.outgoing.model.varp.VarpSmall as Rs3VarpSmall
import net.rsprox.protocol.rs3.game.outgoing.model.zone.header.UpdateZoneFullFollows as Rs3UpdateZoneFullFollows
import net.rsprox.protocol.rs3.game.outgoing.model.zone.header.UpdateZonePartialEnclosed as Rs3UpdateZonePartialEnclosed
import net.rsprox.protocol.rs3.game.outgoing.model.zone.header.UpdateZonePartialFollows as Rs3UpdateZonePartialFollows
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.LocAddChange as Rs3LocAddChange
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.LocAnim as Rs3LocAnim
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.MapAnim as Rs3MapAnim
import net.rsprox.protocol.rs3.game.outgoing.model.zone.payload.ObjAdd as Rs3ObjAdd
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

/**
 * Walks every recorded RS3 binary dump of a Daemonheim dungeon run and extracts statistics
 * useful for figuring out drop rates, resource depletion rates, room composition and
 * completion rewards: one JSON file is written per floor plus a combined `.jsonl` for
 * easy aggregation once hundreds of dumps have been recorded.
 */
public class DungeonStatisticsCommand : CliktCommand(name = "dungeonstats"), Runnable {
    private val name by option("-name")
    private val outDir by option("-out")

    private val statsDirectory: Path by lazy {
        val dir = outDir?.let { Path.of(it) } ?: BINARY_PATH.resolveSibling("dungeon-stats")
        dir.createDirectories()
        dir
    }

    private var currentFile: String = ""
    private var floorSequence: Int = 0
    private var current: DungeonFloorStats? = null

    /**
     * [current], but only while its floor is still in progress. Once [DungeonFloorStats.completed]
     * is set (see handleMidiSong) the floor keeps [current] alive so trailing reward messages/varps
     * (tokens, floor time, xp, chat) can still be attributed to it until the next floor's
     * "- Welcome to Daemonheim -" line finalizes and replaces it - but the player is teleported out
     * during that same window, and the *next* dungeon's region load, npc spawns, etc. arrive well
     * before that line. Without this, those packets were wrongly recorded onto the completed floor
     * (see the various pending* buffers, which is where this data belongs instead) rather than
     * dropped or deferred like data seen before the very first floor.
     */
    private val activeFloor: DungeonFloorStats?
        get() = current?.takeUnless { it.completed }

    private var introLines: MutableList<String>? = null
    private var lastKnownTokens: Int? = null

    /** Most recently seen level per skill, cleared per binary file (see transcribeFile). */
    private val currentLevels: MutableMap<String, Int> = mutableMapOf()
    private var world = Rs3World()
    private var currentTick: Int = 0
    private var activeSessionState: SessionState? = null

    // Per-index live NPC tracking, cleared per binary file (see transcribeFile).
    private val npcIndexToId: MutableMap<Int, Int> = mutableMapOf()
    private val npcIndexToCoord: MutableMap<Int, CoordGrid> = mutableMapOf()
    private val npcIndexToSpot: MutableMap<Int, NpcSpot> = mutableMapOf()

    /**
     * Unlike the three maps above, this is deliberately *not* cleared on Remove: an npc walking out
     * of render distance is removed and re-Added under the same index once it's back in view, and
     * without this history that re-Add would otherwise be logged as a brand new spawn. Cleared per
     * floor (see handleMessage) so a stale spot from the previous floor's instance can't be reused.
     */
    private val npcIndexHistory: MutableMap<Int, NpcSpot> = mutableMapOf()

    /** Deaths seen recently enough that a nearby ObjAdd is probably their loot, not an unrelated spawn. */
    private val recentNpcDeaths: MutableList<NpcDeath> = mutableListOf()

    /** Inventory item clicks seen recently enough that a matching ObjAdd is probably their result. */
    private val pendingPlayerActions: MutableList<PendingPlayerAction> = mutableListOf()

    /** The most recent door/obstruction OpLoc, recently enough that a player anim/gfx is its response. */
    private var pendingDoorInteraction: PendingDoorInteraction? = null

    /**
     * Source-template-tile to actual-instance-tile pairs from the RebuildRegion map-load packet,
     * non-null only when the last such packet was a real dungeon load (see [handleRoomLayout]).
     */
    private var pendingRoomOrigins: List<Pair<CoordGrid, CoordGrid>>? = null

    /**
     * Doors seen spawning before the current floor's "- Welcome to Daemonheim -" line arrives -
     * the initial region load fires well before that line, so a door in (or near) the starting
     * room is otherwise silently dropped by the `activeFloor == null` guard in [recordResourceState].
     * Applied to the next floor once it starts (see handleMessage), same as [pendingRoomOrigins].
     */
    private val pendingDoors: MutableList<PendingDoor> = mutableListOf()

    /** Same early-arrival problem as [pendingDoors], but for locked-door barrier key numbers. */
    private val pendingBarrierKeyNumbers: MutableSet<Int> = mutableSetOf()

    /** Same early-arrival problem as [pendingDoors], but for skill-gated obstructions. */
    private val pendingObstructions: MutableList<PendingObstruction> = mutableListOf()

    /**
     * The most recent room-space coordinate the local player was placed at before the current
     * floor's "- Welcome to Daemonheim -" line arrived. The player's *own* position at the moment
     * that line is read is unreliable for the same reason [pendingDoors] is needed: it can still be
     * wherever the previous RebuildNormal (e.g. the dungeoneering lobby, which isn't part of any
     * instance's coordinate mapping) left them, since the real room load can race the chat line.
     */
    private var pendingStartCoord: CoordGrid? = null

    /**
     * The resource node the local player last clicked; every xp drop in that node's skill is
     * counted as one resource gathered from it (see [handleUpdateStat]), since a single click keeps
     * gathering until the node depletes or the player does something else.
     */
    private var lastGatherSpot: ResourceSpot? = null

    /** True while decoding the standalone payload packets that follow a full-zone-snapshot header. */
    private var zoneIsFullSnapshot: Boolean = false

    override fun run() {
        HuffmanProvider.load()
        val filters = DefaultPropertyFilterSetStore.load(FILTERS_DIRECTORY)
        val settings = DefaultSettingSetStore.load(SETTINGS_DIRECTORY)
        val fileTreeWalk =
            BINARY_PATH
                .toFile()
                .walkTopDown()
                .filter { it.extension == "bin" && filter(it) }
                .map { it.toPath() }
                .map { it to BinaryBlob.decode(it, filters, settings) }
                .filter { it.second.header.isRuneScape3() }
                .sortedBy { it.second.header.revision }
        var floorsWritten = 0
        for ((path, blob) in fileTreeWalk) {
            floorsWritten += transcribeFile(path, blob, filters, settings)
        }
        echo("Wrote $floorsWritten dungeon floor stat(s) to $statsDirectory")
    }

    private fun filter(path: File): Boolean {
        val filterName = name
        return filterName == null || path.nameWithoutExtension.contains(filterName)
    }

    private fun transcribeFile(
        binaryPath: Path,
        binary: BinaryBlob,
        filters: PropertyFilterSetStore,
        settings: SettingSetStore,
    ): Int {
        currentFile = binaryPath.nameWithoutExtension
        val before = floorSequence
        world = Rs3World()
        npcIndexToId.clear()
        npcIndexToCoord.clear()
        npcIndexToSpot.clear()
        npcIndexHistory.clear()
        currentLevels.clear()
        recentNpcDeaths.clear()
        pendingPlayerActions.clear()
        pendingRoomOrigins = null
        pendingDoors.clear()
        pendingBarrierKeyNumbers.clear()
        pendingObstructions.clear()
        pendingDoorInteraction = null
        pendingStartCoord = null
        lastGatherSpot = null
        zoneIsFullSnapshot = false
        val sessionState = SessionState(binary.header.revision, DefaultSettingSetStore(binaryPath))
        sessionState.createWorld(-1)
        sessionState.setActiveWorld(-1, 0)
        activeSessionState = sessionState
        var tick = 0
        Rs3BinaryTranscriber.transcribe(binaryPath, binary, filters, settings, null) { event ->
            when (event) {
                is Rs3BinaryTranscriber.Event.LobbyTransfer -> {
                    sessionState.localPlayerIndex = event.playerIndex
                }
                is Rs3BinaryTranscriber.Event.Packet -> {
                    // Unlike Transcriber.kt, tick 0 (the initial login burst) is processed too,
                    // since it carries the varp/varc state (e.g. token count) in effect before
                    // the first dungeon floor of the dump begins.
                    processPacket(tick, sessionState, event.message)
                    if (event.message is Rs3TickEnd) {
                        tick++
                    }
                }
            }
        }
        finalizeCurrent()
        echo("Binary file decoded $currentFile")
        return floorSequence - before
    }

    private fun processPacket(tick: Int, sessionState: SessionState, packet: Any) {
        currentTick = tick
        when (packet) {
            is Rs3MessageGame -> handleMessage(tick, packet.message)
            is Rs3VarpSmall -> if (packet.id == VARP_REWARD_TOKENS) handleTokens(packet.value)
            is Rs3VarpLarge -> if (packet.id == VARP_REWARD_TOKENS) handleTokens(packet.value)
            is Rs3MidiSong -> handleMidiSong(tick, packet.id)
            is Rs3VarcSmall -> handleVarc(packet.id, packet.value)
            is Rs3VarcLarge -> handleVarc(packet.id, packet.value)
            is Rs3VarcStrSmall -> handleVarcStr(packet.id, packet.value)
            is Rs3UpdateStat -> handleUpdateStat(sessionState, packet)
            is Rs3NpcInfo -> handleNpcInfo(packet)
            is Rs3PlayerInfo -> handlePlayerInfo(sessionState, packet)
            is Rs3OpLoc -> handleOpLoc(sessionState, packet)
            is Rs3If3Button -> handleIf3Button(packet)
            is Rs3RebuildNormal -> {
                world.rebuild(packet)
                initLocalPlayer(sessionState, packet.playerInfoInit)
                pendingRoomOrigins = null
            }
            is Rs3RebuildRegion -> {
                world.rebuild(packet)
                initLocalPlayer(sessionState, packet.playerInfoInit)
                handleRoomLayout(packet)
            }
            is Rs3UpdateZoneFullFollows -> {
                world.setActiveZone(packet.zoneX, packet.zoneZ, packet.level)
                zoneIsFullSnapshot = true
            }
            is Rs3UpdateZonePartialFollows -> {
                world.setActiveZone(packet.zoneX, packet.zoneZ, packet.level)
                zoneIsFullSnapshot = false
            }
            is Rs3UpdateZonePartialEnclosed -> handleZone(packet)
            // Standalone payloads that follow one of the two zone headers above, decoded relative
            // to the zone that header just made active; the header tells us whether this content
            // is the room's initial state (full snapshot) or a later, incremental change.
            is Rs3LocAddChange -> {
                val raw = world.relativizeZoneCoord(packet.xInZone, packet.zInZone)
                recordResourceState(toRoomCoord(raw), packet.locId, world.instanceRotation(raw))
            }
            is Rs3ObjAdd -> {
                val raw = world.relativizeZoneCoord(packet.xInZone, packet.zInZone)
                recordFloorItem(
                    toRoomCoord(raw),
                    packet.objId,
                    packet.count,
                    isSnapshot = zoneIsFullSnapshot,
                    rotation = world.instanceRotation(raw),
                )
            }
            is Rs3LocAnim -> {
                if (packet.id != -1) {
                    val raw = world.relativizeZoneCoord(packet.xInZone, packet.zInZone)
                    activeFloor?.recordDoorObjectAnimation(toRoomCoord(raw), animationId(packet.id))
                }
            }
            is Rs3MapAnim -> {
                val raw = world.relativizeZoneCoord(packet.xInZone, packet.zInZone)
                activeFloor?.recordDoorObjectSpotanim(toRoomCoord(raw), gfxId(packet.id))
            }
            else -> {}
        }
    }

    private fun handleMessage(tick: Int, raw: String) {
        if (raw == "- Welcome to Daemonheim -") {
            finalizeCurrent()
            npcIndexHistory.clear()
            current = DungeonFloorStats(currentFile).also {
                it.startTick = tick
                it.tokensAtStart = lastKnownTokens
                it.levelsAtStart.putAll(currentLevels)
                // pendingRoomOrigins survives only when the last map event before this line was the
                // real dungeon RebuildRegion (RebuildNormal, e.g. leaving the Daemonheim lobby,
                // resets it to null); only then is pendingStartCoord an actual in-instance position
                // rather than the player's last real-world tile, which can't translate to one.
                pendingRoomOrigins?.let { origins ->
                    for ((source, destination) in origins) it.recordRoomOrigin(source, destination)
                    pendingStartCoord?.let { coord -> it.visitRoom(coord, tick) }
                }
                for (door in pendingDoors) {
                    it.noteRotation(door.coord, door.rotation)
                    it.recordDoor(door.coord, door.id, door.kind, door.keyNumber)
                }
                it.lockedDoorBarriers.addAll(pendingBarrierKeyNumbers)
                for (obstruction in pendingObstructions) {
                    it.noteRotation(obstruction.coord, obstruction.rotation)
                    it.recordObstruction(obstruction.coord, obstruction.id, obstruction.skill, obstruction.cleared)
                }
            }
            pendingRoomOrigins = null
            pendingDoors.clear()
            pendingBarrierKeyNumbers.clear()
            pendingObstructions.clear()
            pendingDoorInteraction = null
            pendingStartCoord = null
            lastGatherSpot = null
            introLines = mutableListOf()
            return
        }
        val intro = introLines
        if (intro != null) {
            if (raw.isBlank()) {
                applyIntro(intro)
                introLines = null
            } else {
                intro.add(raw)
            }
            return
        }
        val floor = current ?: return
        val message = stripTags(raw)
        if (message.isBlank()) return
        if (message.startsWith("For completing this floor") ||
            message.contains("floor buff is now at") ||
            message.startsWith("You've just advanced a")
        ) {
            floor.completionMessages.add(message)
        }
        REWARD_ITEM_REGEX.find(message)?.let { match ->
            for (item in match.groupValues[1].split(",")) {
                val name = item.trim().removeSuffix(".").trim()
                if (name.isNotEmpty()) floor.awardedItems.add(name)
            }
        }
    }

    private fun applyIntro(lines: List<String>) {
        val floor = current ?: return
        var inBuffs = false
        for (line in lines) {
            when {
                line == "Floor buffs:" -> inBuffs = true
                inBuffs -> floor.floorBuffs.add(stripTags(line))
                else -> {
                    FLOOR_REGEX.find(line)?.let {
                        floor.floor = it.groupValues[1].toInt()
                        floor.complexity = it.groupValues[2]
                    }
                    SIZE_REGEX.find(line)?.let { floor.size = it.groupValues[1] }
                    PARTY_REGEX.find(line)?.let {
                        floor.partySize = it.groupValues[1].toInt()
                        floor.difficulty = it.groupValues[2].toInt()
                    }
                }
            }
        }
    }

    private fun handleTokens(value: Int) {
        current?.tokensAtEnd = value
        lastKnownTokens = value
    }

    private fun handleMidiSong(tick: Int, id: Int) {
        val floor = current ?: return
        if (id == MIDI_DUNGEON_COMPLETE) {
            floor.completed = true
            floor.endTick = tick
            floor.bossRoomTile = floor.visitRoom(localPlayerCoord(), tick)
        }
    }

    private fun handleVarc(id: Int, value: Int) {
        if (id == VARC_PARTY_FLOOR_TIME) {
            val floor = current ?: return
            if (value >= 0) {
                floor.floorTimeSeconds = value
            }
        }
    }

    private fun handleVarcStr(id: Int, value: String) {
        if (id in VARC_PARTY_MEMBER_NAMES && value.isNotBlank()) {
            val floor = current ?: return
            if (value !in floor.partyMembers) {
                floor.partyMembers.add(value)
            }
        }
    }

    private fun handleUpdateStat(sessionState: SessionState, packet: Rs3UpdateStat) {
        val oldXp = sessionState.getExperience(packet.skillId) ?: 0
        sessionState.setExperience(packet.skillId, packet.xp)
        currentLevels[skillName(packet.skillId)] = packet.level
        val gained = packet.xp - oldXp
        if (gained <= 0) return
        val floor = current ?: return
        val skill = skillName(packet.skillId)
        floor.xpGained[skill] = (floor.xpGained[skill] ?: 0L) + gained
        val spot = lastGatherSpot
        if (spot != null && activeFloor != null && spot.skill == skill) {
            // The depleting gather's xp can arrive in the same tick as (and after) the depleted loc.
            val depletedTick = spot.depletedTick
            if (depletedTick == null || currentTick <= depletedTick) spot.gathers++
        }
    }

    /**
     * The instance grid from the RebuildRegion map-load packet: each non-empty cell says which
     * real, static Daemonheim room template (level/zoneX/zoneZ + rotation) was copied into which
     * slot of this dungeon's private scene. Ground-floor rooms only (see the destLevel filter
     * below) - a dungeon floor's own upper story, e.g. behind a staircase, is out of scope here.
     *
     * A dungeon room is 16x16 tiles, i.e. a 2x2 block of zones (a zone is 8x8 tiles), so only the
     * template of the top-left zone of each such block is sampled: the other three zones of the
     * same room have their own, distinct source zone coordinates and would otherwise register as
     * three extra, spurious "rooms". [Rs3World]'s own row/column-to-tile math (see its instanceCoord)
     * is mirrored here to turn the destination row/column back into an actual instance tile.
     */
    private fun handleRoomLayout(message: Rs3RebuildRegion) {
        val originZoneX = (message.regionOriginX shr 3) shl 3
        val originZoneZ = (message.regionOriginZ shr 3) shl 3
        val origins = mutableListOf<Pair<CoordGrid, CoordGrid>>()
        for ((destLevel, plane) in message.templates.withIndex()) {
            if (destLevel != 0) continue
            for ((row, cells) in plane.withIndex()) {
                if (row % 2 != 0) continue
                for ((column, template) in cells.withIndex()) {
                    if (column % 2 != 0) continue
                    if (template == -1) continue
                    val sourceZoneX = (template ushr 14) and 0x3ff
                    val sourceZoneZ = (template ushr 3) and 0x7ff
                    val sourceLevel = (template ushr 24) and 3
                    val source = CoordGrid(sourceLevel, sourceZoneX * 8, sourceZoneZ * 8)
                    val destination = CoordGrid(destLevel, (originZoneX + row) * 8, (originZoneZ + column) * 8)
                    origins.add(source to destination)
                }
            }
        }
        pendingRoomOrigins = origins
        val floor = activeFloor
        if (floor != null) {
            for ((source, destination) in origins) floor.recordRoomOrigin(source, destination)
        }
    }

    /** Translates a coordinate in this dungeon's private instance plane into the coordinate of the static room template it was copied from, when known. */
    private fun toRoomCoord(coord: CoordGrid): CoordGrid {
        if (coord == CoordGrid.INVALID) return coord
        return world.instanceCoord(coord) ?: coord
    }

    private fun handleNpcInfo(packet: Rs3NpcInfo) {
        val floor = activeFloor
        for ((index, update) in packet.updates) {
            when (update) {
                is Rs3NpcUpdateType.Add -> {
                    val id = transformedId(update.id, update.extendedInfo)
                    val raw = CoordGrid(update.level, update.x, update.z)
                    val coord = toRoomCoord(raw)
                    npcIndexToId[index] = id
                    npcIndexToCoord[index] = coord
                    if (floor != null && coord != CoordGrid.INVALID) {
                        floor.noteRotation(coord, world.instanceRotation(raw))
                        // Re-entering render distance re-Adds the same index; reuse its existing
                        // spot rather than logging another spawn of the same npc (see npcIndexHistory).
                        val previous = npcIndexHistory[index]
                        val spot =
                            if (previous != null && previous.id == id) {
                                previous
                            } else {
                                floor.npcSpot(coord, id, npcId(id)).also { it.count++ }
                            }
                        npcIndexToSpot[index] = spot
                        npcIndexHistory[index] = spot
                        applyNpcExtendedInfo(spot, update.extendedInfo)
                    } else {
                        npcIndexToSpot.remove(index)
                    }
                }
                is Rs3NpcUpdateType.Active -> {
                    val previousId = npcIndexToId[index] ?: continue
                    val id = transformedId(previousId, update.extendedInfo)
                    if (id != previousId) npcIndexToId[index] = id
                    npcIndexToCoord[index] = toRoomCoord(CoordGrid(update.level, update.x, update.z))
                    if (update.extendedInfo.isNotEmpty()) {
                        // Anims/gfx/stats seen while the npc is active are attributed back to the
                        // spawn-point record its Add created, not to wherever it's since walked to.
                        npcIndexToSpot[index]?.let { spot -> applyNpcExtendedInfo(spot, update.extendedInfo) }
                    }
                }
                Rs3NpcUpdateType.Remove -> {
                    val deathCoord = npcIndexToCoord.remove(index)
                    val deathId = npcIndexToId.remove(index)
                    val deathSpot = npcIndexToSpot.remove(index)
                    if (deathId != null && deathCoord != null && deathCoord != CoordGrid.INVALID) {
                        recentNpcDeaths.add(NpcDeath(deathSpot, deathId, deathCoord, currentTick))
                    }
                }
                Rs3NpcUpdateType.Idle -> {}
            }
        }
    }

    /**
     * Many Daemonheim NPCs spawn as a generic template id and immediately morph into their real
     * appearance via a Transformation mask carried in the very same update; without this, they'd
     * be recorded under the pre-transform template's (often unrelated-looking) name forever, since
     * [npcIndexToId] is otherwise only ever populated from the Add update's raw id.
     */
    private fun transformedId(fallbackId: Int, infos: List<Rs3NpcExtendedInfo>): Int =
        infos.filterIsInstance<Rs3NpcMask.Transformation>().lastOrNull()?.id ?: fallbackId

    private fun applyNpcExtendedInfo(spot: NpcSpot, infos: List<Rs3NpcExtendedInfo>) {
        for (info in infos) {
            when (info) {
                is Rs3NpcMask.Sequence -> {
                    val id = info.ids.firstOrNull { it != -1 } ?: continue
                    val animName = animationId(id)
                    spot.sequences[animName] = (spot.sequences[animName] ?: 0) + 1
                }
                is Rs3NpcMask.Spotanims -> {
                    for (addition in info.additions) {
                        val gfxName = gfxId(addition.id)
                        spot.spotanims[gfxName] = (spot.spotanims[gfxName] ?: 0) + 1
                    }
                }
                is Rs3NpcMask.Stats -> {
                    for (stat in info.stats) {
                        val observation = spot.stats.getOrPut(stat.slot) { NpcStatObservation() }
                        observation.baseLevel = stat.baseLevel
                        observation.minCurrentLevel = minOf(observation.minCurrentLevel, stat.currentLevel)
                        observation.maxCurrentLevel = maxOf(observation.maxCurrentLevel, stat.currentLevel)
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * A click on a resource node counts an attempt as before; a click on anything already recorded
     * as a door (see [recordResourceState]) - a guardian/locked door or a skill obstruction alike -
     * counts an open attempt and arms [pendingDoorInteraction], so that whatever animation/gfx the
     * local player plays in response (see [handlePlayerInfo]) gets attributed to that same door.
     */
    private fun handleOpLoc(sessionState: SessionState, packet: Rs3OpLoc) {
        val floor = activeFloor ?: return
        val name = objectId(packet.id)
        val level = sessionState.getPlayerOrNull(sessionState.localPlayerIndex)?.coord?.level ?: 0
        val raw = CoordGrid(level, packet.x, packet.y)
        val coord = toRoomCoord(raw)
        if (coord == CoordGrid.INVALID) return
        val door = floor.doorAt(coord)
        if (door != null) {
            floor.noteRotation(coord, world.instanceRotation(raw))
            door.attempts++
            pendingDoorInteraction = PendingDoorInteraction(coord, currentTick)
            lastGatherSpot = null
            return
        }
        if (resourceSkill(name) == null) return
        floor.noteRotation(coord, world.instanceRotation(raw))
        val spot = floor.resourceSpot(coord, canonicalResourceId(name))
        spot.attempts++
        lastGatherSpot = spot
    }

    /** The local player's index/coord is otherwise never populated on [sessionState] in this walk. */
    private fun initLocalPlayer(sessionState: SessionState, init: Rs3PlayerInfoInitBlock?) {
        if (init == null) return
        sessionState.localPlayerIndex = init.localPlayerIndex
        val coord = CoordGrid(init.localPlayerLevel, init.localPlayerX, init.localPlayerZ)
        sessionState.overridePlayer(Player(init.localPlayerIndex, "", coord))
        recordVisit(toRoomCoord(coord))
    }

    private fun handlePlayerInfo(sessionState: SessionState, packet: Rs3PlayerInfo) {
        val index = sessionState.localPlayerIndex
        val update = packet.updates[index] ?: return
        val extendedInfo =
            when (update) {
                is Rs3PlayerUpdateType.HighResolutionIdle -> update.extendedInfo
                is Rs3PlayerUpdateType.LowResolutionToHighResolution -> update.extendedInfo
                is Rs3PlayerUpdateType.HighResolutionMovement -> update.extendedInfo
                else -> emptyList()
            }
        if (extendedInfo.isNotEmpty()) applyPendingDoorInteractionAnims(extendedInfo)
        val coord =
            when (update) {
                is Rs3PlayerUpdateType.LowResolutionToHighResolution -> CoordGrid(update.level, update.x, update.z)
                is Rs3PlayerUpdateType.HighResolutionMovement -> CoordGrid(update.level, update.x, update.z)
                else -> return
            }
        sessionState.overridePlayer(Player(index, "", coord))
        recordVisit(toRoomCoord(coord))
    }

    /**
     * Whatever animation/gfx the local player plays within [DOOR_INTERACTION_ANIM_WINDOW_TICKS] of
     * clicking a door/obstruction (see [handleOpLoc]) is attributed to it as the "open" (or "fail")
     * reaction; this can't itself tell success from failure, so both land in the same counts and are
     * told apart, if at all, by cross-referencing [DoorSpot.cleared]/[DoorSpot.attempts].
     */
    private fun applyPendingDoorInteractionAnims(extendedInfo: List<Rs3PlayerExtendedInfo>) {
        val floor = activeFloor ?: return
        val interaction = pendingDoorInteraction ?: return
        if (currentTick - interaction.tick > DOOR_INTERACTION_ANIM_WINDOW_TICKS) {
            pendingDoorInteraction = null
            return
        }
        for (info in extendedInfo) {
            when (info) {
                is Rs3PlayerExtendedInfo.Sequence -> {
                    val id = info.ids.firstOrNull { it != -1 } ?: continue
                    floor.recordDoorPlayerAnimation(interaction.coord, animationId(id))
                }
                is Rs3PlayerExtendedInfo.Spotanims -> {
                    for (addition in info.additions) {
                        floor.recordDoorPlayerSpotanim(interaction.coord, gfxId(addition.id))
                    }
                }
                else -> {}
            }
        }
    }

    /** See [pendingStartCoord] for why this can't just always read the floor off [activeFloor]. */
    private fun recordVisit(roomCoord: CoordGrid) {
        val floor = activeFloor
        if (floor != null) {
            floor.visitRoom(roomCoord, currentTick)
        } else {
            pendingStartCoord = roomCoord
        }
    }

    /**
     * Any click on an inventory item is a candidate player action (Drop, but also an item-specific
     * op that places something on the ground, e.g. a dungeoneering group gatestone): verified in a
     * real recording, both show up as an If3Button click on this exact component followed, within
     * the same tick, by an ObjAdd for that same item id at the player's own tile.
     */
    private fun handleIf3Button(packet: Rs3If3Button) {
        if (packet.obj == -1) return
        val iface = packet.combinedId ushr 16
        val component = packet.combinedId and 0xFFFF
        if (iface != INVENTORY_INTERFACE_ID || component != INVENTORY_ITEM_COMPONENT) return
        pendingPlayerActions.add(PendingPlayerAction(packet.obj, currentTick))
    }

    private fun handleZone(packet: Rs3UpdateZonePartialEnclosed) {
        world.setActiveZone(packet.zoneX, packet.zoneZ, packet.level)
        if (activeFloor == null) return
        for (child in packet.packets) {
            when (child) {
                is Rs3LocAddChange -> {
                    val raw = world.relativizeZoneCoord(child.xInZone, child.zInZone, packet.level)
                    recordResourceState(toRoomCoord(raw), child.locId, world.instanceRotation(raw))
                }
                is Rs3ObjAdd -> {
                    val raw = world.relativizeZoneCoord(child.xInZone, child.zInZone, packet.level)
                    // A zone update embedded in an "enclosed" packet is always an incremental
                    // change to an already-loaded room, never its initial full snapshot.
                    recordFloorItem(
                        toRoomCoord(raw),
                        child.objId,
                        child.count,
                        isSnapshot = false,
                        rotation = world.instanceRotation(raw),
                    )
                }
                is Rs3LocAnim -> {
                    if (child.id != -1) {
                        val raw = world.relativizeZoneCoord(child.xInZone, child.zInZone, packet.level)
                        activeFloor?.recordDoorObjectAnimation(toRoomCoord(raw), animationId(child.id))
                    }
                }
                is Rs3MapAnim -> {
                    val raw = world.relativizeZoneCoord(child.xInZone, child.zInZone, packet.level)
                    activeFloor?.recordDoorObjectSpotanim(toRoomCoord(raw), gfxId(child.id))
                }
                else -> {}
            }
        }
    }

    /**
     * Tracks a resource node's full/depleted state transition at its exact spawn tile, or records a
     * door, a locked door's "barrier" (a physical blockage sat in front of it, numbered to match the
     * key that clears it - see [DoorSpot.hasBarrier]), or a skill-gated obstruction (e.g. a fire that
     * needs burning down with Firemaking - see [ONECLICK_OBSTRUCTION_REGEX]).
     *
     * All three are handled before the `activeFloor == null` check: unlike resource nodes (which
     * reliably arrive as part of a room's full-snapshot load), these can arrive as an "incremental"
     * LocAddChange the very first time their room is seen - including during the initial region
     * load for a floor, which fires before the "- Welcome to Daemonheim -" line creates [current],
     * or while [current] is a just-completed floor awaiting finalization (see [activeFloor]).
     * Anything seen this early is stashed in a pending list and applied once the floor actually
     * starts, instead of being dropped.
     */
    private fun recordResourceState(coord: CoordGrid, locId: Int, rotation: Int?) {
        if (coord == CoordGrid.INVALID) return
        val name = objectId(locId)
        val kind = doorKind(name)
        if (kind != null) {
            val floor = activeFloor
            if (floor != null) {
                floor.noteRotation(coord, rotation)
                floor.recordDoor(coord, name, kind, lockedDoorKeyNumber(name))
            } else {
                pendingDoors.add(PendingDoor(coord, name, kind, lockedDoorKeyNumber(name), rotation))
            }
            return
        }
        val barrierKeyNumber = lockedDoorBarrierKeyNumber(name)
        if (barrierKeyNumber != null) {
            val floor = activeFloor
            if (floor != null) {
                floor.lockedDoorBarriers.add(barrierKeyNumber)
            } else {
                pendingBarrierKeyNumbers.add(barrierKeyNumber)
            }
            return
        }
        val obstruction = ONECLICK_OBSTRUCTION_REGEX.find(name)
        if (obstruction != null) {
            val skill = obstruction.groupValues[1]
            val cleared = obstruction.groupValues[2] == "unlocked"
            val floor = activeFloor
            if (floor != null) {
                floor.noteRotation(coord, rotation)
                floor.recordObstruction(coord, name, skill, cleared)
            } else {
                pendingObstructions.add(PendingObstruction(coord, name, skill, cleared, rotation))
            }
            return
        }
        val floor = activeFloor ?: return
        if (resourceSkill(name) == null) return
        floor.noteRotation(coord, rotation)
        val spot = floor.resourceSpot(coord, canonicalResourceId(name))
        if (name.contains("empty")) {
            spot.depletions++
            if (spot.depletedAfter == null) spot.depletedAfter = spot.attempts
            if (spot.depletedTick == null) spot.depletedTick = currentTick
        } else {
            spot.spawns++
        }
    }

    /**
     * Records a ground item, tagged as either a room-load "spawn" (delivered as part of a zone's
     * initial full snapshot) or a "drop" (a later, incremental addition). Drops that land on the
     * exact tile an NPC was removed from within [NPC_DEATH_DROP_WINDOW_TICKS] ticks are further
     * attributed to that specific NPC spawn point - nested under its own entry in [NpcSpot.drops]
     * rather than the room's flat [RoomStats.floorItems] list, so that two separate kills of the
     * same NPC (e.g. after a respawn) each keep their own drops instead of being collated together;
     * this is a heuristic, as not every Remove is a death (an NPC leaving render distance also
     * removes it), so an occasional drop may be mis-attributed.
     *
     * Items the player themselves put on the ground are not statistics about the dungeon and are
     * excluded: an npc-death drop takes priority if one matches, otherwise a recent inventory click
     * on that same item id, landing on the player's own tile, is assumed to be that action's result
     * and skipped (see [handleIf3Button]).
     */
    private fun recordFloorItem(coord: CoordGrid, objId: Int, count: Int, isSnapshot: Boolean, rotation: Int?) {
        val floor = activeFloor ?: return
        if (coord == CoordGrid.INVALID) return
        val death = if (isSnapshot) null else findRecentNpcDeath(coord)
        if (death == null && !isSnapshot && consumePendingPlayerAction(objId, coord)) return
        floor.noteRotation(coord, rotation)
        val name = itemId(objId)
        val deathSpot = death?.spot
        if (deathSpot != null) {
            val spot = deathSpot.dropSpot(name, tileKey(coord))
            spot.count += count
            if (spot.firstTick == null) spot.firstTick = currentTick
            return
        }
        val source = if (isSnapshot) "spawn" else "drop"
        val spot = floor.floorItemSpot(coord, name, source)
        spot.count += count
        if (spot.firstTick == null) spot.firstTick = currentTick
    }

    private fun findRecentNpcDeath(coord: CoordGrid): NpcDeath? {
        recentNpcDeaths.removeAll { currentTick - it.tick > NPC_DEATH_DROP_WINDOW_TICKS }
        return recentNpcDeaths.lastOrNull { it.coord == coord }
    }

    private fun consumePendingPlayerAction(objId: Int, coord: CoordGrid): Boolean {
        pendingPlayerActions.removeAll { currentTick - it.tick > PLAYER_ACTION_WINDOW_TICKS }
        if (coord != localPlayerCoord()) return false
        val index = pendingPlayerActions.indexOfLast { it.objId == objId }
        if (index == -1) return false
        pendingPlayerActions.removeAt(index)
        return true
    }

    private fun localPlayerCoord(): CoordGrid {
        val session = activeSessionState ?: return CoordGrid.INVALID
        val coord = session.getPlayerOrNull(session.localPlayerIndex)?.coord ?: return CoordGrid.INVALID
        return toRoomCoord(coord)
    }

    /** Returns true if a floor was written out. */
    private fun finalizeCurrent(): Boolean {
        val floor = current ?: return false
        current = null
        introLines = null
        writeFloor(floor)
        return true
    }

    private fun writeFloor(floor: DungeonFloorStats) {
        val start = floor.tokensAtStart
        val end = floor.tokensAtEnd
        if (start != null && end != null) {
            floor.tokensEarned = end - start
        }
        floor.markCriticalRooms()
        floorSequence++
        val json = gson.toJson(floor.toOutput())
        val fileName = "${floor.sourceFile}-floor${floor.floor.takeIf { it >= 0 } ?: floorSequence}-$floorSequence.json"
        statsDirectory.resolve(fileName).writeText(json)
        val jsonlLine = json.replace("\n", " ") + "\n"
        val jsonl = statsDirectory.resolve("dungeon-stats.jsonl")
        Files.writeString(jsonl, jsonlLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private class PendingDoor(val coord: CoordGrid, val id: String, val kind: String, val keyNumber: Int?, val rotation: Int?)

    private class PendingObstruction(
        val coord: CoordGrid,
        val id: String,
        val skill: String,
        val cleared: Boolean,
        val rotation: Int?,
    )

    private class NpcDeath(val spot: NpcSpot?, val npcId: Int, val coord: CoordGrid, val tick: Int)

    private class PendingPlayerAction(val objId: Int, val tick: Int)

    private class PendingDoorInteraction(val coord: CoordGrid, val tick: Int)

    private class NpcStatObservation {
        var baseLevel: Int = -1
        var minCurrentLevel: Int = Int.MAX_VALUE
        var maxCurrentLevel: Int = Int.MIN_VALUE
    }

    /** One specific gather point: a resource node at one exact tile, tracked across its full/depleted cycles. */
    private class ResourceSpot(val id: String, val tile: String) {
        val skill: String? = resourceSkill(id)
        var attempts: Int = 0
        var spawns: Int = 0
        var depletions: Int = 0
        var depletedAfter: Int? = null

        /** Resources gathered from this node, see [DungeonStatisticsCommand.handleUpdateStat]. */
        var gathers: Int = 0

        /** The tick the node was first seen depleted, after which later xp drops can't have come from it. */
        var depletedTick: Int? = null
    }

    /** One specific NPC spawn point: an npc id repeatedly seen at one exact tile (e.g. across respawns). */
    private class NpcSpot(val id: Int, val sid: String, val tile: String) {
        var count: Int = 0
        val sequences: MutableMap<String, Int> = linkedMapOf()
        val spotanims: MutableMap<String, Int> = linkedMapOf()
        val stats: MutableMap<Int, NpcStatObservation> = linkedMapOf()

        /**
         * Items dropped by this exact spawn point's kills, keyed by id/tile so a specific kill's
         * drops stay grouped under the npc that dropped them rather than a room-wide flat list -
         * see [DungeonStatisticsCommand.recordFloorItem].
         */
        val drops: MutableMap<String, FloorItemSpot> = linkedMapOf()

        fun dropSpot(id: String, tile: String): FloorItemSpot = drops.getOrPut("$id@$tile") { FloorItemSpot(id, tile, "drop") }
    }

    /** One specific ground item stack: a distinct id/tile/source combination. */
    private class FloorItemSpot(val id: String, val tile: String, val source: String) {
        var count: Int = 0

        /** The tick this stack was first seen, e.g. to tell a key lying in a room from one appearing later. */
        var firstTick: Int? = null
    }

    /**
     * A door - or a skill-gated obstruction standing in for one where no separate door loc exists
     * (see [recordObstruction]) - seen on the room's wall the moment it was first opened.
     * [kind] is one of "door" (plain, unlocked), "guardian_door" (needs the co-op guardian
     * mechanism to open), "boss_door" (leads into the boss room), "locked_door" (needs the matching
     * dungeoneering key, numbered per floor - see [keyNumber] and [DoorOutput.hasBarrier]), or
     * "skill_obstruction" (needs a specific skill action, e.g. burning down a fire with Firemaking -
     * see [skill], [attempts] and [cleared]).
     */
    private class DoorSpot(
        val coord: CoordGrid,
        val direction: String,
        val id: String,
        val kind: String,
        val keyNumber: Int?,
        val skill: String?,
        val tile: String,
    ) {
        /** How many times this door/obstruction was clicked (an OpLoc landed on its tile). */
        var attempts: Int = 0

        /** kind == "skill_obstruction" only: whether the matching "..._unlocked_<theme>" was seen. */
        var cleared: Boolean = false

        /** The door object's own animations (LocAnim on its tile), e.g. swinging open, by count. */
        val objectSequences: MutableMap<String, Int> = linkedMapOf()

        /** Gfx played at the door's tile (MapAnim), e.g. a magical unlock effect, by count. */
        val objectSpotanims: MutableMap<String, Int> = linkedMapOf()

        /** The local player's animations while opening/failing to open this door, by count. */
        val playerSequences: MutableMap<String, Int> = linkedMapOf()

        /** Gfx played on the local player while opening/failing to open this door, by count. */
        val playerSpotanims: MutableMap<String, Int> = linkedMapOf()
    }

    /** [tile] is the static template's own ("originating") coordinate - see [actualTile]. */
    private class RoomStats(val tile: String) {
        /** Where this room actually sits in this floor's private instance, from the map-load packet. */
        var actualTile: String? = null

        /** 0-3: how the static room template was rotated when placed into this instance. */
        var rotation: Int? = null

        /** The tick the local player first entered this room, i.e. when its door was opened. */
        var firstVisitTick: Int? = null

        /**
         * Whether this room lies on the critical path (the doors that must be opened to reach the
         * boss room), or null when that can't be determined - see the comment on
         * [DungeonFloorStats.markCriticalRooms].
         */
        var critical: Boolean? = null
        val resources: MutableMap<String, ResourceSpot> = linkedMapOf()
        val npcs: MutableMap<String, NpcSpot> = linkedMapOf()
        val floorItems: MutableMap<String, FloorItemSpot> = linkedMapOf()

        /** Doors seen spawning when this room was first opened, keyed by tile. */
        val doors: MutableMap<String, DoorSpot> = linkedMapOf()
    }

    private class DungeonFloorStats(val sourceFile: String) {
        var floor: Int = -1
        var complexity: String? = null
        var size: String? = null
        var partySize: Int = -1
        var difficulty: Int = -1
        val partyMembers: MutableList<String> = mutableListOf()
        val floorBuffs: MutableList<String> = mutableListOf()
        var startTick: Int = -1
        var endTick: Int = -1
        var floorTimeSeconds: Int = -1
        var completed: Boolean = false
        var tokensAtStart: Int? = null
        var tokensAtEnd: Int? = null
        var tokensEarned: Int? = null
        val levelsAtStart: MutableMap<String, Int> = linkedMapOf()
        val xpGained: MutableMap<String, Long> = linkedMapOf()
        val completionMessages: MutableList<String> = mutableListOf()

        /** Item names parsed from "You receive: ..." reward chat lines seen this floor. */
        val awardedItems: MutableList<String> = mutableListOf()

        // Coordinates below are all translated back to the static room template's own coordinate
        // space (see toRoomCoord), so rooms are keyed consistently regardless of instance rotation.
        val rooms: MutableMap<String, RoomStats> = linkedMapOf()

        /** The room the party started this floor in; always known and always critical. */
        var startRoomTile: String? = null

        /** The room the boss was killed in (the local player's room when the completion midi fires). */
        var bossRoomTile: String? = null

        /** Key numbers of every "rand_locked_door_barrier_<n>" seen this floor - see [DoorSpot.hasBarrier]. */
        val lockedDoorBarriers: MutableSet<Int> = mutableSetOf()

        private fun room(coord: CoordGrid): RoomStats {
            val tile = roomTile(coord.x, coord.z, coord.level)
            return rooms.getOrPut(tile) { RoomStats(tile) }
        }

        /** Records the room's placement rotation; a null [rotation] (unknown) never overwrites a known one. */
        fun noteRotation(coord: CoordGrid, rotation: Int?) {
            if (rotation == null) return
            room(coord).rotation = rotation
        }

        /** Records where the room templated at [source] actually sits in this floor's instance. */
        fun recordRoomOrigin(source: CoordGrid, destination: CoordGrid) {
            room(source).actualTile = roomTile(destination.x, destination.z, destination.level)
        }

        /**
         * Records that the local player entered the room at [coord] - i.e. its door was opened - at
         * [tick], the first time this is observed for that room, and returns the room's tile key
         * (or null for an invalid coord).
         */
        fun visitRoom(coord: CoordGrid, tick: Int): String? {
            if (coord == CoordGrid.INVALID) return null
            val stats = room(coord)
            if (stats.firstVisitTick == null) {
                stats.firstVisitTick = tick
                if (startRoomTile == null) startRoomTile = stats.tile
            }
            return stats.tile
        }

        /**
         * Every Daemonheim door - whether it leads deeper along the mandatory route to the boss or
         * into an optional side room - is the exact same generic loc (`rand_dnd_standard_door`), and
         * the map-load packet only reveals which static room template occupies each grid cell, not
         * how rooms are actually connected by doors. Neither piece of data this dumper can observe
         * distinguishes a critical-path door from an optional one, so the true critical path can't be
         * reconstructed here.
         *
         * What *is* certain: the starting room and the room the boss died in are always on the
         * critical path, and any room whose door was first opened after the boss died can't be
         * (the floor was already complete). Everything else is left as unknown (null) rather than
         * guessed at.
         */
        fun markCriticalRooms() {
            val bossTick = bossRoomTile?.let { rooms[it]?.firstVisitTick }
            for (stats in rooms.values) {
                stats.critical =
                    when {
                        stats.tile == startRoomTile || stats.tile == bossRoomTile -> true
                        bossTick != null && stats.firstVisitTick != null && stats.firstVisitTick!! > bossTick -> false
                        else -> null
                    }
            }
        }

        fun recordDoor(coord: CoordGrid, id: String, kind: String, keyNumber: Int?) {
            val tile = tileKey(coord)
            room(coord).doors.getOrPut(tile) {
                DoorSpot(coord, edgeDirection(coord.x, coord.z), id, kind, keyNumber, skill = null, tile)
            }
        }

        /**
         * Records a skill-gated obstruction (e.g. "rand_oneclick_firemaking_locked_frozen") as a
         * "skill_obstruction" door-like entry, keyed by tile so its later "..._unlocked_<theme>"
         * counterpart (same tile, same skill) updates the same entry's [DoorSpot.cleared] instead of
         * creating a duplicate. There's often no separate door loc for the opening this gates - see
         * [DungeonStatisticsCommand.ONECLICK_OBSTRUCTION_REGEX].
         */
        fun recordObstruction(coord: CoordGrid, id: String, skill: String, cleared: Boolean) {
            val tile = tileKey(coord)
            val stats =
                room(coord).doors.getOrPut(tile) {
                    DoorSpot(coord, edgeDirection(coord.x, coord.z), id, "skill_obstruction", null, skill, tile)
                }
            if (cleared) stats.cleared = true
        }

        /** The door/obstruction already recorded at [coord], without creating a new room entry. */
        fun doorAt(coord: CoordGrid): DoorSpot? = rooms[roomTile(coord.x, coord.z, coord.level)]?.doors?.get(tileKey(coord))

        /** Attributes a door object's own animation (LocAnim) to the door recorded at [coord], if any. */
        fun recordDoorObjectAnimation(coord: CoordGrid, id: String) {
            doorAt(coord)?.let { it.objectSequences[id] = (it.objectSequences[id] ?: 0) + 1 }
        }

        /** Attributes a gfx played at [coord] (MapAnim) to the door recorded there, if any. */
        fun recordDoorObjectSpotanim(coord: CoordGrid, id: String) {
            doorAt(coord)?.let { it.objectSpotanims[id] = (it.objectSpotanims[id] ?: 0) + 1 }
        }

        /** Attributes the local player's animation, played while [pendingDoorInteraction] is live, to that door. */
        fun recordDoorPlayerAnimation(coord: CoordGrid, id: String) {
            doorAt(coord)?.let { it.playerSequences[id] = (it.playerSequences[id] ?: 0) + 1 }
        }

        /** Attributes the local player's gfx, played while [pendingDoorInteraction] is live, to that door. */
        fun recordDoorPlayerSpotanim(coord: CoordGrid, id: String) {
            doorAt(coord)?.let { it.playerSpotanims[id] = (it.playerSpotanims[id] ?: 0) + 1 }
        }

        fun resourceSpot(coord: CoordGrid, id: String): ResourceSpot {
            val room = room(coord)
            val tile = tileKey(coord)
            return room.resources.getOrPut("$id@$tile") { ResourceSpot(id, tile) }
        }

        fun npcSpot(coord: CoordGrid, id: Int, sid: String): NpcSpot {
            val room = room(coord)
            val tile = tileKey(coord)
            return room.npcs.getOrPut("$id@$tile") { NpcSpot(id, sid, tile) }
        }

        fun floorItemSpot(coord: CoordGrid, id: String, source: String): FloorItemSpot {
            val room = room(coord)
            val tile = tileKey(coord)
            return room.floorItems.getOrPut("$id@$tile@$source") { FloorItemSpot(id, tile, source) }
        }

        fun toOutput(): FloorOutput =
            FloorOutput(
                sourceFile = sourceFile,
                floor = floor,
                complexity = complexity,
                size = size,
                partySize = partySize,
                difficulty = difficulty,
                partyMembers = partyMembers,
                floorBuffs = floorBuffs,
                startTick = startTick,
                endTick = endTick,
                floorTimeSeconds = floorTimeSeconds,
                completed = completed,
                tokensAtStart = tokensAtStart,
                tokensAtEnd = tokensAtEnd,
                tokensEarned = tokensEarned,
                levelsAtStart = levelsAtStart,
                xpGained = xpGained,
                completionMessages = completionMessages,
                awardedItems = awardedItems,
                startRoomTile = startRoomTile,
                bossRoomTile = bossRoomTile,
                rooms = rooms.values.map { it.toOutput() },
            )

        private fun RoomStats.toOutput(): RoomOutput =
            RoomOutput(
                originatingTile = tile,
                actualTile = actualTile,
                rotation = rotation,
                firstVisitTick = firstVisitTick,
                critical = critical,
                doors = doors.values.map { it.toOutput() },
                resources = resources.values.map { it.toOutput() },
                npcs = npcs.values.map { it.toOutput() },
                floorItems = floorItems.values.map { it.toOutput() },
            )

        private fun DoorSpot.toOutput(): DoorOutput =
            DoorOutput(
                direction = direction,
                id = id,
                kind = kind,
                keyNumber = keyNumber,
                hasBarrier = keyNumber != null && keyNumber in lockedDoorBarriers,
                skill = skill,
                attempts = attempts,
                cleared = if (kind == "skill_obstruction") cleared else null,
                objectSequences = objectSequences,
                objectSpotanims = objectSpotanims,
                playerSequences = playerSequences,
                playerSpotanims = playerSpotanims,
                tile = tile,
            )

        private fun ResourceSpot.toOutput(): ResourceOutput =
            ResourceOutput(id, tile, attempts, spawns, depletions, depletedAfter, gathers)

        private fun NpcSpot.toOutput(): NpcOutput =
            NpcOutput(
                id = id,
                sid = sid,
                tile = tile,
                count = count,
                sequences = sequences.filterNot { it.key == "65535" },
                spotanims = spotanims.filterNot { it.key == "65535" },
                stats = stats.mapValues { (_, stat) -> NpcStatOutput(stat.baseLevel, stat.minCurrentLevel, stat.maxCurrentLevel) },
                drops = drops.values.map { it.toOutput() },
            )

        private fun FloorItemSpot.toOutput(): FloorItemOutput = FloorItemOutput(id, tile, count, source, firstTick)
    }

    private companion object {
        private const val VARP_REWARD_TOKENS = 1097
        private const val MIDI_DUNGEON_COMPLETE = 28724
        private const val VARC_PARTY_FLOOR_TIME = 4190
        private val VARC_PARTY_MEMBER_NAMES = 2376..2380

        /** How many ticks after an NPC is removed a nearby item add still counts as its drop. */
        private const val NPC_DEATH_DROP_WINDOW_TICKS = 2

        /** The backpack's item container: interface 1473 ("toplevel_v2_inventory"), component 5 ("item_layer"). */
        private const val INVENTORY_INTERFACE_ID = 1473
        private const val INVENTORY_ITEM_COMPONENT = 5

        /** How many ticks after an inventory item click a matching ObjAdd still counts as its result. */
        private const val PLAYER_ACTION_WINDOW_TICKS = 3

        /** How many ticks after clicking a door/obstruction a player anim/gfx still counts as its response. */
        private const val DOOR_INTERACTION_ANIM_WINDOW_TICKS = 3

        private val FLOOR_REGEX = Regex("""Floor\s*(?:<[^>]*>)?(\d+)\s*(?:<[^>]*>)?(\w+) Complexity""")
        private val SIZE_REGEX = Regex("""Dungeon Size:\s*(?:<[^>]*>)?(\w+)""")
        private val PARTY_REGEX = Regex("""Party Size:Difficulty\s*(?:<[^>]*>)?(\d+):(\d+)""")
        private val TAG_REGEX = Regex("""<[^>]*>""")
        private val REWARD_ITEM_REGEX = Regex("""^You received item:\s*(.+)$""")
        private val RESOURCE_SKILL_REGEX = Regex("""rand_([a-z]+)_resource""")
        private val DOOR_REGEX = Regex("""^rand_(door|guardian_door|boss_door)_(?:frzn|abnd|furn|oclt|wrpd)$""")
        private val LOCKED_DOOR_REGEX = Regex("""^rand_locked_door_(\d+)_(?:frozen|abandoned|furnished|occult|warped)$""")
        private val LOCKED_DOOR_BARRIER_REGEX = Regex("""^rand_locked_door_barrier_(\d+)$""")

        /**
         * A skill-gated obstruction, e.g. "rand_oneclick_firemaking_locked_frozen" (a burnable
         * blockage needing Firemaking) or "rand_oneclick_crafting_unlocked_abandoned" (the same
         * blockage after being cleared) - one per skill per Daemonheim decor theme. Group 1 is the
         * skill name, group 2 is "locked" or "unlocked".
         */
        private val ONECLICK_OBSTRUCTION_REGEX =
            Regex("""^rand_oneclick_([a-z]+)_(locked|unlocked)_(?:frozen|abandoned|furnished|occult|warped)$""")

        private val gson = GsonBuilder().setPrettyPrinting().create()

        private val rs3Npcs = loadRs3("npc")
        private val rs3Objects = loadRs3("loc")
        private val rs3Items = loadRs3("obj")
        private val rs3Animations = loadRs3("seq")

        private val skillNames = arrayOf(
            "attack", "defence", "strength", "constitution", "ranged", "prayer", "magic",
            "cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
            "smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
            "runecrafting", "hunter", "construction", "summoning", "dungeoneering",
            "divination", "invention", "archaeology", "necromancy",
        )

        fun skillName(id: Int): String = skillNames.getOrElse(id) { id.toString() }
        fun npcId(id: Int): String = rs3Npcs.getOrDefault(id, id.toString())
        fun objectId(id: Int): String = rs3Objects.getOrDefault(id, id.toString())
        fun itemId(id: Int): String = rs3Items.getOrDefault(id, id.toString())
        fun animationId(id: Int): String = rs3Animations.getOrDefault(id, id.toString())
        fun gfxId(id: Int): String = id.toString()

        fun stripTags(text: String): String = TAG_REGEX.replace(text, "").trim()

        fun resourceSkill(name: String): String? {
            if (!name.contains("_resource")) return null
            return RESOURCE_SKILL_REGEX.find(name)?.groupValues?.get(1)
        }

        /**
         * The node's mapped name with its depleted-state marker stripped, so full/empty share one id:
         * "rand_mining_resource_empty_frzn_1" -> "rand_mining_resource_frzn_1" (the marker sits
         * mid-name, not at the end).
         */
        fun canonicalResourceId(name: String): String = name.replace("_resource_empty", "_resource").removeSuffix("_empty")

        /**
         * A standard room door ("rand_door_<theme>"), a co-op "guardian" door
         * ("rand_guardian_door_<theme>") or the door into the boss room ("rand_boss_door_<theme>"),
         * one per Daemonheim decor theme (frozen/abandoned/furnished/occult/warped); or a keyed
         * "rand_locked_door_<n>_<theme>" (see [lockedDoorKeyNumber]). Returns "door"/"guardian_door"/
         * "boss_door"/"locked_door", or null if [name] isn't a door at all.
         *
         * None of these say whether a door is on the mandatory route to the boss - a guardian or
         * locked door might gate a side room just as easily as the main path - but they *do* say
         * whether it's just walked through versus needing the guardian mechanism or a specific key.
         *
         * In practice a plain "door"/"boss_door" is rarely, if ever, observed this way: verified
         * against a real recording, only doors with state that can actually change at runtime
         * (guardian/locked) were ever sent as a LocAddChange across an entire floor - a plain,
         * never-locked door appears to be baked into the room as static terrain instead, so most
         * rooms will only show up with doors here when at least one of their connections is gated.
         */
        fun doorKind(name: String): String? {
            if (DOOR_REGEX.matches(name)) return DOOR_REGEX.find(name)!!.groupValues[1]
            if (LOCKED_DOOR_REGEX.matches(name)) return "locked_door"
            return null
        }

        /** The dungeoneering key number a `rand_locked_door_<n>_<theme>` loc needs, or null otherwise. */
        fun lockedDoorKeyNumber(name: String): Int? = LOCKED_DOOR_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * The key number of a `rand_locked_door_barrier_<n>` loc - a physical blockage placed in
         * front of the matching locked door, cleared once that key is used - or null otherwise.
         */
        fun lockedDoorBarrierKeyNumber(name: String): Int? =
            LOCKED_DOOR_BARRIER_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()

        /** The anchor (top-left) tile of the 16x16-tile room containing (x, z, level). */
        fun roomTile(x: Int, z: Int, level: Int): String {
            val anchorX = (x shr 4) shl 4
            val anchorZ = (z shr 4) shl 4
            return "$anchorX, $anchorZ, $level"
        }

        /**
         * Which of the room's four walls (x, z, level) is closest to, using the same room-anchor
         * convention as [roomTile]. A door sits exactly on the boundary row/column, but a skill
         * obstruction (see [ONECLICK_OBSTRUCTION_REGEX]) is often a tile or two in front of it, so
         * this picks the nearest wall rather than requiring an exact edge.
         */
        fun edgeDirection(x: Int, z: Int): String {
            val localX = x - ((x shr 4) shl 4)
            val localZ = z - ((z shr 4) shl 4)
            val distanceToNorth = 15 - localZ
            val distanceToSouth = localZ
            val distanceToEast = 15 - localX
            val distanceToWest = localX
            return when (minOf(distanceToNorth, distanceToSouth, distanceToEast, distanceToWest)) {
                distanceToNorth -> "north"
                distanceToSouth -> "south"
                distanceToEast -> "east"
                else -> "west"
            }
        }

        fun tileKey(coord: CoordGrid): String = "${coord.x}, ${coord.z}, ${coord.level}"
    }
}

public fun main(args: Array<String>) {
    DungeonStatisticsCommand().main(args)
}
