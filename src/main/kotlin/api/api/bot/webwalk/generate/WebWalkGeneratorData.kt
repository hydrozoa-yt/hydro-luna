package api.bot.webwalk.generate

import api.bot.webwalk.model.EdgeType
import io.luna.game.model.Position
import io.luna.game.model.`object`.ObjectDirection

/**
 * A door, gate or curtain, in its closed state, placed in the world.
 *
 * @property objectId The id of the closed object.
 * @property position The position of the object.
 * @property direction The side of the tile that the wall is on.
 * @property type [EdgeType.DOOR], [EdgeType.GATE] or [EdgeType.CURTAIN].
 *
 * @author Hydrozoa
 */
data class PlacedWall(val objectId: Int, val position: Position, val direction: ObjectDirection, val type: EdgeType)

/**
 * An obstacle of `obstacles.json` made by the generator.
 *
 * @property type The type of obstacle.
 * @property objectId The id of the object, in the state that it was found in.
 * @property pos The position of the object.
 * @property from The tile to cross it from.
 * @property to The tile that crossing it arrives at.
 * @property option The menu option to use on the object, or `null` to choose by the object's type.
 *
 * @author Hydrozoa
 */
data class GeneratedObstacle(val type: EdgeType,
                             val objectId: Int,
                             val pos: Position,
                             val from: Position,
                             val to: Position,
                             val option: String? = null)

/**
 * A hub of `hubs.json` made by the generator.
 *
 * @property id The id of the hub.
 * @property pos The position of the hub.
 * @property tags The labels of the hub.
 *
 * @author Hydrozoa
 */
data class GeneratedHub(val id: String, val pos: Position, val tags: Set<String> = emptySet())

/**
 * A walk edge of `hubs.json` made by the generator, which is travelled both ways.
 *
 * @property from The id of one node.
 * @property to The id of the other node.
 * @property cost The cost of walking between them, in game ticks.
 *
 * @author Hydrozoa
 */
data class GeneratedLink(val from: String, val to: String, val cost: Int)

/**
 * What the generator did, for people to read.
 *
 * @author Hydrozoa
 */
class GenerationReport {

    /**
     * Things that were left out, and why.
     */
    val skipped = ArrayList<String>()

    /**
     * How many of each kind of thing was made.
     */
    val counts = LinkedHashMap<String, Int>()

    /**
     * Counts one more of [what].
     */
    fun count(what: String, amount: Int = 1) {
        counts.merge(what, amount, Int::plus)
    }

    /**
     * @return A summary with the count of each thing, then the first [maxSkipped] things that were left out.
     */
    fun summary(maxSkipped: Int = 20): List<String> {
        val lines = ArrayList<String>()
        for ((what, amount) in counts) {
            lines += "$what: $amount"
        }
        lines += "skipped: ${skipped.size}"
        skipped.take(maxSkipped).forEach { lines += "  - $it" }
        if (skipped.size > maxSkipped) {
            lines += "  ... and ${skipped.size - maxSkipped} more"
        }
        return lines
    }
}

/**
 * Makes obstacles out of doors, gates and curtains.
 *
 * @author Hydrozoa
 */
object DoorObstacles {

    /**
     * The tile next to [position] that a wall facing [direction] stands between it and.
     */
    fun otherSide(position: Position, direction: ObjectDirection): Position = when (direction) {
        ObjectDirection.WEST -> position.translate(-1, 0)
        ObjectDirection.NORTH -> position.translate(0, 1)
        ObjectDirection.EAST -> position.translate(1, 0)
        ObjectDirection.SOUTH -> position.translate(0, -1)
    }

    /**
     * Makes an obstacle of every wall that has a tile that can be stood on at each side of it. Walls where it can't be
     * done are left out and reported.
     *
     * @param walls The closed doors, gates and curtains.
     * @param walkable Determines if a tile can be stood on.
     * @param report Where the left out walls are reported.
     * @return The obstacles, which are not sorted.
     */
    fun generate(walls: Iterable<PlacedWall>, walkable: (Position) -> Boolean, report: GenerationReport):
            List<GeneratedObstacle> {
        val obstacles = ArrayList<GeneratedObstacle>()
        for (wall in walls) {
            val from = wall.position
            val to = otherSide(from, wall.direction)
            if (!walkable(from) || !walkable(to)) {
                report.skipped += "${wall.type} ${wall.objectId} at ${describe(from)}: " +
                        "nothing to stand on at ${if (!walkable(from)) describe(from) else describe(to)}"
                continue
            }
            obstacles += GeneratedObstacle(wall.type, wall.objectId, wall.position, from, to)
            report.count(wall.type.name.lowercase() + "s")
        }
        return obstacles
    }

