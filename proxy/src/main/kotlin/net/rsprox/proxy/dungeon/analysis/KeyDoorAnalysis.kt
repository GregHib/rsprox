package net.rsprox.proxy.dungeon.analysis

import net.rsprox.proxy.dungeon.FloorItemOutput
import kotlin.math.abs

/**
 * Key doors ("rand_locked_door_<n>_<theme>") and their keys ("rand_key_<n>"): how many a floor gets,
 * whether every door's key turns up (and vice versa), where keys come from (already lying in a room
 * when it's opened, or appearing later, e.g. as a reward for clearing the room), how far a key is from
 * its door, and whether key numbers are uniformly random.
 */
internal class KeyDoorAnalysis : DungeonAnalysis {
    override val id: String = "key-doors"
    override val title: String = "Key doors"
    override val description: String =
        "Locked door counts, key/door pairing, where keys spawn, key-to-door distance and key number distribution."

    private class LockedDoor(val floor: FloorObservation, val room: RoomObservation, val keyNumber: Int, val hasBarrier: Boolean)

    private class Key(val floor: FloorObservation, val room: RoomObservation, val keyNumber: Int, val item: FloorItemOutput)

    override fun analyse(dataset: DungeonDataset, report: AnalysisReport) {
        val doors =
            dataset.rooms.flatMap { room ->
                room.doors
                    .filter { it.kind == "locked_door" && it.keyNumber != null }
                    .map { LockedDoor(room.floor, room, it.keyNumber!!, it.hasBarrier) }
            }
        val keys =
            dataset.rooms.flatMap { room ->
                // A key can also be credited to an npc's drops when it lands on that npc's death tile.
                val items = room.room.floorItems.orEmpty() + room.npcs.flatMap { it.drops.orEmpty() }
                items.mapNotNull { item -> keyNumber(item.id)?.let { Key(room.floor, room, it, item) } }
            }
        if (doors.isEmpty() && keys.isEmpty()) {
            report.note("No key doors or keys found.")
            return
        }
        perFloorCounts(dataset, doors, report)
        pairing(dataset, doors, keys, report)
        keySources(keys, report)
        distances(dataset, doors, keys, report)
        keyNumbers(doors, report)
    }

    private fun perFloorCounts(dataset: DungeonDataset, doors: List<LockedDoor>, report: AnalysisReport) {
        val byFloor = doors.groupBy { it.floor }
        // Doors in rooms that were never loaded can't be seen, so only completed floors count here.
        val floors = dataset.floors.filter { it.output.completed }
        if (floors.size < dataset.floors.size) {
            report.note("${dataset.floors.size - floors.size} incomplete floor(s) excluded from per-floor key door counts.")
        }
        for ((group, entries) in floors.groupBy { "${it.complexity} complexity, ${it.size}" }.toSortedMap()) {
            report.rate(group, "key doors per floor", entries.sumOf { byFloor[it]?.size ?: 0 }, entries.size)
        }
        for ((group, entries) in floors.groupBy { "${it.complexity} complexity, ${it.size}" }.toSortedMap()) {
            val rooms = entries.sumOf { floor -> floor.rooms.count { it.firstVisitTick != null } }
            val count = entries.sumOf { floor -> byFloor[floor]?.count { it.room.visited } ?: 0 }
            report.rate(group, "key doors per visited room", count, rooms)
        }
        report.table(
            ReportTable(
                "key doors per floor: distribution",
                listOf("key doors", "floors", "share"),
                floors.groupingBy { byFloor[it]?.size ?: 0 }.eachCount().toSortedMap().map { (n, count) ->
                    listOf(n.toString(), count.toString(), (count.toDouble() / floors.size).fmt())
                },
            ),
        )
        report.regression(
            "completed floors",
            "key doors",
            floors.map { floor ->
                RegressionRow(
                    (byFloor[floor]?.size ?: 0).toDouble(),
                    mapOf(
                        "floor" to floor.floor.toDouble(),
                        "complexity" to (floor.complexityIndex?.toDouble() ?: Double.NaN),
                        "size" to (floor.sizeIndex?.toDouble() ?: Double.NaN),
                        "party size" to floor.partySize.toDouble(),
                        "rooms" to floor.rooms.size.toDouble(),
                    ),
                )
            },
            listOf("floor", "complexity", "size", "party size", "rooms"),
        )
        report.proportion("key doors", "have a barrier", doors.count { it.hasBarrier }, doors.size)
    }

    private fun pairing(dataset: DungeonDataset, doors: List<LockedDoor>, keys: List<Key>, report: AnalysisReport) {
        var doorsWithKey = 0
        var keysWithDoor = 0
        var duplicateNumbers = 0
        for (floor in dataset.floors) {
            val doorNumbers = doors.filter { it.floor == floor }.map { it.keyNumber }
            val keyNumbers = keys.filter { it.floor == floor }.map { it.keyNumber }.toSet()
            doorsWithKey += doorNumbers.count { it in keyNumbers }
            keysWithDoor += keyNumbers.count { it in doorNumbers }
            duplicateNumbers += doorNumbers.size - doorNumbers.toSet().size
        }
        val distinctKeys = keys.map { it.floor to it.keyNumber }.distinct().size
        report.proportion("key doors", "matching key seen on the floor", doorsWithKey, doors.size)
        report.proportion("keys", "matching door seen on the floor", keysWithDoor, distinctKeys)
        if (duplicateNumbers > 0) report.note("$duplicateNumbers key door(s) shared a key number with another door on the same floor.")
    }

