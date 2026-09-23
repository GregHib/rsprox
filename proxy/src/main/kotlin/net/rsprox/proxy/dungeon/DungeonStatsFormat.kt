package net.rsprox.proxy.dungeon

/*
 * The on-disk JSON format of a single dungeon floor's statistics, shared between the writer
 * (DungeonStatisticsCommand, which extracts one file per floor from a binary dump) and the reader
 * (DungeonAnalysisCommand, which aggregates many of those files). Field names are the JSON keys, so
 * renaming one breaks compatibility with previously written dumps.
 *
 * Gson instantiates these without running their constructors, so a field absent from an older dump
 * is read back as null (or 0/false for primitives) regardless of its declared Kotlin type; readers
 * should treat collections defensively (see DungeonDataset).
 */

internal class FloorOutput(
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
    val levelsAtStart: Map<String, Int>,
    val xpGained: Map<String, Long>,
    val completionMessages: List<String>,
    val awardedItems: List<String>,
    val startRoomTile: String?,
    val bossRoomTile: String?,
    val rooms: List<RoomOutput>,
)

internal class RoomOutput(
    /** The static room template's own coordinate in the template bank ("level, x, z"). */
    val originatingTile: String,
    /** Where this room actually sits in this floor's private instance, when known. */
    val actualTile: String?,
    val rotation: Int?,
    val firstVisitTick: Int?,
    val critical: Boolean?,
    val doors: List<DoorOutput>,
    val resources: List<ResourceOutput>,
    val npcs: List<NpcOutput>,
    val floorItems: List<FloorItemOutput>,
)

internal class DoorOutput(
    val direction: String,
    val id: String,
    val kind: String,
    val keyNumber: Int?,
    /** True when this locked door's matching "rand_locked_door_barrier_<n>" was seen this floor. */
    val hasBarrier: Boolean,
    /** kind == "skill_obstruction" only: the skill needed to clear it, e.g. "firemaking". */
    val skill: String?,
    /** How many times this door/obstruction was clicked (an OpLoc landed on its tile). */
    val attempts: Int,
    /** kind == "skill_obstruction" only: whether it was seen to actually clear. */
    val cleared: Boolean?,
    /** The door object's own animations (LocAnim), e.g. swinging open, mapped name to count. */
    val objectSequences: Map<String, Int>,
    /** Gfx played at the door's tile (MapAnim), mapped name to count. */
    val objectSpotanims: Map<String, Int>,
    /**
     * The local player's animations while opening/failing to open this door, mapped name to
     * count - can't itself distinguish success from failure.
     */
    val playerSequences: Map<String, Int>,
    /** Gfx played on the local player while opening/failing to open this door, mapped name to count. */
    val playerSpotanims: Map<String, Int>,
    val tile: String,
)

internal class ResourceOutput(
    val id: String,
    val tile: String,
    /** How many times the node was clicked; one click keeps gathering until interrupted, so this isn't a gather count. */
    val attempts: Int,
    val spawns: Int,
    val depletions: Int,
    val depletedAfter: Int?,
    /**
     * How many resources the local player actually gathered from this node (xp drops in the node's
     * skill while it was the last node clicked), up to and including the one that depleted it.
     * Null in dumps written before this was tracked.
     */
    val gathers: Int?,
)

internal class NpcOutput(
    val id: Int,
    val sid: String,
    val tile: String,
    val count: Int,
    val sequences: Map<String, Int>,
    val spotanims: Map<String, Int>,
    val stats: Map<Int, NpcStatOutput>,
    /** Items dropped by this specific npc spawn point's kills. */
    val drops: List<FloorItemOutput>,
)

internal class NpcStatOutput(val baseLevel: Int, val minCurrentLevel: Int, val maxCurrentLevel: Int)

internal class FloorItemOutput(
    val id: String,
    val tile: String,
    val count: Int,
    val source: String,
    /** The tick this stack was first seen; compare with [RoomOutput.firstVisitTick]. Null in older dumps. */
    val firstTick: Int?,
)
