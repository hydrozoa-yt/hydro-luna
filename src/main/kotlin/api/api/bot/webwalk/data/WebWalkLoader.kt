package api.bot.webwalk.data

import api.bot.webwalk.model.EdgeAction
import api.bot.webwalk.model.EdgeType
import api.bot.webwalk.model.FairyRing
import api.bot.webwalk.model.ItemRequirement
import api.bot.webwalk.model.NodeKind
import api.bot.webwalk.model.Requirements
import api.bot.webwalk.model.TeleportDefinition
import api.bot.webwalk.model.TeleportKind
import api.bot.webwalk.model.WebWalkEdge
import api.bot.webwalk.model.WebWalkGraph
import api.bot.webwalk.model.WebWalkNode
import io.luna.game.model.Position
import io.luna.game.model.mob.Skill
import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads the web-walker graph from its JSON files and checks it.
 *
 * Every file is optional, and a missing file is the same as an empty one. All the files are read before anything is
 * reported, so [WebWalkDataException] lists every problem at once. These are the files, with their fields:
 *
 * - `hubs.jsonc` (generated) and `hubs_manual.json` (maintained by hand): an object with `hubs` (`id`, `pos`, optional
 *   `tags`) and `edges` (`from`, `to`, optional `cost`, `bidirectional`, `requirements`) that walk between nodes. The ids
 *   of the two files share one namespace, so a manual hub can't silently replace a generated one.
 * - `obstacles.jsonc` (generated): closed doors, gates and curtains. `climbs.jsonc` (generated): ladders, stairs and
 *   trapdoors. Both have entries with a `type`, `object`,
 *   `pos`, `from` and `to`, and an optional `cost`, `bidirectional`, `option` and `requirements`.
 * - `obstacle_overrides.json` (maintained by hand): patches for obstacles, found by `pos` (and optionally `object`), that
 *   set `requirements` or `cost`, or turn the obstacle off with `disabled`. An override that finds no obstacle is a
 *   problem, so overrides can't quietly rot after the obstacles are generated again.
 * - `teleports.jsonc` (generated): teleports, each with an `id`, `kind`, `dest`, `cost`, and the `key` (and for jewellery
 *   the `option`) that says which spell or jewellery it is.
 * - `ships.json`: an object with `ports` (`id`, `pos`) and `routes` (`from`, `to`, `cost`), or an empty array.
 * - `fairy_rings.json`: fairy rings, each with an `id`, `code` and `pos`.
 *
 * Positions are written as `[x, y]` or `[x, y, z]`. Requirements are written as an object with any of `skills` (skill name
 * to level), `items` (`id` and optional `amount`), `coins`, `flags` and `maxWilderness`.
 *
 * The files may have `//` comments, which the generated ones use for a header that says they are generated.
 *
 * @author Hydrozoa
 */
object WebWalkLoader {

    /**
     * The generated hubs, and the walk edges between them.
     */
    const val HUBS = "hubs.jsonc"

    /**
     * The hubs and walk edges that are maintained by hand.
     */
    const val HUBS_MANUAL = "hubs_manual.json"

    /**
     * The generated obstacles.
     */
    const val OBSTACLES = "obstacles.jsonc"

    /**
     * The generated ladders, stairs and trapdoors, which are obstacles too.
     */
    const val CLIMBS = "climbs.jsonc"

    /**
     * The patches that are applied to the generated obstacles.
     */
    const val OBSTACLE_OVERRIDES = "obstacle_overrides.json"

    /**
     * The teleports.
     */
    const val TELEPORTS = "teleports.jsonc"

    /**
     * The ships.
     */
    const val SHIPS = "ships.json"

    /**
     * The fairy rings.
     */
    const val FAIRY_RINGS = "fairy_rings.json"

    /**
     * Every file that is loaded.
     */
    val FILES = listOf(HUBS, HUBS_MANUAL, OBSTACLES, CLIMBS, OBSTACLE_OVERRIDES, TELEPORTS, SHIPS, FAIRY_RINGS)

