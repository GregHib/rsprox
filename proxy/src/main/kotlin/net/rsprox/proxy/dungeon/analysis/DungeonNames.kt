package net.rsprox.proxy.dungeon.analysis

/** A resource node's name broken into its parts, e.g. "rand_mining_resource_empty_frzn_4". */
internal class ResourceName(val skill: String, val theme: String?, val tier: Int, val empty: Boolean) {
    /** Groups the same node across themes: frozen/abandoned/etc. copies of a tier are assumed identical. */
    val key: String get() = "$skill tier $tier"
}

/** An npc's name split into its family and trailing variant number, e.g. "rand_ice_fiend" + 1. */
internal class NpcName(val family: String, val variant: Int?)

internal object DungeonNames {
    private val RESOURCE_REGEX = Regex("""^rand_([a-z]+)_resource(_empty)?(?:_([a-z]+))?_(\d+)$""")
    private val TRAILING_NUMBER_REGEX = Regex("""^(.*?)_(\d+)$""")

    /** Npcs that aren't combat encounters, so shouldn't count towards a room's spawns. */
    private val NON_COMBAT_NPC_PATTERNS =
        listOf("smuggler", "divination_wisp", "huntable_npc", "fishing", "_resource")

    fun resource(name: String): ResourceName? {
        val match = RESOURCE_REGEX.matchEntire(name) ?: return null
        val (skill, empty, theme, tier) = match.destructured
        return ResourceName(skill, theme.ifEmpty { null }, tier.toInt(), empty.isNotEmpty())
    }

    fun npc(sid: String): NpcName {
        val match = TRAILING_NUMBER_REGEX.matchEntire(sid) ?: return NpcName(sid, null)
        return NpcName(match.groupValues[1], match.groupValues[2].toInt())
    }

    fun isBoss(sid: String): Boolean = sid.contains("_boss")

    /**
     * Npcs summoned mid-fight by another npc rather than spawned with the room, e.g. the zombies
     * raised by rand_human_necromancy; counting them would inflate room spawns and skew stats.
     */
    private val SUMMONED_NPC_PATTERNS = listOf("rand_zombie_melee")

    /** A combat npc the room itself spawned: excludes non-combat npcs and [SUMMONED_NPC_PATTERNS]. */
    fun isCombatNpc(sid: String): Boolean =
        NON_COMBAT_NPC_PATTERNS.none { sid.contains(it) } && SUMMONED_NPC_PATTERNS.none { sid.startsWith(it) }
}