    /**
     * Describes a position for the report.
     */
    fun describe(position: Position) = "[${position.x}, ${position.y}, ${position.z}]"
}

/**
 * A place that should become a hub, before it has been moved onto a tile that can be stood on.
 *
 * @property id The id of the hub.
 * @property position Where the place is.
 * @property tags The labels of the hub.
 *
 * @author Hydrozoa
 */
data class HubSeed(val id: String, val position: Position, val tags: Set<String> = emptySet())

/**
 * Makes hubs out of known places.
 *
 * @author Hydrozoa
 */
object HubSeeds {

    /**
     * Makes a hub of every seed, after moving it to a tile that can be stood on.
     *
     * Seeds that can't be moved to such a tile are left out and reported. A seed that lands on the tile of an earlier one
     * is left out too, but its tags are added to that hub.
     *
     * @param seeds The places, in the order that they win ties.
     * @param snap Finds the tile to use for a position, or `null` if there is none.
     * @param report Where the left out seeds are reported.
     * @return The hubs, which are not sorted.
     */
    fun generate(seeds: Iterable<HubSeed>, snap: (Position) -> Position?, report: GenerationReport): List<GeneratedHub> {
        val byPosition = LinkedHashMap<Position, GeneratedHub>()
        val ids = HashSet<String>()
        for (seed in seeds) {
            val position = snap(seed.position)
            if (position == null) {
                report.skipped += "hub ${seed.id} at ${DoorObstacles.describe(seed.position)}: no tile to stand on nearby"
                continue
            }
            if (!ids.add(seed.id)) {
                report.skipped += "hub ${seed.id}: duplicate id"
                continue
            }
            val existing = byPosition[position]
            if (existing != null) {
                byPosition[position] = existing.copy(tags = existing.tags + seed.tags)
                report.skipped += "hub ${seed.id} at ${DoorObstacles.describe(position)}: same tile as ${existing.id}"
                continue
            }
            byPosition[position] = GeneratedHub(seed.id, position, seed.tags)
            report.count("hubs")
        }
        return byPosition.values.toList()
    }
}

/**
 * Writes the generated files as text. The text is stable, with one entry on each line and entries in a fixed order, so
 * that generating again changes nothing unless the data changed.
 *
 * @author Hydrozoa
 */
object WebWalkWriter {

    /**
     * Writes `obstacles.json`.
     */
    fun obstacles(obstacles: Collection<GeneratedObstacle>): String {
        val sorted = obstacles.sortedWith(compareBy({ it.pos.z }, { it.pos.x }, { it.pos.y }, { it.objectId },
                                                    { it.type }, { it.from.z }, { it.from.x }, { it.from.y },
                                                    { it.to.z }, { it.to.x }, { it.to.y }))
        return lines(sorted.map { entry ->
            val option = entry.option?.let { ", \"option\": ${quote(it)}" } ?: ""
            "{ \"type\": \"${entry.type}\", \"object\": ${entry.objectId}, \"pos\": ${position(entry.pos)}, " +
                    "\"from\": ${position(entry.from)}, \"to\": ${position(entry.to)}$option }"
        }, "[", "]")
    }

    /**
     * Writes `hubs.json`.
     */
    fun hubs(hubs: Collection<GeneratedHub>, links: Collection<GeneratedLink>): String {
        val hubLines = hubs.sortedBy { it.id }.map { hub ->
            val tags = if (hub.tags.isEmpty()) "" else
                ", \"tags\": [" + hub.tags.sorted().joinToString(", ") { quote(it) } + "]"
            "{ \"id\": ${quote(hub.id)}, \"pos\": ${position(hub.pos)}$tags }"
        }
        val linkLines = links.map { if (it.from <= it.to) it else GeneratedLink(it.to, it.from, it.cost) }
            .distinct().sortedWith(compareBy({ it.from }, { it.to })).map {
                "{ \"from\": ${quote(it.from)}, \"to\": ${quote(it.to)}, \"cost\": ${it.cost} }"
            }
        return "{\n  \"hubs\": ${lines(hubLines, "[", "]", "  ")},\n  \"edges\": ${lines(linkLines, "[", "]", "  ")}\n}\n"
    }

    /**
     * Writes a list with one entry on each line.
     */
    private fun lines(entries: List<String>, open: String, close: String, indent: String = ""): String {
        if (entries.isEmpty()) {
            return "$open$close" + if (indent.isEmpty()) "\n" else ""
        }
        val body = entries.joinToString(",\n") { "$indent  $it" }
        return "$open\n$body\n$indent$close" + if (indent.isEmpty()) "\n" else ""
    }

    /**
     * Writes a position as `[x, y, z]`.
     */
    private fun position(position: Position) = "[${position.x}, ${position.y}, ${position.z}]"

    /**
     * Writes a string with quotes, escaping what needs it.
     */
    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