    /**
     * The logger instance.
     */
    private val logger = LogManager.getLogger()

    /**
     * Loads the graph from the files of a directory.
     *
     * @param directory The directory of the files.
     * @return The graph.
     * @throws WebWalkDataException If the data is invalid.
     */
    fun load(directory: Path): WebWalkGraph {
        val sources = HashMap<String, String>()
        for (file in FILES) {
            val path = directory.resolve(file)
            if (Files.exists(path)) {
                sources[file] = Files.readString(path)
            }
        }
        val graph = fromSources(sources)
        logger.debug("Loaded web-walker graph: {} nodes, {} edges, {} teleports, {} fairy rings.",
                     graph.nodes.size, graph.edges.size, graph.teleports.size, graph.fairyRings.size)
        return graph
    }

    /**
     * Creates the graph from the text of the files.
     *
     * @param sources The text of each file, keyed by file name (see [FILES]). Files that are missing are empty.
     * @return The graph.
     * @throws WebWalkDataException If the data is invalid.
     */
    fun fromSources(sources: Map<String, String>): WebWalkGraph = Session(sources).build()

    /**
     * The skill ids, keyed by lower case skill name.
     */
    private val skillIds: Map<String, Int> by lazy {
        Skill.NAMES.withIndex().associate { (id, name) -> name.lowercase() to id }
    }

    /**
     * The ids of nodes that people write: lower case letters, numbers, `_` and `-`. The prefixes of the nodes that are made
     * by the loader contain a `:`, so they can never clash.
     */
    private val idPattern = Regex("[a-z0-9_-]+")

    /**
     * The letters of a fairy ring code: one of A-D, then one of I-L, then one of P-S.
     */
    private val fairyCodePattern = Regex("[A-D][I-L][P-S]")

    /**
     * An edge whose nodes have not been checked yet.
     */
    private class PendingEdge(val where: String,
                              val from: String,
                              val to: String,
                              val type: EdgeType,
                              val cost: Int?,
                              val bidirectional: Boolean,
                              val requirements: Requirements,
                              val action: EdgeAction? = null)

    /**
     * An entry of `obstacles.jsonc`, which overrides can still change.
     */
    private class Obstacle(val where: String,
                           val type: EdgeType,
                           val objectId: Int,
                           val pos: Position,
                           val from: Position,
                           val to: Position,
                           var cost: Int?,
                           val bidirectional: Boolean,
                           val option: String?,
                           var requirements: Requirements,
                           var disabled: Boolean = false)

    /**
     * One load of the files.
     */
    private class Session(private val sources: Map<String, String>) {

        /**
         * The problems found.
         */
        val errors = DataErrors()

        /**
         * The nodes, in the order they were added.
         */
        val nodes = LinkedHashMap<String, WebWalkNode>()

        /**
         * The edges that still need their nodes to be checked.
         */
        val pending = ArrayList<PendingEdge>()

        /**
         * The teleports.
         */
        val teleports = ArrayList<TeleportDefinition>()

        /**
         * The fairy rings.
         */
        val fairyRings = ArrayList<FairyRing>()

        /**
         * Reads every file and creates the graph.
         */
        fun build(): WebWalkGraph {
            readHubs(HUBS)
            readHubs(HUBS_MANUAL)
            readObstacles()
            readTeleports()
            readShips()
            readFairyRings()

            val edges = ArrayList<WebWalkEdge>()
            for (edge in pending) {
                resolve(edge, edges)
            }
            if (errors.messages.isNotEmpty()) {
                throw WebWalkDataException(errors.messages)
            }
            return WebWalkGraph(nodes.values, edges, teleports, fairyRings)
        }

        /**
         * Reads a file that is an array of objects, or is empty.
         */
        fun entries(file: String): List<FieldReader> {
            val root = FieldReader.parse(file, sources[file], errors) ?: return emptyList()
            if (!root.isJsonArray) {
                errors.add(file, "must be an array")
                return emptyList()
            }
            return FieldReader.objectsOf(root.asJsonArray, file, errors)
        }

