package api.bot.webwalk

import io.luna.game.model.Position

/**
 * Finds the walk edges that connect the nodes of the web-walker graph.
 *
 * Every node is linked to the nodes nearest to it that can be walked to. The nodes that are left in groups that are not
 * linked to each other are then joined, nearest first, over longer distances. Once it is done, any two nodes that can be
 * walked between are connected, however far apart they are.
 *
 * The linker only asks how far it is to walk between two positions, so it does not depend on any pathfinder.
 *
 * @param pathCost Gives the cost, in game ticks, of walking between two positions on the same plane, or `null` if it can't
 * be walked. This may be called from any thread, but the linker never calls it from more than one at once.
 * @param neighbours How many nodes each node is linked to at first.
 * @param nearRadius How far, in tiles, the nodes that are linked at first may be.
 * @param farRadius How far, in tiles, nodes in other groups are looked for when joining the groups.
 * @param joinAttempts How many times a pair of groups is tried before giving up on joining them.
 *
 * @author Hydrozoa
 */
class WalkLinker(private val pathCost: (Position, Position) -> Int?,
                 private val neighbours: Int = 5,
                 private val nearRadius: Int = 40,
                 private val farRadius: Int = 400,
                 private val joinAttempts: Int = 3) {

    companion object {

        /**
         * The width of the squares that the nodes are sorted into, so that the ones nearby can be found quickly.
         */
        private const val CELL_SIZE = 32
    }

    /**
     * Finds the groups of nodes that are linked.
     */
    private class Groups(size: Int) {

        /**
         * The parent of each node, which is itself for the root of a group.
         */
        private val parent = IntArray(size) { it }

        /**
         * @return The root of the group that [node] is in.
         */
        fun find(node: Int): Int {
            var root = node
            while (parent[root] != root) {
                root = parent[root]
            }
            var current = node
            while (parent[current] != root) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        /**
         * Puts the groups of two nodes together.
         */
        fun union(a: Int, b: Int) {
            parent[find(a)] = find(b)
        }
    }

    /**
     * Makes the links.
     *
     * @param nodes The nodes to link. Nodes on different planes are never linked.
     * @param componentOf Gives the component of a node, as found by [WalkableComponents]. Nodes in different components
     * can not be walked between, so they are never tried, which saves the searches that would fail. By default every node
     * is in the same component.
     * @return The links, each of which is travelled both ways. They are always the same for the same nodes.
     */
    fun link(nodes: Collection<WebWalkNode>, componentOf: (WebWalkNode) -> Int = { 0 }): List<GeneratedLink> {
        val sorted = nodes.sortedBy { it.id }
        val components = IntArray(sorted.size) { componentOf(sorted[it]) }
        val grid = HashMap<Triple<Int, Int, Int>, MutableList<Int>>()
        for ((index, node) in sorted.withIndex()) {
            grid.getOrPut(cell(node.position)) { ArrayList() } += index
        }

        val links = ArrayList<GeneratedLink>()
        val groups = Groups(sorted.size)
        val attempted = HashSet<Pair<Int, Int>>()

        // Tries to link two nodes, once. Returns true if they are now linked.
        fun tryLink(a: Int, b: Int): Boolean {
            val key = if (a < b) Pair(a, b) else Pair(b, a)
            if (!attempted.add(key)) {
                return false
            }
            val cost = pathCost(sorted[a].position, sorted[b].position) ?: return false
            links += GeneratedLink(sorted[a].id, sorted[b].id, cost)
            groups.union(a, b)
            return true
        }

        // Near links: each node to its closest nodes that can be walked to.
        for (index in sorted.indices) {
            var linked = 0
            for (other in nearest(sorted, components, grid, index, nearRadius)) {
                if (linked >= neighbours) {
                    break
                }
                if (tryLink(index, other)) {
                    linked++
                }
            }
        }

        // Far links: join the groups that are left, which can only be done once for each pair of groups.
        // A search that fails can cover everything that can be reached, so each pair of groups is only tried a few times.
        val failures = HashMap<Pair<Int, Int>, Int>()
        for (index in sorted.indices) {
            for (other in nearest(sorted, components, grid, index, farRadius)) {
                val a = groups.find(index)
                val b = groups.find(other)
                if (a == b) {
                    continue
                }
                val pair = if (a < b) Pair(a, b) else Pair(b, a)
                if ((failures[pair] ?: 0) >= joinAttempts) {
                    continue
                }
                if (tryLink(index, other)) {
                    break
                }
                failures.merge(pair, 1, Int::plus)
            }
        }
        return links
    }

    /**
     * Finds the nodes on the same plane within [radius] tiles of a node, which is the closest first.
     */
    private fun nearest(nodes: List<WebWalkNode>,
                        components: IntArray,
                        grid: Map<Triple<Int, Int, Int>, List<Int>>,
                        index: Int,
                        radius: Int): List<Int> {
        val position = nodes[index].position
        val (cellX, cellY, cellZ) = cell(position)
        val reach = radius / CELL_SIZE + 1
        val found = ArrayList<Int>()
        for (x in cellX - reach..cellX + reach) {
            for (y in cellY - reach..cellY + reach) {
                for (other in grid[Triple(x, y, cellZ)] ?: continue) {
                    if (other != index && components[other] == components[index] && nodes[other].position.computeLongestDistance(position) <= radius) {
                        found += other
                    }
                }
            }
        }
        return found.sortedWith(compareBy({ nodes[it].position.computeLongestDistance(position) }, { it }))
    }

    /**
     * The square of the grid that a position is in.
     */
    private fun cell(position: Position) = Triple(position.x / CELL_SIZE, position.y / CELL_SIZE, position.z)
}
