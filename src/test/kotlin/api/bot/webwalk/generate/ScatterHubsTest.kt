package api.bot.webwalk.generate

import api.bot.webwalk.data.WebWalkLoader
import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [ScatterHubs].
 *
 * @author Hydrozoa
 */
class ScatterHubsTest {

    private fun scatter(seed: Long = 377L, cellSize: Int = 24, occupied: Set<Pair<Int, Int>> = emptySet(),
                        standable: (Position) -> Boolean = { true }) =
        ScatterHubs.generate(seed, cellSize, 0, 0, 239, 239, 0, occupied, standable)

    @Test
    fun everyCellGetsOneHubInsideIt() {
        val hubs = scatter()

        assertEquals(100, hubs.size)
        for (hub in hubs) {
            val (cellX, cellY) = hub.id.removePrefix("scatter_").split("_").map { it.toInt() }
            assertEquals(cellX, hub.pos.x / 24)
            assertEquals(cellY, hub.pos.y / 24)
            assertEquals(setOf(ScatterHubs.TAG), hub.tags)
        }
        assertEquals(100, hubs.map { it.id }.toSet().size)
    }

    @Test
    fun hubsAreTheSameForTheSameSeedOnly() {
        assertEquals(scatter(), scatter())
        assertNotEquals(scatter(seed = 1), scatter(seed = 2))
    }

    @Test
    fun hubsAreSpreadRandomlyWithinTheirCells() {
        val offsets = scatter().map { Pair(it.pos.x % 24, it.pos.y % 24) }.toSet()

        assertTrue(offsets.size > 50, "The hubs are all in the same place of their cells.")
    }

    @Test
    fun neighbouringHubsAreWithinTheRangeOfTheCellSize() {
        val hubs = scatter().associateBy { it.id }
        for (hub in hubs.values) {
            val (cellX, cellY) = hub.id.removePrefix("scatter_").split("_").map { it.toInt() }
            for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
                val neighbour = hubs["scatter_${cellX + dx}_${cellY + dy}"] ?: continue
                assertTrue(hub.pos.computeLongestDistance(neighbour.pos) < 48)
            }
        }
    }

    @Test
    fun cellsWithoutAStandableTileOrWithAHubAreLeftEmpty() {
        val hubs = scatter(occupied = setOf(Pair(0, 0), Pair(1, 1))) { it.x >= 24 || it.y >= 24 }

        // Cell (0, 0) is occupied, (1, 1) is occupied, and the rest of the tiles that count are outside of (0, 0).
        assertEquals(98, hubs.size)
        assertTrue(hubs.none { it.id == "scatter_0_0" || it.id == "scatter_1_1" })
        assertTrue(scatter { false }.isEmpty())
    }

    @Test
    fun theAreaIsRespected() {
        val hubs = ScatterHubs.generate(377L, 24, 10, 10, 50, 50, 1, emptySet()) { true }

        assertTrue(hubs.isNotEmpty())
        assertTrue(hubs.all { it.pos.x in 10..50 && it.pos.y in 10..50 && it.pos.z == 1 })
    }

    @Test
    fun hubsOfTheWholeGridAreLoadable() {
        val text = WebWalkWriter.hubs(scatter(), emptyList())
        val graph = WebWalkLoader.fromSources(mapOf(WebWalkLoader.HUBS to text))

        assertEquals(100, graph.nodes.size)
    }
}