        /**
         * Reads a file that is an object. An empty array counts as an empty object.
         */
        fun root(file: String): FieldReader? {
            val root = FieldReader.parse(file, sources[file], errors) ?: return null
            if (root.isJsonArray && root.asJsonArray.size() == 0) {
                return null
            }
            if (!root.isJsonObject) {
                errors.add(file, "must be an object (or an empty array)")
                return null
            }
            return FieldReader(root.asJsonObject, file, errors)
        }

        /**
         * Reads the id of an entry that people write.
         */
        fun readId(reader: FieldReader): String? {
            val id = reader.string("id") ?: return null
            if (!idPattern.matches(id)) {
                reader.reject("id", "must be lower case letters, numbers, '_' and '-' only, but was '$id'")
                return null
            }
            return id
        }

        /**
         * Adds a node, and records a problem if its id is taken.
         */
        fun addNode(where: String, node: WebWalkNode) {
            val existing = nodes.putIfAbsent(node.id, node)
            if (existing != null) {
                errors.add(where, "duplicate node id '${node.id}'")
            }
        }

        /**
         * Reads requirements, which are none if the field is missing.
         */
        fun readRequirements(parent: FieldReader): Requirements {
            val reader = parent.obj("requirements") ?: return Requirements.NONE
            val skills = LinkedHashMap<Int, Int>()
            reader.obj("skills")?.let { skillReader ->
                for (name in skillReader.keys()) {
                    val id = skillIds[name.lowercase()]
                    if (id == null) {
                        skillReader.reject(name, "unknown skill")
                    } else {
                        skillReader.int(name, min = 1, max = 99)?.let { skills[id] = it }
                    }
                }
                skillReader.finish()
            }
            val items = ArrayList<ItemRequirement>()
            for (item in reader.objects("items")) {
                val id = item.int("id", min = 0)
                val amount = item.int("amount", required = false, min = 1) ?: 1
                item.finish()
                if (id != null) {
                    items += ItemRequirement(id, amount)
                }
            }
            val coins = reader.int("coins", required = false, min = 0) ?: 0
            val flags = reader.strings("flags").toSet()
            val maxWilderness = reader.int("maxWilderness", required = false, min = 0)
            reader.finish()
            return Requirements(skills, items, coins, flags, maxWilderness)
        }

        /**
         * Reads a hubs file.
         */
        fun readHubs(file: String) {
            val root = root(file) ?: return
            for (hub in root.objects("hubs")) {
                val id = readId(hub)
                val pos = hub.position("pos")
                val tags = hub.strings("tags").toSet()
                hub.finish()
                if (id != null && pos != null) {
                    addNode(hub.where, WebWalkNode(id, pos, NodeKind.HUB, tags))
                }
            }
            for (edge in root.objects("edges")) {
                val from = edge.string("from")
                val to = edge.string("to")
                val type = edge.string("type", required = false)
                val cost = edge.int("cost", required = false, min = 0)
                val bidirectional = edge.bool("bidirectional") ?: EdgeType.WALK.bidirectionalByDefault
                val requirements = readRequirements(edge)
                edge.finish()
                if (type != null && type != EdgeType.WALK.name) {
                    edge.reject("type", "only WALK edges are allowed here, but was '$type'")
                } else if (from != null && to != null) {
                    pending += PendingEdge(edge.where, from, to, EdgeType.WALK, cost, bidirectional, requirements)
                }
            }
            root.finish()
        }

        /**
         * Reads the obstacles, applies the overrides and makes nodes and edges of the ones that are left.
         */
        fun readObstacles() {
            val obstacles = ArrayList<Obstacle>()
            val seen = HashSet<List<Any>>()
            for (entry in entries(OBSTACLES) + entries(CLIMBS)) {
                val obstacle = readObstacle(entry) ?: continue
                val key = listOf(obstacle.type, obstacle.objectId, obstacle.pos, obstacle.from, obstacle.to)
                if (!seen.add(key)) {
                    errors.add(entry.where, "duplicate of an earlier obstacle")
                    continue
                }
                obstacles += obstacle
            }
            applyOverrides(obstacles)

            for (obstacle in obstacles) {
                if (obstacle.disabled) {
                    continue
                }
                val from = obstacleNode(obstacle.from)
                val to = obstacleNode(obstacle.to)
                pending += PendingEdge(obstacle.where, from, to, obstacle.type, obstacle.cost, obstacle.bidirectional,
                                       obstacle.requirements,
                                       EdgeAction(obstacle.objectId, obstacle.pos, obstacle.option))
            }
        }

