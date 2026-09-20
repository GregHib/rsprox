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
    private var introLines: MutableList<String>? = null
    private var lastKnownTokens: Int? = null
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

    private var pendingRoomLayout: Set<String>? = null

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
        recentNpcDeaths.clear()
        pendingPlayerActions.clear()
        pendingRoomLayout = null
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
                pendingRoomLayout = null
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
                pendingRoomLayout?.let { layout -> it.roomLayout.addAll(layout) }
            }
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
        val gained = packet.xp - oldXp
        if (gained <= 0) return
        val floor = current ?: return
        val skill = skillName(packet.skillId)
        floor.xpGained[skill] = (floor.xpGained[skill] ?: 0L) + gained
    }

    /**
     * The instance grid from the RebuildRegion map-load packet: each non-empty cell says which
     * real, static Daemonheim room template (level/zoneX/zoneZ + rotation) was copied into that
     * slot of this dungeon's private scene. This is independent of coordinate translation below.
     *
     * A dungeon room is 16x16 tiles, i.e. a 2x2 block of zones (a zone is 8x8 tiles), so only the
     * template of the top-left zone of each such block is sampled: the other three zones of the
     * same room have their own, distinct source zone coordinates and would otherwise register as
     * three extra, spurious "rooms".
     */
    private fun handleRoomLayout(message: Rs3RebuildRegion) {
        val layout = linkedSetOf<String>()
        for (plane in message.templates) {
            for ((x, row) in plane.withIndex()) {
                if (x % 2 != 0) continue
                for ((z, template) in row.withIndex()) {
                    if (z % 2 != 0) continue
                    if (template == -1) continue
                    val sourceZoneX = (template ushr 14) and 0x3ff
                    val sourceZoneZ = (template ushr 3) and 0x7ff
                    val rotation = (template ushr 1) and 3
                    val sourceLevel = (template ushr 24) and 3
                    layout.add("$sourceLevel:$sourceZoneX:$sourceZoneZ:r$rotation")
                }
            }
        }
        pendingRoomLayout = layout
        current?.roomLayout?.addAll(layout)
    }

    /** Translates a coordinate in this dungeon's private instance plane into the coordinate of the static room template it was copied from, when known. */
    private fun toRoomCoord(coord: CoordGrid): CoordGrid {
        if (coord == CoordGrid.INVALID) return coord
        return world.instanceCoord(coord) ?: coord
    }

    private fun handleNpcInfo(packet: Rs3NpcInfo) {
        val floor = current
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
                    npcIndexToSpot.remove(index)
                    if (deathId != null && deathCoord != null && deathCoord != CoordGrid.INVALID) {
                        recentNpcDeaths.add(NpcDeath(deathId, deathCoord, currentTick))
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

    private fun handleOpLoc(sessionState: SessionState, packet: Rs3OpLoc) {
        val floor = current ?: return
        val name = objectId(packet.id)
        if (resourceSkill(name) == null) return
        val level = sessionState.getPlayerOrNull(sessionState.localPlayerIndex)?.coord?.level ?: 0
        val raw = CoordGrid(level, packet.x, packet.y)
        val coord = toRoomCoord(raw)
        if (coord == CoordGrid.INVALID) return
        floor.noteRotation(coord, world.instanceRotation(raw))
        val spot = floor.resourceSpot(coord, canonicalResourceId(name))
        spot.attempts++
    }

    /** The local player's index/coord is otherwise never populated on [sessionState] in this walk. */
    private fun initLocalPlayer(sessionState: SessionState, init: Rs3PlayerInfoInitBlock?) {
        if (init == null) return
        sessionState.localPlayerIndex = init.localPlayerIndex
        sessionState.overridePlayer(
            Player(init.localPlayerIndex, "", CoordGrid(init.localPlayerLevel, init.localPlayerX, init.localPlayerZ)),
        )
    }

    private fun handlePlayerInfo(sessionState: SessionState, packet: Rs3PlayerInfo) {
        val index = sessionState.localPlayerIndex
        val update = packet.updates[index] ?: return
        val coord =
            when (update) {
                is Rs3PlayerUpdateType.LowResolutionToHighResolution -> CoordGrid(update.level, update.x, update.z)
                is Rs3PlayerUpdateType.HighResolutionMovement -> CoordGrid(update.level, update.x, update.z)
                else -> return
            }
        sessionState.overridePlayer(Player(index, "", coord))
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
        if (current == null) return
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
                else -> {}
            }
        }
    }

    /** Tracks a resource node's full/depleted state transition at its exact spawn tile. */
    private fun recordResourceState(coord: CoordGrid, locId: Int, rotation: Int?) {
        val floor = current ?: return
        if (coord == CoordGrid.INVALID) return
        val name = objectId(locId)
        if (resourceSkill(name) == null) return
        floor.noteRotation(coord, rotation)
        val spot = floor.resourceSpot(coord, canonicalResourceId(name))
        if (name.contains("empty")) {
            spot.depletions++
            if (spot.depletedAfter == null) spot.depletedAfter = spot.attempts
        } else {
            spot.spawns++
        }
    }

    /**
     * Records a ground item, tagged as either a room-load "spawn" (delivered as part of a zone's
     * initial full snapshot) or a "drop" (a later, incremental addition). Drops that land on the
     * exact tile an NPC was removed from within [NPC_DEATH_DROP_WINDOW_TICKS] ticks are further
     * attributed to that NPC; this is a heuristic; not every Remove is a death (an NPC leaving
     * render distance also removes it), so an occasional drop may be mis-attributed.
     *
     * Items the player themselves put on the ground are not statistics about the dungeon and are
     * excluded: an npc-death drop takes priority if one matches, otherwise a recent inventory click
     * on that same item id, landing on the player's own tile, is assumed to be that action's result
     * and skipped (see [handleIf3Button]).
     */
    private fun recordFloorItem(coord: CoordGrid, objId: Int, count: Int, isSnapshot: Boolean, rotation: Int?) {
        val floor = current ?: return
        if (coord == CoordGrid.INVALID) return
        val death = if (isSnapshot) null else findRecentNpcDeath(coord)
        if (death == null && !isSnapshot && consumePendingPlayerAction(objId, coord)) return
        floor.noteRotation(coord, rotation)
        val name = itemId(objId)
        val source = if (isSnapshot) "spawn" else "drop"
        val droppedBy = death?.let { npcId(it.npcId) }
        val spot = floor.floorItemSpot(coord, name, source, droppedBy)
        spot.count += count
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
        floorSequence++
        val json = gson.toJson(floor.toOutput())
        val fileName = "${floor.sourceFile}-floor${floor.floor.takeIf { it >= 0 } ?: floorSequence}-$floorSequence.json"
        statsDirectory.resolve(fileName).writeText(json)
        val jsonlLine = json.replace("\n", " ") + "\n"
        val jsonl = statsDirectory.resolve("dungeon-stats.jsonl")
        Files.writeString(jsonl, jsonlLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private class NpcDeath(val npcId: Int, val coord: CoordGrid, val tick: Int)

    private class PendingPlayerAction(val objId: Int, val tick: Int)

    private class NpcStatObservation {
        var baseLevel: Int = -1
        var minCurrentLevel: Int = Int.MAX_VALUE
        var maxCurrentLevel: Int = Int.MIN_VALUE
    }

    private class NpcStatOutput(val baseLevel: Int, val minCurrentLevel: Int, val maxCurrentLevel: Int)

    /** One specific gather point: a resource node at one exact tile, tracked across its full/depleted cycles. */
    private class ResourceSpot(val id: String, val tile: String) {
        var attempts: Int = 0
        var spawns: Int = 0
        var depletions: Int = 0
        var depletedAfter: Int? = null
    }

    /** One specific NPC spawn point: an npc id repeatedly seen at one exact tile (e.g. across respawns). */
    private class NpcSpot(val id: Int, val sid: String, val tile: String) {
        var count: Int = 0
        val sequences: MutableMap<String, Int> = linkedMapOf()
        val spotanims: MutableMap<String, Int> = linkedMapOf()
        val stats: MutableMap<Int, NpcStatObservation> = linkedMapOf()
    }

    /** One specific ground item stack: a distinct id/tile/source(/source npc) combination. */
    private class FloorItemSpot(val id: String, val tile: String, val source: String, val droppedBy: String?) {
        var count: Int = 0
    }

    private class RoomStats(val tile: String) {
        /** 0-3: how the static room template was rotated when placed into this instance. */
        var rotation: Int? = null
        val resources: MutableMap<String, ResourceSpot> = linkedMapOf()
        val npcs: MutableMap<String, NpcSpot> = linkedMapOf()
        val floorItems: MutableMap<String, FloorItemSpot> = linkedMapOf()
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
        val xpGained: MutableMap<String, Long> = linkedMapOf()
        val completionMessages: MutableList<String> = mutableListOf()

        /** Static room templates ("level:zoneX:zoneZ:rRotation") used to build this instance, from the map-load packet. */
        val roomLayout: MutableSet<String> = linkedSetOf()

        // Coordinates below are all translated back to the static room template's own coordinate
        // space (see toRoomCoord), so rooms are keyed consistently regardless of instance rotation.
        val rooms: MutableMap<String, RoomStats> = linkedMapOf()

        private fun room(coord: CoordGrid): RoomStats {
            val tile = roomTile(coord.x, coord.z, coord.level)
            return rooms.getOrPut(tile) { RoomStats(tile) }
        }

        /** Records the room's placement rotation; a null [rotation] (unknown) never overwrites a known one. */
        fun noteRotation(coord: CoordGrid, rotation: Int?) {
            if (rotation == null) return
            room(coord).rotation = rotation
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

        fun floorItemSpot(coord: CoordGrid, id: String, source: String, droppedBy: String?): FloorItemSpot {
            val room = room(coord)
            val tile = tileKey(coord)
            return room.floorItems.getOrPut("$id@$tile@$source@${droppedBy ?: ""}") {
                FloorItemSpot(id, tile, source, droppedBy)
            }
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
                xpGained = xpGained,
                completionMessages = completionMessages,
                roomLayout = roomLayout.toList(),
                rooms = rooms.values.map { it.toOutput() },
            )

        private fun RoomStats.toOutput(): RoomOutput =
            RoomOutput(
                tile = tile,
                rotation = rotation,
                resources = resources.values.map { it.toOutput() },
                npcs = npcs.values.map { it.toOutput() },
                floorItems = floorItems.values.map { it.toOutput() },
            )

        private fun ResourceSpot.toOutput(): ResourceOutput =
            ResourceOutput(id, tile, attempts, spawns, depletions, depletedAfter)

        private fun NpcSpot.toOutput(): NpcOutput =
            NpcOutput(
                id = id,
                sid = sid,
                tile = tile,
                count = count,
                sequences = sequences,
                spotanims = spotanims,
                stats = stats.mapValues { (_, stat) -> NpcStatOutput(stat.baseLevel, stat.minCurrentLevel, stat.maxCurrentLevel) },
            )

        private fun FloorItemSpot.toOutput(): FloorItemOutput = FloorItemOutput(id, tile, count, source, droppedBy)
    }

    private class FloorOutput(
        val sourceFile: String,
        val floor: Int,
        val complexity: String?,
        val size: String?,
        val partySize: Int,
        val difficulty: Int,
        val partyMembers: List<String>,
        val floorBuffs: List<String>,
        val startTick: Int,
        val endTick: Int,
        val floorTimeSeconds: Int,
        val completed: Boolean,
        val tokensAtStart: Int?,
        val tokensAtEnd: Int?,
        val tokensEarned: Int?,
        val xpGained: Map<String, Long>,
        val completionMessages: List<String>,
        val roomLayout: List<String>,
        val rooms: List<RoomOutput>,
    )

    private class RoomOutput(
        val tile: String,
        val rotation: Int?,
        val resources: List<ResourceOutput>,
        val npcs: List<NpcOutput>,
        val floorItems: List<FloorItemOutput>,
    )

    private class ResourceOutput(
        val id: String,
        val tile: String,
        val attempts: Int,
        val spawns: Int,
        val depletions: Int,
        val depletedAfter: Int?,
    )

    private class NpcOutput(
        val id: Int,
        val sid: String,
        val tile: String,
        val count: Int,
        val sequences: Map<String, Int>,
        val spotanims: Map<String, Int>,
        val stats: Map<Int, NpcStatOutput>,
    )

    private class FloorItemOutput(
        val id: String,
        val tile: String,
        val count: Int,
        val source: String,
        val droppedBy: String?,
    )

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

        private val FLOOR_REGEX = Regex("""Floor\s*(?:<[^>]*>)?(\d+)\s*(?:<[^>]*>)?(\w+) Complexity""")
        private val SIZE_REGEX = Regex("""Dungeon Size:\s*(?:<[^>]*>)?(\w+)""")
        private val PARTY_REGEX = Regex("""Party Size:Difficulty\s*(?:<[^>]*>)?(\d+):(\d+)""")
        private val TAG_REGEX = Regex("""<[^>]*>""")
        private val RESOURCE_SKILL_REGEX = Regex("""rand_([a-z]+)_resource""")

        private val gson = GsonBuilder().setPrettyPrinting().create()

        private val rs3Npcs = loadRs3("npc")
        private val rs3Objects = loadRs3("loc")
        private val rs3Items = loadRs3("obj")
        private val rs3Animations = loadRs3("seq")
        private val rs3Graphics = loadRs3("graphic")

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
        fun gfxId(id: Int): String = rs3Graphics.getOrDefault(id, id.toString())

        fun stripTags(text: String): String = TAG_REGEX.replace(text, "").trim()

        fun resourceSkill(name: String): String? {
            if (!name.contains("_resource")) return null
            return RESOURCE_SKILL_REGEX.find(name)?.groupValues?.get(1)
        }

        /** The node's mapped name with its depleted-state suffix stripped, so full/empty share one id. */
        fun canonicalResourceId(name: String): String = name.removeSuffix("_empty")

        /** The anchor (top-left) tile of the 16x16-tile room containing (x, z, level). */
        fun roomTile(x: Int, z: Int, level: Int): String {
            val anchorX = (x shr 4) shl 4
            val anchorZ = (z shr 4) shl 4
            return "$anchorX, $anchorZ, $level"
        }

        fun tileKey(coord: CoordGrid): String = "${coord.x}, ${coord.z}, ${coord.level}"
    }
}

public fun main(args: Array<String>) {
    DungeonStatisticsCommand().main(args)
}
