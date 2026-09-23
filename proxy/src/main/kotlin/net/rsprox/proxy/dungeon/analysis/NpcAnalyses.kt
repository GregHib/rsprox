package net.rsprox.proxy.dungeon.analysis

import net.rsprox.proxy.dungeon.NpcOutput

/** The predictors every npc-related regression uses; ones that never vary are dropped automatically. */
private val NPC_PREDICTORS = listOf("floor", "combat level", "party size", "complexity", "size", "difficulty")

private fun npcPredictors(floor: FloorObservation): Map<String, Double> =
    mapOf(
        "floor" to floor.floor.toDouble(),
        "combat level" to (floor.combatLevel?.toDouble() ?: Double.NaN),
        "party size" to floor.partySize.toDouble(),
        "complexity" to (floor.complexityIndex?.toDouble() ?: Double.NaN),
        "size" to (floor.sizeIndex?.toDouble() ?: Double.NaN),
        "difficulty" to floor.difficulty.toDouble(),
    )

/**
 * Which boss a floor gets, and which variant of it. Bosses are named e.g. "rand_ice_lord_boss_3": the
 * family is expected to depend on the floor's theme, the trailing variant perhaps on combat level.
 */
internal class BossTypeAnalysis : DungeonAnalysis {
    override val id: String = "boss-type"
    override val title: String = "Boss type"
    override val description: String = "Which boss (and variant) spawns, by theme, floor, size, party and combat level."

    private class Boss(val floor: FloorObservation, val name: NpcName)

    override fun analyse(dataset: DungeonDataset, report: AnalysisReport) {
        val bosses =
            dataset.floors.mapNotNull { floor ->
                val rooms = floor.rooms
                val bossRoom = rooms.firstOrNull { it.originatingTile == floor.output.bossRoomTile }
                val npc =
                    bossRoom?.npcs?.firstOrNull { DungeonNames.isBoss(it.sid) }
                        ?: rooms.flatMap { it.npcs }.firstOrNull { DungeonNames.isBoss(it.sid) }
                        ?: return@mapNotNull null
                Boss(floor, DungeonNames.npc(npc.sid))
            }
        val missing = dataset.floors.size - bosses.size
        if (missing > 0) report.note("$missing floor(s) had no identifiable boss npc and were skipped.")
        if (bosses.isEmpty()) return
        val covariates = Covariate.floorCovariates<Boss> { it.floor }
        report.categorical("all floors", "boss", bosses, { it.name.family }, covariates)
        for ((family, group) in bosses.groupBy { it.name.family }.toSortedMap()) {
            report.categorical(family, "variant", group, { it.name.variant?.toString() }, covariates)
            report.regression(
                family,
                "variant",
                group.mapNotNull { boss -> boss.name.variant?.let { RegressionRow(it.toDouble(), npcPredictors(boss.floor)) } },
                NPC_PREDICTORS,
            )
        }
    }
}

/**
 * How many combat npcs spawn in an ordinary room (not the start or boss room), counting only rooms
 * the player entered so the room's whole population was in view. Also which kinds of npc they are.
 */
internal class NpcSpawnCountAnalysis : DungeonAnalysis {
    override val id: String = "npc-count"
    override val title: String = "Npc spawns per room"
    override val description: String = "Number and kind of combat npcs per room, by floor, size, party and combat level."

    private class Spawn(val floor: FloorObservation, val name: NpcName)