        /**
         * Reads one obstacle.
         */
        fun readObstacle(entry: FieldReader): Obstacle? {
            val typeName = entry.string("type")
            val objectId = entry.int("object", min = 0)
            val pos = entry.position("pos")
            val from = entry.position("from")
            val to = entry.position("to")
            val cost = entry.int("cost", required = false, min = 0)
            val bidirectional = entry.bool("bidirectional")
            val option = entry.string("option", required = false)
            val requirements = readRequirements(entry)
            entry.finish()

            var type: EdgeType? = null
            if (typeName != null) {
                type = EdgeType.entries.firstOrNull { it.name == typeName && it.isObstacle }
                if (type == null) {
                    entry.reject("type", "must be one of ${EdgeType.entries.filter { it.isObstacle }.joinToString()}, " +
                            "but was '$typeName'")
                }
            }
            if (type == null || objectId == null || pos == null || from == null || to == null) {
                return null
            }
            if (from == to) {
                entry.reject("to", "must differ from 'from'")
                return null
            }
            if (type.isDoorLike && (from.z != to.z || from.computeLongestDistance(to) != 1)) {
                entry.reject("to", "must be the tile next to 'from' on the same plane for a ${type.name}")
                return null
            }
            return Obstacle(entry.where, type, objectId, pos, from, to, cost,
                            bidirectional ?: type.bidirectionalByDefault, option, requirements)
        }

        /**
         * Applies the overrides to the obstacles.
         */
        fun applyOverrides(obstacles: List<Obstacle>) {
            for (entry in entries(OBSTACLE_OVERRIDES)) {
                val pos = entry.position("pos")
                val objectId = entry.int("object", required = false, min = 0)
                val disabled = entry.bool("disabled")
                val cost = entry.int("cost", required = false, min = 0)
                val hasRequirements = entry.has("requirements")
                val requirements = readRequirements(entry)
                entry.finish()
                if (pos == null) {
                    continue
                }
                if (disabled == null && cost == null && !hasRequirements) {
                    errors.add(entry.where, "must set at least one of 'disabled', 'cost' or 'requirements'")
                    continue
                }
                val matches = obstacles.filter { it.pos == pos && (objectId == null || it.objectId == objectId) }
                if (matches.isEmpty()) {
                    errors.add(entry.where, "matches no obstacle at $pos")
                    continue
                }
                for (obstacle in matches) {
                    if (disabled != null) {
                        obstacle.disabled = disabled
                    }
                    if (cost != null) {
                        obstacle.cost = cost
                    }
                    if (hasRequirements) {
                        obstacle.requirements = requirements
                    }
                }
            }
        }

        /**
         * Gets the id of the node of an obstacle tile, adding it if it is not there yet.
         */
        fun obstacleNode(position: Position): String {
            val id = "o:${position.x},${position.y},${position.z}"
            nodes.putIfAbsent(id, WebWalkNode(id, position, NodeKind.OBSTACLE))
            return id
        }

