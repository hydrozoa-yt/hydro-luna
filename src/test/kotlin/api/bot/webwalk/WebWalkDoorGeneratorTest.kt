package api.bot.webwalk

import io.luna.game.model.Position
import io.luna.game.model.`object`.ObjectDirection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [DoorObstacles], [HubSeeds], [WebWalkWriter] and [WebWalkDoorGenerator].
 *
 * @author Hydrozoa
 */
class WebWalkDoorGeneratorTest {

    companion object {

        /**
         * The map data, which takes a while to decode and is only decoded when a test needs it.
         */
        private val map by lazy { WebWalkDoorGenerator.loadMap() }
    }

    private fun wall(direction: ObjectDirection, x: Int = 10, y: Int = 10, type: EdgeType = EdgeType.DOOR) =
        PlacedWall(1512, Position(x, y, 0), direction, type)

    @Test
    fun wallsSeparateTheirTileFromTheTileTheyFace() {
        assertEquals(Position(9, 10), DoorObstacles.otherSide(Position(10, 10), ObjectDirection.WEST))
        assertEquals(Position(10, 11), DoorObstacles.otherSide(Position(10, 10), ObjectDirection.NORTH))
        assertEquals(Position(11, 10), DoorObstacles.otherSide(Position(10, 10), ObjectDirection.EAST))
        assertEquals(Position(10, 9), DoorObstacles.otherSide(Position(10, 10), ObjectDirection.SOUTH))
    }

    @Test
    fun obstaclesAreMadeOnlyWhereBothSidesCanBeStoodOn() {
        val report = GenerationReport()
        val blocked = Position(11, 10)
        val obstacles = DoorObstacles.generate(
            listOf(wall(ObjectDirection.WEST), wall(ObjectDirection.EAST), wall(ObjectDirection.NORTH, type = EdgeType.GATE)),
            { it != blocked }, report)

        assertEquals(2, obstacles.size)
        assertEquals(GeneratedObstacle(EdgeType.DOOR, 1512, Position(10, 10), Position(10, 10), Position(9, 10)),
                     obstacles[0])
        assertEquals(EdgeType.GATE, obstacles[1].type)
        assertEquals(1, report.skipped.size)
        assertTrue(report.skipped.single().contains("nothing to stand on at [11, 10, 0]"))
        assertEquals(mapOf("doors" to 1, "gates" to 1), report.counts)
    }

    @Test
    fun hubSeedsAreMovedOntoStandableTiles() {
        val report = GenerationReport()
        val seeds = listOf(HubSeed("bank", Position(10, 10), setOf("bank")),
                           HubSeed("lost", Position(50, 50)),
                           HubSeed("town", Position(10, 11), setOf("town")),
                           HubSeed("town", Position(30, 30)))
        // The booth is moved to the tile next to it, which the town also lands on.
        val hubs = HubSeeds.generate(seeds, { if (it.x == 50) null else Position(11, 11) }, report)

        assertEquals(listOf(GeneratedHub("bank", Position(11, 11), setOf("bank", "town"))), hubs)
        assertEquals(3, report.skipped.size)
    }

    @Test
    fun writerIsSortedAndStable() {
        val a = GeneratedObstacle(EdgeType.DOOR, 1512, Position(5, 5, 1), Position(5, 5, 1), Position(6, 5, 1))
        val b = GeneratedObstacle(EdgeType.LADDER, 1746, Position(9, 9), Position(9, 8), Position(9, 8, 1), "Climb-up")
        val text = WebWalkWriter.obstacles(listOf(a, b))

        assertEquals(WebWalkWriter.obstacles(listOf(b, a)), text)
        assertEquals("""
            [
              { "type": "LADDER", "object": 1746, "pos": [9, 9, 0], "from": [9, 8, 0], "to": [9, 8, 1], "option": "Climb-up" },
              { "type": "DOOR", "object": 1512, "pos": [5, 5, 1], "from": [5, 5, 1], "to": [6, 5, 1] }
            ]
        """.trimIndent() + "\n", text)
        assertEquals("[]\n", WebWalkWriter.obstacles(emptyList()))
    }

    @Test
    fun hubsAreWrittenAsALoadableFile() {
        val text = WebWalkWriter.hubs(
            listOf(GeneratedHub("b", Position(2, 2), setOf("town", "bank")), GeneratedHub("a", Position(1, 1))),
            listOf(GeneratedLink("b", "a", 7), GeneratedLink("a", "b", 7)))
        val graph = WebWalkLoader.fromSources(mapOf(WebWalkLoader.HUBS to text))

        assertEquals(setOf("a", "b"), graph.nodes.keys)
        assertEquals(setOf("bank", "town"), graph.node("b")!!.tags)
        // The same link written twice, in either direction, is one link: two directed edges.
        assertEquals(2, graph.edges.size)
        assertEquals(7, graph.edgesFrom("a").single().cost)
        assertEquals("{\n  \"hubs\": [],\n  \"edges\": []\n}\n", WebWalkWriter.hubs(emptyList(), emptyList()))
    }

    @Test
    fun committedObstaclesAreUpToDate() {
        val doors = Path.of("data/game/world/doors")
        val committed = Path.of("data/game/bots/webwalk").resolve(WebWalkLoader.OBSTACLES)
        assumeTrue(Files.exists(Path.of("data/game/cache/main_file_cache.dat")), "The cache is not available.")
        assumeTrue(Files.exists(committed), "obstacles.json has not been generated.")

        val result = WebWalkDoorGenerator.generate(doors, map)

        assertEquals(Files.readString(committed).replace("\r\n", "\n"), result.obstacles,
                     "obstacles.json is out of date: run './gradlew generateWebWalk'.")
    }

    @Test
    fun everyDoorIdIsFoundInTheMap() {
        assumeTrue(Files.exists(Path.of("data/game/cache/main_file_cache.dat")), "The cache is not available.")
        val closed = WebWalkDoorGenerator.readClosedIds(Path.of("data/game/world/doors"))
        val (obstacles, _) = WebWalkDoorGenerator.generate(map, closed)

        val found = obstacles.map { it.objectId }.toSet()
        assertTrue(closed.keys.any { it in found })
        assertTrue(obstacles.any { it.type == EdgeType.GATE })
        assertTrue(obstacles.any { it.type == EdgeType.CURTAIN })
        assertTrue(obstacles.all { it.from.computeLongestDistance(it.to) == 1 })
    }
}
