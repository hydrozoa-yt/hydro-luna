package api.bot.webwalk.generate

import api.bot.webwalk.data.WebWalkLoader
import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [ScatterWaypoints].
 *
 * @author Hydrozoa
 */
class ScatterWaypointsTest {

    private fun scatter(seed: Long = 377L, cellSize: Int = 24, occupied: Set<Pair<Int, Int>> = emptySet(),
                        standable: (Position) -> Boolean = { true }) =
        ScatterWaypoints.generate(seed, cellSize, 0, 0, 239, 239, 0, occupied, standable)

    @Test
    fun everyCellGetsOneWaypointInsideIt() {
        val waypoints = scatter()

        assertEquals(100, waypoints.size)
        for (waypoint in waypoints) {
            val (cellX, cellY) = waypoint.id.removePrefix("scatter_").split("_").map { it.toInt() }
            assertEquals(cellX, waypoint.pos.x / 24)
            assertEquals(cellY, waypoint.pos.y / 24)
            assertEquals(setOf(ScatterWaypoints.TAG), waypoint.tags)
        }
        assertEquals(100, waypoints.map { it.id }.toSet().size)
    }

    @Test
    fun waypointsAreTheSameForTheSameSeedOnly() {
        assertEquals(scatter(), scatter())
        assertNotEquals(scatter(seed = 1), scatter(seed = 2))
    }

    @Test
    fun waypointsAreSpreadRandomlyAroundTheCentresOfTheirCells() {
        val offsets = scatter().map { Pair(it.pos.x % 24, it.pos.y % 24) }.toSet()

        assertTrue(offsets.size > 50, "The waypoints are all in the same place of their cells.")
        // Every tile of the cell can be stood on, so the first try is the one that is used: within a quarter of a cell from the centre.
        assertTrue(offsets.all { (x, y) -> x in 6..18 && y in 6..18 }, "The waypoints are not close to the centres of their cells.")
    }

    @Test
    fun waypointsFarFromTheCentreAreUsedWhenTheCentreCantBeStoodOn() {
        val waypoints = scatter { it.x % 24 !in 4..19 || it.y % 24 !in 4..19 }

        assertTrue(waypoints.size > 50)
        assertTrue(waypoints.all { it.pos.x % 24 !in 4..19 || it.pos.y % 24 !in 4..19 })
    }

    @Test
    fun neighbouringWaypointsAreWithinTheRangeOfTheCellSize() {
        val waypoints = scatter().associateBy { it.id }
        for (waypoint in waypoints.values) {
            val (cellX, cellY) = waypoint.id.removePrefix("scatter_").split("_").map { it.toInt() }
            for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
                val neighbour = waypoints["scatter_${cellX + dx}_${cellY + dy}"] ?: continue
                assertTrue(waypoint.pos.computeLongestDistance(neighbour.pos) < 48)
            }
        }
    }

    @Test
    fun cellsWithoutAStandableTileOrWithAWaypointAreLeftEmpty() {
        val waypoints = scatter(occupied = setOf(Pair(0, 0), Pair(1, 1))) { it.x >= 24 || it.y >= 24 }

        // Cell (0, 0) is occupied, (1, 1) is occupied, and the rest of the tiles that count are outside of (0, 0).
        assertEquals(98, waypoints.size)
        assertTrue(waypoints.none { it.id == "scatter_0_0" || it.id == "scatter_1_1" })
        assertTrue(scatter { false }.isEmpty())
    }

    @Test
    fun theAreaIsRespected() {
        val waypoints = ScatterWaypoints.generate(377L, 24, 10, 10, 50, 50, 1, emptySet()) { true }

        assertTrue(waypoints.isNotEmpty())
        assertTrue(waypoints.all { it.pos.x in 10..50 && it.pos.y in 10..50 && it.pos.z == 1 })
    }

    @Test
    fun waypointsOfTheWholeGridAreLoadable() {
        val text = WebWalkWriter.walkGraph(scatter(), emptyList())
        val graph = WebWalkLoader.fromSources(mapOf(WebWalkLoader.WALK_GRAPH to text))

        assertEquals(100, graph.nodes.size)
    }
}
