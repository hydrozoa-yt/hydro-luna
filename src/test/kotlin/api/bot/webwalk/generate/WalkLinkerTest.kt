package api.bot.webwalk.generate

import api.bot.webwalk.model.NodeKind
import api.bot.webwalk.model.WebWalkNode
import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [WalkLinker].
 *
 * @author Hydrozoa
 */
class WalkLinkerTest {

    /**
     * A world with a wall at x = 500 that can't be crossed, where walking one tile takes half a tick.
     */
    private val pathCost = { a: Position, b: Position ->
        if ((a.x < 500) != (b.x < 500)) null else (a.computeLongestDistance(b) + 1) / 2
    }

    private fun node(id: String, x: Int, y: Int = 0, z: Int = 0) = WebWalkNode(id, Position(x, y, z), NodeKind.WAYPOINT)

    /**
     * Finds the sets of node ids that the links connect.
     */
    private fun components(nodes: List<WebWalkNode>, links: List<GeneratedLink>): Set<Set<String>> {
        val parent = nodes.associate { it.id to it.id }.toMutableMap()
        fun find(id: String): String = if (parent[id] == id) id else find(parent[id]!!).also { parent[id] = it }
        for (link in links) {
            parent[find(link.from)] = find(link.to)
        }
        return nodes.groupBy { find(it.id) }.values.map { group -> group.map { it.id }.toSet() }.toSet()
    }

    @Test
    fun nearbyNodesAreLinkedWithTheirCost() {
        val nodes = listOf(node("a", 0), node("b", 10), node("c", 20))
        val links = WalkLinker(pathCost).link(nodes)

        assertEquals(3, links.size)
        assertEquals(5, links.first { it.from == "a" && it.to == "b" }.cost)
        assertEquals(10, links.first { it.from == "a" && it.to == "c" }.cost)
        assertEquals(1, components(nodes, links).size)
    }

    @Test
    fun nodesThatCantBeWalkedBetweenAreNotLinked() {
        val nodes = listOf(node("a", 0), node("b", 10), node("x", 505), node("y", 510))
        val links = WalkLinker(pathCost).link(nodes)

        assertEquals(setOf(setOf("a", "b"), setOf("x", "y")), components(nodes, links))
    }

    @Test
    fun farAwayGroupsAreJoinedWhenTheyCanBeWalkedBetween() {
        val nodes = listOf(node("a", 400), node("b", 410), node("far1", 110), node("far2", 100), node("island", 505))
        val links = WalkLinker(pathCost).link(nodes)

        // The groups are 290 tiles apart, so they are joined over a long distance, but the island behind the wall is not.
        assertEquals(setOf(setOf("a", "b", "far1", "far2"), setOf("island")), components(nodes, links))
        val join = links.single { setOf(it.from, it.to) == setOf("a", "far1") }
        assertEquals(145, join.cost)
    }

    @Test
    fun groupsOutOfRangeStayApart() {
        val nodes = listOf(node("a", 0), node("far", 460))
        val links = WalkLinker(pathCost, farRadius = 400).link(nodes)

        assertTrue(links.isEmpty())
    }

    @Test
    fun differentPlanesAreNeverLinked() {
        val nodes = listOf(node("ground", 0), node("above", 1, z = 1))
        assertTrue(WalkLinker({ _, _ -> 1 }).link(nodes).isEmpty())
    }

    @Test
    fun everyNodeIsLinkedToNoMoreThanItsNeighboursAndNoPairTwice() {
        val nodes = (0 until 10).map { node("n$it", it) }
        val links = WalkLinker(pathCost, neighbours = 2).link(nodes)

        assertEquals(links.size, links.map { setOf(it.from, it.to) }.toSet().size)
        assertTrue(links.size <= nodes.size * 2)
        assertEquals(1, components(nodes, links).size)
    }

    @Test
    fun theSamePairIsNeverTriedTwice() {
        val tried = ArrayList<Pair<String, String>>()
        val nodes = listOf(node("a", 0), node("b", 10), node("c", 505))
        WalkLinker({ a, b ->
            tried += Pair("${a.x}", "${b.x}")
            pathCost(a, b)
        }).link(nodes)

        val pairs = tried.map { setOf(it.first, it.second) }
        assertEquals(pairs.size, pairs.toSet().size)
    }

    @Test
    fun linksAreAlwaysTheSame() {
        val nodes = (0 until 30).map { node("n$it", (it * 37) % 120, (it * 53) % 90) }
        val first = WalkLinker(pathCost).link(nodes)
        val second = WalkLinker(pathCost).link(nodes.reversed())

        assertEquals(first, second)
    }
}
