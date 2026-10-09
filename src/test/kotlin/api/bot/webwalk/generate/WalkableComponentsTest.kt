package api.bot.webwalk.generate

import api.bot.webwalk.model.NodeKind
import api.bot.webwalk.model.WebWalkNode
import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [WalkableComponents].
 *
 * @author Hydrozoa
 */
class WalkableComponentsTest {

    /**
     * A 20 by 20 world with a wall between x = 9 and x = 10, except at y = 15, and a closed off square at x 15 to 17 and
     * y 2 to 4. Nothing outside the 20 by 20 world can be walked on.
     */
    private fun canStep(x: Int, y: Int, z: Int, dx: Int, dy: Int): Boolean {
        fun open(x: Int, y: Int) = x in 0..19 && y in 0..19
        fun room(x: Int, y: Int) = x in 15..17 && y in 2..4
        val toX = x + dx
        val toY = y + dy
        if (!open(toX, toY) || room(x, y) != room(toX, toY)) {
            return false
        }
        val crossesWall = (x <= 9) != (toX <= 9)
        return !crossesWall || (y == 15 && toY == 15)
    }

    private val components = WalkableComponents(::canStep)

    @Test
    fun positionsThatCanBeWalkedBetweenShareAComponent() {
        val labels = components.label(listOf(Position(1, 1), Position(8, 8), Position(12, 12), Position(19, 19)))

        assertEquals(1, labels.toSet().size)
    }

    @Test
    fun wallsAndRoomsSeparateComponents() {
        val labels = components.label(listOf(Position(1, 1), Position(16, 3), Position(16, 4), Position(25, 25)))

        assertEquals(labels[1], labels[2])
        assertEquals(3, labels.toSet().size)
        assertNotEquals(labels[0], labels[1])
        assertNotEquals(labels[0], labels[3])
        assertNotEquals(labels[1], labels[3])
    }

    @Test
    fun differentPlanesAreSeparate() {
        val labels = components.label(listOf(Position(1, 1, 0), Position(1, 1, 1), Position(2, 2, 1)))

        assertNotEquals(labels[0], labels[1])
        assertEquals(labels[1], labels[2])
    }

    @Test
    fun positionsOnTheSameTileAreInTheSameComponent() {
        val labels = components.label(listOf(Position(3, 3), Position(3, 3)))

        assertEquals(labels[0], labels[1])
    }

    @Test
    fun linkerOnlyTriesNodesInTheSameComponent() {
        val tried = ArrayList<Pair<Int, Int>>()
        val nodes = listOf(WebWalkNode("a", Position(1, 1), NodeKind.WAYPOINT),
                           WebWalkNode("b", Position(5, 5), NodeKind.WAYPOINT),
                           WebWalkNode("room", Position(16, 3), NodeKind.WAYPOINT))
        val labels = components.label(nodes.map { it.position })
        val byId = nodes.indices.associate { nodes[it].id to labels[it] }

        val links = WalkLinker({ a, b ->
            tried += Pair(a.x, b.x)
            4
        }).link(nodes) { byId.getValue(it.id) }

        assertEquals(listOf(GeneratedLink("a", "b", 4)), links)
        assertTrue(tried.none { it.first == 16 || it.second == 16 })
    }
}
