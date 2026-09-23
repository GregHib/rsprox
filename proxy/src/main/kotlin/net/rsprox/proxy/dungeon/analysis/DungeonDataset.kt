package net.rsprox.proxy.dungeon.analysis

import com.google.gson.Gson
import com.google.gson.JsonParseException
import net.rsprox.proxy.dungeon.DoorOutput
import net.rsprox.proxy.dungeon.FloorOutput
import net.rsprox.proxy.dungeon.NpcOutput
import net.rsprox.proxy.dungeon.ResourceOutput
import net.rsprox.proxy.dungeon.RoomOutput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * Every floor loaded from a directory of DungeonStatisticsCommand output, wrapped in [FloorObservation]
 * so analyses can ask for derived covariates (theme, combat level, a skill's level...) in one place.
 */
internal class DungeonDataset(val floors: List<FloorObservation>) {
    val rooms: List<RoomObservation> by lazy { floors.flatMap { floor -> floor.rooms.map { RoomObservation(floor, it) } } }

    companion object {
        private val gson = Gson()

        /**
         * Reads the per-floor `.json` files under [directory] (recursively). The combined
         * `dungeon-stats.jsonl` is deliberately ignored: it's appended to on every run of the dumper,
         * so it accumulates duplicates. Floors are still de-duplicated by source file + start tick in
         * case the same dump was extracted into two places.
         */
        fun load(directory: Path, onError: (Path, Exception) -> Unit = { _, _ -> }): DungeonDataset {
            require(directory.isDirectory()) { "Not a directory: $directory" }
            val files = Files.walk(directory).use { stream -> stream.filter { it.extension == "json" }.sorted().toList() }
            val floors = linkedMapOf<String, FloorObservation>()
            for (file in files) {
                val output =
                    try {
                        gson.fromJson(file.readText(), FloorOutput::class.java)
                    } catch (e: JsonParseException) {
                        onError(file, e)
                        continue
                    } ?: continue
                // Not every .json under the directory is necessarily a floor (e.g. an analysis report).
                @Suppress("SENSELESS_COMPARISON")
                if (output.sourceFile == null || output.rooms == null) continue
                floors.putIfAbsent("${output.sourceFile}@${output.startTick}", FloorObservation(output))
            }
            return DungeonDataset(floors.values.toList())
        }
    }
}

/**
 * One floor plus the covariates analyses condition on. Collections are read through `orEmpty()`
 * because Gson leaves fields that are absent from older dumps as null (see DungeonStatsFormat.kt).
 */
@Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
internal class FloorObservation(val output: FloorOutput) {
    val floor: Int get() = output.floor
    val complexity: String get() = output.complexity ?: "unknown"
    val size: String get() = output.size ?: "unknown"
    val partySize: Int get() = output.partySize
    val difficulty: Int get() = output.difficulty
    val rooms: List<RoomOutput> get() = output.rooms?.filterNotNull().orEmpty()
    val levels: Map<String, Int> get() = output.levelsAtStart ?: emptyMap()

    /** The complexity/size as an ordinal, for regression; null when unrecognised. */
    val complexityIndex: Int? get() = ordinal(COMPLEXITIES, complexity)
    val sizeIndex: Int? get() = ordinal(SIZES, size)

    val theme: String
        get() = THEMES.firstOrNull { floor in it.first }?.second ?: "unknown"

    /**
     * The local player's level in [skill] at the start of the floor. Only the recording player's own
     * levels are ever known - other party members' aren't in the dump - so for a party this is at best
     * one member's level, which is why most analyses default to solo floors where it matters.
     */
    fun level(skill: String): Int? = levels[SKILL_ALIASES[skill] ?: skill]

    /**
     * RS3 combat level of the recording player:
     * `(1.3 * max(att + str, 2 * mag, 2 * rng, 2 * necro) + def + con + pray / 2 + summ / 2) / 4`.
     */
    val combatLevel: Int?
        get() {
            val attack = level("attack") ?: return null
            val strength = level("strength") ?: return null
            val magic = level("magic") ?: return null
            val ranged = level("ranged") ?: return null
            val defence = level("defence") ?: return null
            val constitution = level("constitution") ?: return null
            val prayer = level("prayer") ?: return null
            val necromancy = level("necromancy") ?: 1
            val summoning = level("summoning") ?: 1
            val offence = maxOf(attack + strength, 2 * magic, 2 * ranged, 2 * necromancy)
            val total = 1.3 * offence + defence + constitution + prayer / 2 + summoning / 2
            return (total / 4).toInt()
        }

    companion object {
        val COMPLEXITIES = listOf("Low", "Medium", "High")
        val SIZES = listOf("Small", "Medium", "Large")

        /** Daemonheim's floor themes by floor number. */
        val THEMES =
            listOf(
                1..11 to "frozen",
                12..17 to "abandoned",
                18..29 to "furnished",
                30..35 to "abandoned2",
                36..47 to "occult",
                48..60 to "warped",
            )

        /** Loc/obstruction names don't always use the same spelling as the stat names. */
        val SKILL_ALIASES = mapOf("runecraft" to "runecrafting", "hp" to "constitution", "hitpoints" to "constitution")

        private fun ordinal(values: List<String>, value: String): Int? =
            values.indexOfFirst { it.equals(value, ignoreCase = true) }.takeIf { it >= 0 }
    }
}

@Suppress("USELESS_ELVIS")
internal class RoomObservation(val floor: FloorObservation, val room: RoomOutput) {
    val npcs: List<NpcOutput> get() = room.npcs.orEmpty()
    val resources: List<ResourceOutput> get() = room.resources.orEmpty()
    val doors: List<DoorOutput> get() = room.doors.orEmpty()
    val visited: Boolean get() = room.firstVisitTick != null
    val isStart: Boolean get() = room.originatingTile == floor.output.startRoomTile
    val isBoss: Boolean get() = room.originatingTile == floor.output.bossRoomTile
}

/** A floor-level property to condition an outcome on, e.g. its complexity or theme. */
internal class Covariate<T>(val name: String, val value: (T) -> String?) {
    companion object {
        fun <T> ofFloor(name: String, value: (FloorObservation) -> String?, floorOf: (T) -> FloorObservation): Covariate<T> =
            Covariate(name) { value(floorOf(it)) }

        /** The standard floor-level covariates every categorical outcome is tested against. */
        fun <T> floorCovariates(floorOf: (T) -> FloorObservation): List<Covariate<T>> =
            listOf(
                ofFloor("theme", { it.theme }, floorOf),
                ofFloor("floor", { it.floor.toString() }, floorOf),
                ofFloor("complexity", { it.complexity }, floorOf),
                ofFloor("size", { it.size }, floorOf),
                ofFloor("party size", { it.partySize.toString() }, floorOf),
                ofFloor("difficulty", { it.difficulty.toString() }, floorOf),
                ofFloor("combat level", { it.combatLevel?.let { level -> bucket(level, 10) } }, floorOf),
            )

        fun bucket(value: Int, width: Int): String {
            val start = (value / width) * width
            return "$start-${start + width - 1}"
        }
    }
}