    /**
     * A key already lying in a room shows up within a tick or two of the room being entered (the room's
     * contents load as its door opens); one that appears well after the room was entered was produced by
     * something that happened in it, e.g. killing its last npc.
     */
    private fun keySources(keys: List<Key>, report: AnalysisReport) {
        val sources = mutableListOf<Pair<Key, String>>()
        val delays = mutableListOf<Int>()
        var withoutTicks = 0
        for (key in keys) {
            val visit = key.room.room.firstVisitTick
            val first = key.item.firstTick
            val source =
                when {
                    key.room.isStart -> "start room"
                    first == null || visit == null -> {
                        withoutTicks++
                        if (key.item.source == "spawn") "with room" else "unknown"
                    }
                    first - visit <= KEY_WITH_ROOM_TICKS -> "with room"
                    else -> "later".also { delays += first - visit }
                }
            sources += key to source
        }
        if (withoutTicks > 0) {
            report.note(
                "$withoutTicks key(s) come from dumps without item timings, so couldn't be told apart as lying in the " +
                    "room vs appearing later; re-run `dungeonstats` on those dumps.",
            )
        }
        val known = sources.filter { it.second != "unknown" }
        for (source in listOf("start room", "with room", "later")) {
            report.proportion("keys", "source: $source", known.count { it.second == source }, known.size)
        }
        if (delays.isNotEmpty()) {
            val values = delays.map { it.toDouble() }
            report.finding(
                Finding(
                    subject = "keys appearing later",
                    measure = "ticks after room entered",
                    estimate = Statistics.mean(values).fmt(1),
                    interval = Statistics.meanInterval(values).format(1),
                    sampleSize = delays.size,
                    pValue = null,
                    hypothesis = null,
                    confidence = Confidence.of(delays.size, 0.3),
                    detail = "min ${delays.min()}, max ${delays.max()}",
                ),
            )
        }
        report.categorical(
            "keys",
            "source",
            known,
            { it.second },
            Covariate.floorCovariates { it.first.floor },
        )
    }

    /** Grid distance in rooms between each key and its door, using where the rooms sit in the instance. */
    private fun distances(dataset: DungeonDataset, doors: List<LockedDoor>, keys: List<Key>, report: AnalysisReport) {
        val distances = mutableListOf<Int>()
        for (floor in dataset.floors) {
            val floorKeys = keys.filter { it.floor == floor }.associateBy { it.keyNumber }
            for (door in doors.filter { it.floor == floor }) {
                val key = floorKeys[door.keyNumber] ?: continue
                val a = parseTile(door.room.room.actualTile) ?: continue
                val b = parseTile(key.room.room.actualTile) ?: continue
                distances += (abs(a.first - b.first) + abs(a.second - b.second)) / ROOM_SIZE
            }
        }
        if (distances.isEmpty()) return
        val values = distances.map { it.toDouble() }
        report.finding(
            Finding(
                subject = "keys",
                measure = "rooms from their door (grid distance)",
                estimate = Statistics.mean(values).fmt(2),
                interval = Statistics.meanInterval(values).format(2),
                sampleSize = distances.size,
                pValue = null,
                hypothesis = null,
                confidence = Confidence.of(distances.size, 0.3),
                detail = "0 = key in the same room as its door",
            ),
        )
        report.table(
            ReportTable(
                "key to door distance (rooms)",
                listOf("rooms", "keys", "share"),
                distances.groupingBy { it }.eachCount().toSortedMap().map { (d, n) ->
                    listOf(d.toString(), n.toString(), (n.toDouble() / distances.size).fmt())
                },
            ),
        )
    }

    /**
     * Key numbers are assumed to be 0..63, encoding one of 8 colours and one of 8 shapes; if keys are
     * picked uniformly at random, both `n / 8` and `n % 8` should be uniform.
     */
    private fun keyNumbers(doors: List<LockedDoor>, report: AnalysisReport) {
        if (doors.isEmpty()) return
        val categories = (0 until 8).map { it.toString() }
        val tests =
            listOf(
                "key number / 8 (colour?)" to doors.groupingBy { (it.keyNumber / 8).toString() }.eachCount(),
                "key number % 8 (shape?)" to doors.groupingBy { (it.keyNumber % 8).toString() }.eachCount(),
            )
        for ((name, counts) in tests) {
            val test = GoodnessOfFitTest.uniform(counts, categories)
            report.finding(
                Finding(
                    subject = "key doors",
                    measure = "$name is uniform",
                    estimate = "chi2 ${test.statistic.fmt(2)}",
                    interval = null,
                    sampleSize = test.total,
                    pValue = test.pValue,
                    hypothesis = "every value of $name is equally likely",
                    confidence = Confidence.of(test.total, 0.3, minimumSample = 40),
                    detail = "${test.method}, df=${test.degreesOfFreedom}",
                ),
            )
            report.table(
                ReportTable(
                    "key doors by $name",
                    listOf(name, "doors"),
                    categories.map { listOf(it, (counts[it] ?: 0).toString()) },
                ),
            )
        }
        val range = doors.minOf { it.keyNumber }..doors.maxOf { it.keyNumber }
        report.note("Key numbers seen: ${range.first}..${range.last}; the 0..63 colour/shape split above is an assumption.")
    }

    private fun keyNumber(itemId: String): Int? = KEY_REGEX.matchEntire(itemId)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseTile(tile: String?): Pair<Int, Int>? {
        val parts = tile?.split(",")?.map { it.trim().toIntOrNull() } ?: return null
        val x = parts.getOrNull(0) ?: return null
        val z = parts.getOrNull(1) ?: return null
        return x to z
    }

    private companion object {
        val KEY_REGEX = Regex("""^rand_key_(\d+)$""")
        const val ROOM_SIZE = 16

        /** A key first seen within this many ticks of its room being entered was already lying there. */
        const val KEY_WITH_ROOM_TICKS = 2
    }
}
