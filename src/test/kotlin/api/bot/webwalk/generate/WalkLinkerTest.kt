package api.bot.webwalk.generate

import api.bot.webwalk.model.NodeKind
import api.bot.webwalk.model.WebWalkNode
import api.bot.webwalk.plan.DoorCrossings
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
    fun theTwoSidesOfADoorAreNotLinkedAndCountAsLinked() {
        val tried = ArrayList<Set<Int>>()
        val nodes = listOf(node("a", 10), node("b", 11), node("c", 14))
        val doors = DoorCrossings(listOf(Pair(Position(10, 0), Position(11, 0))))
        val links = WalkLinker({ a, b ->
            tried += setOf(a.x, b.x)
            // The door is shut, so there is no walk between its sides.
            if ((a.x <= 10) != (b.x <= 10)) null else pathCost(a, b)
        }, neighbours = 1, doors = doors).link(nodes)

        // The door is the one neighbour of a, and the walk between the sides is never searched for. c is on the other side.
        assertTrue(tried.none { it == setOf(10, 11) })
        assertEquals(setOf(setOf("b", "c")), links.map { setOf(it.from, it.to) }.toSet())
    }

    @Test
    fun aNodeIsNotLinkedToANodeBesideOneThatItIsLinkedTo() {
        val nodes = listOf(node("a", 0), node("b", 10), node("c", 11))
        val links = WalkLinker(pathCost).link(nodes)

        // a is linked to b, which is nearer, so not to c next to it. c still gets a link, from b.
        assertEquals(setOf(setOf("a", "b"), setOf("b", "c")), links.map { setOf(it.from, it.to) }.toSet())
    }

    @Test
    fun aNodeCountsTheLinksThatOtherNodesMadeToIt() {
        // a is linked to b, so b has its one neighbour already, and goes no farther to c. c is linked to a.
        val nodes = listOf(node("a", 10), node("b", 0), node("c", 20))
        val links = WalkLinker(pathCost, neighbours = 1).link(nodes)

        assertEquals(setOf(setOf("a", "b"), setOf("a", "c")), links.map { setOf(it.from, it.to) }.toSet())
    }

    @Test
    fun nodesAreLinkedByTheWalkToThemAndNotByTheDistance() {
        // n is nearer to d than f is, but the walk to it goes around something and takes far longer.
        val nodes = listOf(node("d", 0), node("f", 12), node("n", 8))
        val links = WalkLinker({ a, b -> if (setOf(a.x, b.x) == setOf(0, 8)) 30 else pathCost(a, b) }, neighbours = 1).link(nodes)

        assertEquals(setOf(setOf("d", "f"), setOf("f", "n")), links.map { setOf(it.from, it.to) }.toSet())
    }

    @Test
    fun theLeavesOfTwoDoorsAreLinkedOnceAndNotOncePerLeaf() {
        // Two pairs of nodes side by side, like the leaves of two gates that face each other.
        val nodes = listOf(node("a1", 0, 0), node("a2", 1, 0), node("c1", 0, 10), node("c2", 1, 10))
        val links = WalkLinker(pathCost).link(nodes)

        // The leaves are linked to each other, and the pairs are linked once, not a1-c1 and a2-c2 (or the crossed ones) as well.
        assertEquals(setOf(setOf("a1", "a2"), setOf("a1", "c1"), setOf("c1", "c2")), links.map { setOf(it.from, it.to) }.toSet())
    }

    /**
     * Links a node and two nodes a tile apart, which take [walk] ticks to walk between because something is in the way.
     */
    private fun linksWithObjectInTheWay(walk: Int): Set<Set<String>> {
        val nodes = listOf(node("d", 0, 0), node("l1", 10, 0), node("l2", 11, 1))
        val links = WalkLinker({ a, b -> if (setOf(a.x, b.x) == setOf(10, 11)) walk else pathCost(a, b) }).link(nodes)
        return links.map { setOf(it.from, it.to) }.toSet()
    }

    @Test
    fun nodesWithAShortWalkAroundAnObjectBetweenThemAreBesideEachOther() {
        // Like a ladder and a trapdoor next to each other, which d is not linked to both of.
        assertEquals(setOf(setOf("d", "l1"), setOf("l1", "l2")), linksWithObjectInTheWay(4))
    }

    @Test
    fun nodesAFewTilesApartWithAShortWalkBetweenThemAreBesideEachOther() {
        val nodes = listOf(node("d", 0, 0), node("l1", 10, 0), node("l2", 13, 0))
        val links = WalkLinker(pathCost).link(nodes).map { setOf(it.from, it.to) }.toSet()

        // l2 is 3 tiles from l1, which is a walk of 2 ticks, so d reaches both of them through l1.
        assertEquals(setOf(setOf("d", "l1"), setOf("l1", "l2")), links)
    }

    @Test
    fun nodesFartherApartThanTheRadiusAreNotBesideEachOther() {
        val nodes = listOf(node("d", 0, 0), node("l1", 10, 0), node("l2", 14, 0))
        val links = WalkLinker(pathCost).link(nodes).map { setOf(it.from, it.to) }.toSet()

        assertEquals(setOf(setOf("d", "l1"), setOf("d", "l2"), setOf("l1", "l2")), links)
    }

    @Test
    fun nodesWithALongWalkBetweenThemAreNotBesideEachOther() {
        // Like two nodes on either side of a wall, which are different places.
        assertEquals(setOf(setOf("d", "l1"), setOf("d", "l2"), setOf("l1", "l2")), linksWithObjectInTheWay(21))
    }

    @Test
    fun theTwoSidesOfADoorAreNotBesideEachOther() {
        // The sides of the gate can be walked between in the test world, but there is a door in the way. So the link between
        // the outer leaves is made, even though the inner leaves are linked, instead of being taken for a second link of the same walk.
        val nodes = listOf(node("in1", 0, 0), node("in2", 1, 0), node("out1", 0, 1), node("out2", 1, 1))
        val doors = DoorCrossings(listOf(Pair(Position(0, 0), Position(0, 1)), Pair(Position(1, 0), Position(1, 1))))
        val links = WalkLinker({ a, b -> if ((a.y == 0) != (b.y == 0)) null else pathCost(a, b) }, doors = doors).link(nodes)

        assertEquals(setOf(setOf("in1", "in2"), setOf("out1", "out2")), links.map { setOf(it.from, it.to) }.toSet())
    }

    @Test
    fun linksAreAlwaysTheSame() {
        val nodes = (0 until 30).map { node("n$it", (it * 37) % 120, (it * 53) % 90) }
        val first = WalkLinker(pathCost).link(nodes)
        val second = WalkLinker(pathCost).link(nodes.reversed())

        assertEquals(first, second)
    }
}
