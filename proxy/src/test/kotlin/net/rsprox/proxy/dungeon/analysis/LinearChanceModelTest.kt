package net.rsprox.proxy.dungeon.analysis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearChanceModelTest {
    private val model = LinearChanceModel()

    /** Simulates gathering from [nodes] nodes (at random levels in [levels]) until each depletes or [maxGathers] is hit. */
    private fun simulate(low: Int, high: Int, nodes: Int, levels: IntRange, seed: Int, maxGathers: Int = 20): LevelTrialSet {
        val random = Random(seed)
        val data = LevelTrialSet()
        repeat(nodes) {
            val level = levels.random(random)
            val p = model.probability(low, high, level)
            var gathers = 0
            var depleted = false
            while (!depleted && gathers < maxGathers) {
                gathers++
                depleted = random.nextDouble() < p
            }
            data.addRun(level, gathers, depleted)
        }
        return data
    }

    @Test
    fun `recovers low and high with many nodes`() {
        val fit = model.fit(simulate(low = 60, high = 20, nodes = 2_000, levels = 1..99, seed = 1))!!
        assertTrue(60 in fit.lowInterval, "low interval ${fit.lowInterval}")
        assertTrue(20 in fit.highInterval, "high interval ${fit.highInterval}")
        assertTrue(fit.levelDependencePValue < 0.001)
    }

    @Test
    fun `intervals narrow as data grows`() {
        val small = model.fit(simulate(low = 60, high = 20, nodes = 10, levels = 1..99, seed = 2))!!
        val large = model.fit(simulate(low = 60, high = 20, nodes = 100, levels = 1..99, seed = 2))!!
        val smallWidth = small.lowInterval.last - small.lowInterval.first
        val largeWidth = large.lowInterval.last - large.lowInterval.first
        assertTrue(largeWidth < smallWidth, "10 nodes: ${small.lowInterval}, 100 nodes: ${large.lowInterval}")
    }

    @Test
    fun `constant chance with a single level`() {
        val fit = model.fit(simulate(low = 100, high = 10, nodes = 500, levels = 50..50, seed = 3))!!
        val expected = model.chance(100, 10, 50)
        assertTrue(!fit.identifiable)
        assertTrue(expected in fit.constantInterval, "expected $expected in ${fit.constantInterval}")
    }

    @Test
    fun `wilson and chi-square sanity`() {
        assertEquals(0.05, Statistics.chiSquarePValue(Statistics.CHI2_95_DF1, 1), 1e-6)
        assertEquals(0.05, Statistics.studentTPValue(1.959963984540054, 100_000), 1e-3)
        val interval = Statistics.wilson(0, 10)
        assertEquals(0.0, interval.low, 1e-9)
        assertTrue(interval.high in 0.25..0.35)
    }
}
