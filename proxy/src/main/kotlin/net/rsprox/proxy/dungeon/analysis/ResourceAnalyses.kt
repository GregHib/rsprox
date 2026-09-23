package net.rsprox.proxy.dungeon.analysis

/**
 * Each resource node (a tier of ore, tree, fishing spot...) has its own depletion chance: every
 * resource gathered from it depletes it with chance `low..high / 255`, interpolated by the gatherer's
 * level in the node's skill. A node is therefore a run of Bernoulli trials ending at the first
 * depletion (or cut short, if the player left before it depleted), which is exactly what
 * [LevelTrialSet.addRun] models, and [LinearChanceModel] recovers low/high from many such runs.
 *
 * Accuracy depends on counting gathers, not clicks: a single click keeps gathering until the node
 * depletes. Dumps extracted before gathers were tracked fall back to clicks, which overstates the
 * chance, and the report says so.
 */
internal class ResourceDepletionAnalysis(
    /** Another party member gathering from the same node makes the local player's count too low. */
    private val soloOnly: Boolean = true,
) : DungeonAnalysis {
    override val id: String = "resource-depletion"
    override val title: String = "Resource depletion chance"
    override val description: String =
        "Per resource tier: chance/255 that a gather depletes the node, at level 1 (low) and 99 (high)."

    override fun analyse(dataset: DungeonDataset, report: AnalysisReport) {
        val trials = sortedMapOf<String, LevelTrialSet>()
        var fromClicks = 0
        var unusable = 0
        var skippedParty = 0
        for (room in dataset.rooms) {
            if (soloOnly && room.floor.partySize > 1) {
                skippedParty += room.resources.size
                continue
            }
            // Older dumps split one node into separate full and "_empty" entries on the same tile.
            for ((_, entries) in room.resources.groupBy { it.tile }) {
                val name = entries.firstNotNullOfOrNull { DungeonNames.resource(it.id) } ?: continue
                val level = room.floor.level(name.skill) ?: continue
                val depleted = entries.any { it.depletions > 0 }
                val recorded = entries.mapNotNull { it.gathers }
                val gathers = if (recorded.isNotEmpty()) recorded.sum() else entries.sumOf { it.attempts }.also { fromClicks++ }
                if (gathers <= 0) {
                    // Never gathered by the local player (or already depleted when first seen).
                    if (depleted) unusable++
                    continue
                }
                trials.getOrPut(name.key) { LevelTrialSet() }.addRun(level, gathers, depleted)
            }
        }
        if (fromClicks > 0) {
            report.note(
                "$fromClicks node(s) come from dumps without gather counts, so clicks were used as the " +
                    "number of trials; re-run `dungeonstats` on those dumps for accurate estimates.",
            )
        }
        if (unusable > 0) report.note("$unusable depleted node(s) had no local gathers (depleted by someone else) and were skipped.")
        if (skippedParty > 0) report.note("$skippedParty node(s) from party floors were skipped (solo floors only).")
        if (trials.isEmpty()) report.note("No gathered resource nodes found.")
        for ((key, data) in trials) {
            report.linearChance(key, "depletion", data)
        }
    }
}

/**
 * Which resources spawn: which skills appear at all (likely gated by complexity), which tier of each
 * (likely driven by the party's level in that skill and the floor), and how many nodes a room has.
 */
internal class ResourceSpawnAnalysis : DungeonAnalysis {
    override val id: String = "resource-spawn"
    override val title: String = "Resource spawns"
    override val description: String =
        "Which resource skills and tiers spawn, and how that depends on floor, complexity and skill level."

    private class Node(val floor: FloorObservation, val name: ResourceName)

    override fun analyse(dataset: DungeonDataset, report: AnalysisReport) {
        // Older dumps recorded a node's full and empty states as two entries; one tile is one node.
        val nodes =
            dataset.rooms.flatMap { room ->
                room.resources
                    .mapNotNull { resource -> DungeonNames.resource(resource.id)?.let { resource.tile to it } }
                    .distinctBy { it.first }
                    .map { Node(room.floor, it.second) }
            }
        if (nodes.isEmpty()) {
            report.note("No resource nodes found.")
            return
        }
        val floorCovariates = Covariate.floorCovariates<Node> { it.floor }
        report.categorical("all nodes", "skill", nodes, { it.name.skill }, floorCovariates)

        for ((skill, skillNodes) in nodes.groupBy { it.name.skill }.toSortedMap()) {
            val covariates =
                floorCovariates +
                    Covariate("$skill level") { node -> node.floor.level(skill)?.let { Covariate.bucket(it, 10) } }
            report.categorical(skill, "tier", skillNodes, { it.name.tier.toString() }, covariates)
            report.regression(
                skill,
                "tier",
                skillNodes.mapNotNull { node ->
                    val level = node.floor.level(skill) ?: return@mapNotNull null
                    RegressionRow(
                        node.name.tier.toDouble(),
                        mapOf(
                            "$skill level" to level.toDouble(),
                            "floor" to node.floor.floor.toDouble(),
                            "party size" to node.floor.partySize.toDouble(),
                            "complexity" to (node.floor.complexityIndex?.toDouble() ?: Double.NaN),
                        ),
                    )
                },
                listOf("$skill level", "floor", "party size", "complexity"),
            )
            // A tier cap by level would show up as a hard ceiling here rather than in the averages.
            val byLevel = skillNodes.groupBy { node -> node.floor.level(skill)?.let { Covariate.bucket(it, 10) } ?: "unknown" }
            report.table(
                ReportTable(
                    "$skill: tiers seen by $skill level",
                    listOf("$skill level", "n", "min tier", "max tier", "mean tier"),
                    byLevel.toSortedMap().map { (bucket, group) ->
                        val tiers = group.map { it.name.tier }
                        listOf(bucket, group.size.toString(), tiers.min().toString(), tiers.max().toString(), tiers.average().fmt(2))
                    },
                ),
            )
        }

        // Nodes per room, only counting rooms the player actually entered so every node was seen.
        val visited = dataset.rooms.filter { it.visited }
        for ((group, rooms) in visited.groupBy { "${it.floor.complexity} complexity, ${it.floor.size}" }.toSortedMap()) {
            val count = rooms.sumOf { room -> room.resources.distinctBy { it.tile }.count { DungeonNames.resource(it.id) != null } }
            report.rate(group, "resource nodes per visited room", count, rooms.size)
        }
    }
}