    override fun analyse(dataset: DungeonDataset, report: AnalysisReport) {
        val rooms = dataset.rooms.filter { it.visited && !it.isStart && !it.isBoss }
        if (rooms.isEmpty()) {
            report.note("No visited ordinary rooms found.")
            return
        }
        val counts = rooms.map { room -> room to room.npcs.count { DungeonNames.isCombatNpc(it.sid) && !DungeonNames.isBoss(it.sid) } }
        report.table(
            ReportTable(
                "combat npcs per room: distribution",
                listOf("npcs", "rooms", "share"),
                counts.groupingBy { it.second }.eachCount().toSortedMap().map { (npcs, n) ->
                    listOf(npcs.toString(), n.toString(), (n.toDouble() / counts.size).fmt())
                },
            ),
        )
        for ((group, entries) in counts.groupBy { "party ${it.first.floor.partySize}, ${it.first.floor.size}" }.toSortedMap()) {
            report.rate(group, "combat npcs per room", entries.sumOf { it.second }, entries.size)
        }
        report.regression(
            "ordinary rooms",
            "combat npcs",
            counts.map { (room, count) -> RegressionRow(count.toDouble(), npcPredictors(room.floor)) },
            NPC_PREDICTORS,
        )
        // Which npc families appear, which likely depends on theme more than anything else.
        val spawns =
            rooms.flatMap { room ->
                room.npcs
                    .filter { DungeonNames.isCombatNpc(it.sid) && !DungeonNames.isBoss(it.sid) }
                    .map { Spawn(room.floor, DungeonNames.npc(it.sid)) }
            }
        report.categorical("combat npcs", "family", spawns, { it.name.family }, Covariate.floorCovariates { it.floor })
    }
}

/**
 * Npc levels/stats: the stats mask carries each npc's base level per stat slot (e.g. slot 3 is its
 * lifepoints). Expected to scale with the variant (tier) and the party's combat level; which tier
 * spawns is itself tested against combat level and floor.
 */
internal class NpcStatsAnalysis : DungeonAnalysis {
    override val id: String = "npc-stats"
    override val title: String = "Npc levels and stats"
    override val description: String = "Npc base stats and tiers against combat level, floor, party size and variant."

    private class Observed(val floor: FloorObservation, val npc: NpcOutput, val name: NpcName)

    override fun analyse(dataset: DungeonDataset, report: AnalysisReport) {
        val npcs =
            dataset.rooms.flatMap { room ->
                room.npcs.filter { DungeonNames.isCombatNpc(it.sid) }.map { Observed(room.floor, it, DungeonNames.npc(it.sid)) }
            }
        if (npcs.isEmpty()) {
            report.note("No combat npcs found.")
            return
        }
        // Which tier spawns, pooled over every family that has a numeric tier.
        val tiered = npcs.filter { it.name.variant != null && !DungeonNames.isBoss(it.npc.sid) }
        report.regression(
            "all combat npcs",
            "tier",
            tiered.map { RegressionRow(it.name.variant!!.toDouble(), npcPredictors(it.floor)) },
            NPC_PREDICTORS,
        )
        report.categorical("all combat npcs", "tier", tiered, { it.name.variant.toString() }, Covariate.floorCovariates { it.floor })

        val summary = mutableListOf<List<String>>()
        for ((family, group) in npcs.groupBy { it.name.family }.toSortedMap()) {
            @Suppress("USELESS_ELVIS")
            val slots = group.flatMap { (it.npc.stats ?: emptyMap()).keys }.distinct().sorted()
            for (slot in slots) {
                val withStat = group.mapNotNull { obs -> obs.npc.stats[slot]?.takeIf { it.baseLevel > 0 }?.let { obs to it.baseLevel } }
                if (withStat.isEmpty()) continue
                val levels = withStat.map { it.second.toDouble() }
                summary +=
                    listOf(
                        family,
                        slot.toString(),
                        withStat.size.toString(),
                        levels.min().toInt().toString(),
                        levels.max().toInt().toString(),
                        Statistics.mean(levels).fmt(1),
                        Statistics.meanInterval(levels).format(1),
                    )
                report.regression(
                    "$family stat $slot",
                    "base level",
                    withStat.map { (obs, level) ->
                        RegressionRow(level.toDouble(), npcPredictors(obs.floor) + ("variant" to (obs.name.variant?.toDouble() ?: Double.NaN)))
                    },
                    listOf("variant") + NPC_PREDICTORS,
                )
            }
        }
        report.table(
            ReportTable(
                "base stat level by family and slot",
                listOf("family", "slot", "n", "min", "max", "mean", "mean 95% CI"),
                summary,
            ),
        )
    }
}
