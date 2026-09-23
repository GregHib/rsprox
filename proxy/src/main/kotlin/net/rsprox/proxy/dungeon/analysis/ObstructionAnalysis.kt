package net.rsprox.proxy.dungeon.analysis

/**
 * Blocked doors: the chance each click on a skill-gated obstruction (e.g. burning down a fire with
 * Firemaking) clears it, modelled like resource depletion as `low..high / 255` by the player's level
 * in that skill - each obstruction is a run of clicks ending at the one that cleared it. Also how
 * often each kind of blocked door (guardian, locked, skill obstruction) appears.
 *
 * The real chance probably depends on the player's level *relative to the obstruction's requirement*,
 * which isn't in the dump; if the fit is poor or level-independent, that's the likely reason.
 */
internal class ObstructionAnalysis(
    /** On party floors another member may clear an obstruction the local player was clicking. */
    private val soloOnly: Boolean = true,
) : DungeonAnalysis {
    override val id: String = "obstruction"
    override val title: String = "Blocked doors"
    override val description: String = "Chance a click clears a skill obstruction, and how often each blocked door kind appears."

    private class Door(val floor: FloorObservation, val kind: String, val skill: String?)

    override fun analyse(dataset: DungeonDataset, report: AnalysisReport) {
        val bySkill = sortedMapOf<String, LevelTrialSet>()
        val pooled = LevelTrialSet()
        var neverClicked = 0
        var unknownLevel = 0
        for (room in dataset.rooms) {
            if (soloOnly && room.floor.partySize > 1) continue
            for (door in room.doors) {
                if (door.kind != "skill_obstruction") continue
                val skill = door.skill ?: continue
                if (door.attempts <= 0) {
                    neverClicked++
                    continue
                }
                val level = room.floor.level(skill)
                if (level == null) {
                    unknownLevel++
                    continue
                }
                val cleared = door.cleared == true
                bySkill.getOrPut(skill) { LevelTrialSet() }.addRun(level, door.attempts, cleared)
                pooled.addRun(level, door.attempts, cleared)
            }
        }
        if (neverClicked > 0) report.note("$neverClicked obstruction(s) were never clicked and carry no information about the clear chance.")
        if (unknownLevel > 0) report.note("$unknownLevel obstruction(s) were skipped because the player's level in the skill wasn't recorded.")
        if (pooled.trials == 0) {
            report.note("No clicked skill obstructions found.")
        } else {
            for ((skill, data) in bySkill) report.linearChance("$skill obstruction", "clear", data)
            report.linearChance("all obstructions (pooled)", "clear", pooled)
        }

        val doors = dataset.rooms.flatMap { room -> room.doors.map { Door(room.floor, it.kind, it.skill) } }
        val visited = dataset.rooms.filter { it.visited }
        val visitedRooms = visited.size
        for ((kind, group) in visited.flatMap { it.doors }.groupBy { it.kind }.toSortedMap()) {
            report.rate(kind, "per visited room", group.size, visitedRooms, "${group.size} seen across $visitedRooms visited rooms")
        }
        val covariates = Covariate.floorCovariates<Door> { it.floor }
        report.categorical("blocked doors", "kind", doors, { it.kind }, covariates)
        report.categorical("skill obstructions", "skill", doors.filter { it.skill != null }, { it.skill }, covariates)
    }
}