        /**
         * Reads the teleports.
         */
        fun readTeleports() {
            for (entry in entries(TELEPORTS)) {
                val id = readId(entry)
                val kindName = entry.string("kind")
                val key = entry.string("key", required = false)
                val option = entry.int("option", required = false, min = 1)
                val dest = entry.position("dest")
                val cost = entry.int("cost", min = 0)
                val requirements = readRequirements(entry)
                entry.finish()

                var valid = true
                val kind = kindName?.let { name -> TeleportKind.entries.firstOrNull { it.name == name } }
                if (kindName != null && kind == null) {
                    entry.reject("kind", "must be one of ${TeleportKind.entries.joinToString()}, but was '$kindName'")
                    valid = false
                }
                if (kind != null && kind != TeleportKind.HOME && key == null) {
                    entry.reject("key", "is required for a ${kind.name} teleport")
                    valid = false
                }
                if (kind == TeleportKind.JEWELLERY && option == null) {
                    entry.reject("option", "is required for a JEWELLERY teleport")
                    valid = false
                }
                if (kind != null && kind != TeleportKind.JEWELLERY && option != null) {
                    entry.reject("option", "is only for a JEWELLERY teleport")
                    valid = false
                }
                if (!valid || id == null || kind == null || dest == null || cost == null) {
                    continue
                }
                val nodeId = "t:$id"
                if (teleports.any { it.id == id }) {
                    errors.add(entry.where, "duplicate teleport id '$id'")
                    continue
                }
                addNode(entry.where, WebWalkNode(nodeId, dest, NodeKind.TELEPORT_DEST))
                teleports += TeleportDefinition(id, kind, key, option, dest, nodeId, cost, requirements)
            }
        }

        /**
         * Reads the ships.
         */
        fun readShips() {
            val root = root(SHIPS) ?: return
            for (port in root.objects("ports")) {
                val id = readId(port)
                val pos = port.position("pos")
                val tags = port.strings("tags").toSet()
                port.finish()
                if (id != null && pos != null) {
                    addNode(port.where, WebWalkNode(id, pos, NodeKind.SHIP_PORT, tags))
                }
            }
            for (route in root.objects("routes")) {
                val from = route.string("from")
                val to = route.string("to")
                val cost = route.int("cost", min = 0)
                val bidirectional = route.bool("bidirectional") ?: EdgeType.SHIP.bidirectionalByDefault
                val option = route.string("option", required = false)
                val requirements = readRequirements(route)
                route.finish()
                if (from != null && to != null && cost != null) {
                    pending += PendingEdge(route.where, from, to, EdgeType.SHIP, cost, bidirectional, requirements,
                                           EdgeAction(option = option))
                }
            }
            root.finish()
        }

        /**
         * Reads the fairy rings.
         */
        fun readFairyRings() {
            val codes = HashSet<String>()
            for (entry in entries(FAIRY_RINGS)) {
                val id = readId(entry)
                val code = entry.string("code")
                val pos = entry.position("pos")
                val requirements = readRequirements(entry)
                entry.finish()

                if (code != null && !fairyCodePattern.matches(code)) {
                    entry.reject("code", "must be a letter of A-D, then I-L, then P-S, but was '$code'")
                    continue
                }
                if (code != null && !codes.add(code)) {
                    entry.reject("code", "duplicate fairy ring code '$code'")
                    continue
                }
                if (id == null || code == null || pos == null) {
                    continue
                }
                val nodeId = "fr:$id"
                addNode(entry.where, WebWalkNode(nodeId, pos, NodeKind.FAIRY_RING))
                fairyRings += FairyRing(id, code, pos, nodeId, requirements)
            }
        }

        /**
         * Checks the nodes of an edge, and adds it (and its reverse, if it is travelled both ways).
         */
        fun resolve(edge: PendingEdge, edges: MutableList<WebWalkEdge>) {
            val from = nodes[edge.from]
            val to = nodes[edge.to]
            if (from == null) {
                errors.add(edge.where, "'from' refers to unknown node '${edge.from}'")
            }
            if (to == null) {
                errors.add(edge.where, "'to' refers to unknown node '${edge.to}'")
            }
            if (from == null || to == null) {
                return
            }
            if (from == to) {
                errors.add(edge.where, "'from' and 'to' are the same node '${edge.from}'")
                return
            }
            val resolved = WebWalkEdge(edge.from, edge.to, edge.type, edge.cost, edge.requirements, edge.action)
            edges += resolved
            if (edge.bidirectional) {
                edges += resolved.reversed()
            }
        }
    }
}
