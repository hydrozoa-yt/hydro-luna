package api.bot.webwalk

import api.bot.zone.SubZone
import api.bot.zone.Zone
import api.predef.*
import engine.obj.LadderDestination
import engine.obj.LadderType
import engine.obj.StairDestination
import engine.obj.Trapdoor
import engine.obj.TrapdoorLanding
import io.luna.game.model.Position
import io.luna.game.model.`object`.GameObject
import io.luna.game.model.path.FallbackPathfinder
import io.luna.game.model.path.PathResultType
import io.luna.game.model.path.astar.LongRangePathfinder
import io.luna.game.model.path.route.RoutePathfinder
import io.luna.game.model.path.route.StepValidator
import io.luna.game.model.path.route.RouteStrategy
import java.nio.file.Files
import java.nio.file.Path

/**
 * Makes `climbs.json` and `hubs.json`, which need a running world.
 *
 * - The ladders, stairs and trapdoors of `climbs.json` land where [LadderDestination], [StairDestination] and
 *   [Trapdoor] say that they do, which is what players get.
 * - The hubs of `hubs.json` are made from the zones, their banks and the sub-zones, moved onto a tile that can be stood
 *   on.
 * - The walk edges of `hubs.json` link every node of the graph (the ones from the files that are not generated here too)
 *   with the cost of walking between them, according to the collision of the world.
 *
 * It runs in two steps. [gather] needs the game thread, because it reads the world as it is. [finish] only reads the
 * collision snapshots, so it runs on a thread of its own, as it takes a while.
 *
 * `obstacles.json` is made without a world by [WebWalkDoorGenerator], and it is never changed here.
 *
 * @author Hydrozoa
 */
object WebWalkLiveGenerator {

    /**
     * The ladder out of the Port Phasmatys brewery's cellar, which isn't called a ladder.
     */
    private const val PHASMATYS_BREWERY_LADDER = 7433

    /**
     * The Barbarian Outpost agility course ladders, which are not for walking.
     */
    private val AGILITY_COURSE_LADDERS = setOf(Position(2532, 3545), Position(2532, 3545, 1))

    /**
     * How far from a seed, in tiles, a tile to stand on is looked for.
     */
    private const val SNAP_RADIUS = 4

    /**
     * How many times a climb is made to find the lowest tile it arrives at, enough to find every tile of a small area.
     */
    private const val LANDING_SAMPLES = 200

    /**
     * The order that landing tiles are compared in.
     */
    private val LANDING_ORDER = compareBy<Position>({ it.z }, { it.x }, { it.y })

    /**
     * What [gather] found.
     *
     * @property climbs The ladders, stairs and trapdoors.
     * @property hubs The hubs.
     * @property report What was made and what was left out.
     */
    class Gathered(val climbs: List<GeneratedObstacle>, val hubs: List<GeneratedHub>, val report: GenerationReport)

    /**
     * The text of the generated files.
     *
     * @property climbs The text of `climbs.json`.
     * @property hubs The text of `hubs.json`.
     */
    class Output(val climbs: String, val hubs: String)

    /**
     * Reads the world. Must be called on the game thread.
     *
     * @return The ladders, stairs, trapdoors and hubs.
     */
    fun gather(): Gathered {
        val report = GenerationReport()
        val climbs = ArrayList<GeneratedObstacle>()
        val trapdoors = HashMap<Int, Trapdoor>()
        for (trapdoor in Trapdoor.entries) {
            trapdoor.closed?.let { trapdoors[it] = trapdoor }
            trapdoors[trapdoor.open] = trapdoor
        }

        for (obj in world.objects.staticIterator()) {
            val def = obj.def() ?: continue
            when {
                def.name == "Ladder" || def.id() == PHASMATYS_BREWERY_LADDER -> ladder(obj, climbs, report)
                def.name in StairDestination.NAMES -> stairs(obj, climbs, report)
                obj.id in trapdoors -> trapdoor(obj, trapdoors.getValue(obj.id), climbs, report)
            }
        }
        report.count("climbs", climbs.size)
        return Gathered(climbs, hubs(report), report)
    }

    /**
     * Makes the obstacles of a ladder.
     */
    private fun ladder(obj: GameObject, climbs: MutableList<GeneratedObstacle>, report: GenerationReport) {
        if (obj.position in AGILITY_COURSE_LADDERS) {
            report.skipped += "ladder ${obj.id} at ${DoorObstacles.describe(obj.position)}: agility course ladder"
            return
        }
        val refusal = LadderType.forObject(obj).refusal
        if (refusal != null) {
            report.skipped += "ladder ${obj.id} at ${DoorObstacles.describe(obj.position)}: $refusal"
            return
        }
        climb(EdgeType.LADDER, obj, StairDestination.directions(obj), climbs, report) { from, up ->
            LadderDestination.destination(obj, from, up)
        }
    }

    /**
     * Makes the obstacles of a staircase.
     */
    private fun stairs(obj: GameObject, climbs: MutableList<GeneratedObstacle>, report: GenerationReport) {
        climb(EdgeType.STAIR, obj, StairDestination.directions(obj), climbs, report) { from, up ->
            StairDestination.destination(obj, from, up)
        }
    }

    /**
     * Makes the obstacle of a trapdoor, which only goes down.
     */
    private fun trapdoor(obj: GameObject, trapdoor: Trapdoor, climbs: MutableList<GeneratedObstacle>,
                         report: GenerationReport) {
        for (from in climbingTiles(obj)) {
            val to = trapdoor.landing.from(obj.position, from)?.let { TrapdoorLanding.land(it) }
            if (to != null && to != from) {
                // A trapdoor has to be opened before it is climbed through.
                val option = if (obj.id == trapdoor.open) "Climb-down" else "Open"
                climbs += GeneratedObstacle(EdgeType.TRAPDOOR, obj.id, obj.position, from, to, option)
                return
            }
        }
        report.skipped += "trapdoor ${obj.id} at ${DoorObstacles.describe(obj.position)}: nowhere to land"
    }

    /**
     * Makes an obstacle for each of the directions that an object can be climbed in, from the first tile beside it that it
     * can be climbed from. The same crossing is never made twice, because some objects ignore the direction.
     */
    private fun climb(type: EdgeType, obj: GameObject, directions: Set<Boolean>, climbs: MutableList<GeneratedObstacle>,
                      report: GenerationReport, destination: (Position, Boolean) -> Position?) {
        val tiles = climbingTiles(obj)
        if (directions.isEmpty()) {
            report.skipped += "${type.name.lowercase()} ${obj.id} at ${DoorObstacles.describe(obj.position)}: " +
                    "no way to climb it"
            return
        }
        val made = HashSet<Pair<Position, Position>>()
        for (up in directions.sortedDescending()) {
            var found = false
            for (from in tiles) {
                val to = canonical { destination(from, up) }
                if (to != null && to != from) {
                    if (made.add(Pair(from, to))) {
                        climbs += GeneratedObstacle(type, obj.id, obj.position, from, to, optionFor(obj, up))
                    }
                    found = true
                    break
                }
            }
            if (!found) {
                report.skipped += "${type.name.lowercase()} ${obj.id} at ${DoorObstacles.describe(obj.position)}: " +
                        "nowhere to land when climbing ${if (up) "up" else "down"}"
            }
        }
    }

    /**
     * Finds where a climb arrives. Some stairs land players on a random tile of an area, as they did in the original game,
     * so the climb is made many times and the lowest tile that it arrives at is used, which keeps the data the same every
     * time it is generated.
     */
    private fun canonical(destination: () -> Position?): Position? {
        var lowest: Position? = null
        repeat(LANDING_SAMPLES) {
            val landing = destination() ?: return null
            if (lowest == null || LANDING_ORDER.compare(landing, lowest) < 0) {
                lowest = landing
            }
        }
        return lowest
    }

    /**
     * Finds the menu option that climbs an object in a direction: the option of that direction if the object has one, and
     * otherwise "Climb", which asks which way.
     */
    private fun optionFor(obj: GameObject, up: Boolean): String {
        val names = if (up) setOf("climb-up", "walk-up", "ascend") else setOf("climb-down", "walk-down", "descend")
        return obj.def().actions.filterNotNull().firstOrNull { it.lowercase() in names } ?: "Climb"
    }

    /**
     * Finds the tiles that an object can be climbed from, in the same order every time.
     */
    private fun climbingTiles(obj: GameObject) =
        LadderDestination.climbingTiles(obj).sortedWith(compareBy({ it.z }, { it.x }, { it.y }))

    /**
     * Makes the hubs of the zones, their banks and the sub-zones.
     */
    private fun hubs(report: GenerationReport): List<GeneratedHub> {
        val seeds = ArrayList<HubSeed>()
        for (zone in Zone.entries) {
            val name = zone.name.lowercase()
            seeds += HubSeed("zone_$name", zone.anchor, setOf("zone"))
            zone.bankAnchors.forEachIndexed { index, bank -> seeds += HubSeed("bank_${name}_$index", bank, setOf("bank")) }
        }
        for (subZone in SubZone.entries) {
            seeds += HubSeed("subzone_${subZone.name.lowercase()}", subZone.inside, setOf("subzone"))
        }
        return HubSeeds.generate(seeds, ::standableNear, report)
    }

    /**
     * Finds the tile to stand on that is nearest a position, which is the position if it can be stood on.
     */
    private fun standableNear(position: Position): Position? {
        for (radius in 0..SNAP_RADIUS) {
            val ring = ArrayList<Position>()
            for (x in -radius..radius) {
                for (y in -radius..radius) {
                    if (maxOf(Math.abs(x), Math.abs(y)) == radius) {
                        ring += position.translate(x, y)
                    }
                }
            }
            ring.sortWith(compareBy({ it.x }, { it.y }))
            ring.firstOrNull { it.x >= 0 && it.y >= 0 && LadderDestination.canLand(it) }?.let { return it }
        }
        return null
    }

    /**
     * Makes the text of the generated files, with the walk edges between all the nodes. Throws [WebWalkDataException] if
     * the files of [directory], with what was gathered, don't make a valid graph.
     *
     * @param gathered What [gather] found.
     * @param directory The directory of the other files, which is `data/game/bots/webwalk`.
     * @return The text of `climbs.json` and `hubs.json`.
     */
    fun finish(gathered: Gathered, directory: Path): Output {
        val sources = HashMap<String, String>()
        for (file in WebWalkLoader.FILES) {
            val path = directory.resolve(file)
            if (Files.exists(path)) {
                sources[file] = Files.readString(path)
            }
        }
        val climbsText = WebWalkWriter.obstacles(gathered.climbs)
        sources[WebWalkLoader.CLIMBS] = climbsText
        sources[WebWalkLoader.HUBS] = WebWalkWriter.hubs(gathered.hubs, emptyList())

        val graph = WebWalkLoader.fromSources(sources)
        val collision = world.collisionManager
        val routes = RoutePathfinder(collision, 1, 0, RouteStrategy.NORMAL)
        val pathfinder = FallbackPathfinder<Position>(routes) { origin -> LongRangePathfinder(collision, origin.z) }

        // Which nodes can be walked between is found once for all of them, so the pathfinder is only asked about the ones that
        // can. Otherwise every search for a node that can't be reached would cover everything that can.
        val view = collision.view(true)
        val nodes = graph.nodes.values.toList()
        val labels = WalkableComponents { x, y, z, dx, dy ->
            StepValidator.canTravel(view, z, x, y, dx, dy, 1, 0, RouteStrategy.NORMAL)
        }.label(nodes.map { it.position })
        val componentById = nodes.indices.associate { nodes[it].id to labels[it] }
        gathered.report.count("walkable areas", labels.toSet().size)

        val links = WalkLinker({ from, to ->
            val result = pathfinder.find(from, to)
            when (result.type) {
                PathResultType.COMPLETE, PathResultType.EMPTY -> walkingTicks(from, result.path)
                else -> null
            }
        }).link(nodes) { componentById.getValue(it.id) }
        gathered.report.count("nodes", graph.nodes.size)
        gathered.report.count("walk links", links.size)
        return Output(climbsText, WebWalkWriter.hubs(gathered.hubs, links))
    }

    /**
     * Computes the ticks that it takes to run along a path, which covers two tiles in a tick.
     */
    private fun walkingTicks(start: Position, path: Collection<Position>): Int {
        var tiles = 0
        var previous = start
        for (waypoint in path) {
            tiles += previous.computeLongestDistance(waypoint)
            previous = waypoint
        }
        return (tiles + 1) / 2
    }

    /**
     * Writes the generated files.
     *
     * @param output The text to write.
     * @param directory The directory to write to.
     */
    fun write(output: Output, directory: Path) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(WebWalkLoader.CLIMBS), output.climbs)
        Files.writeString(directory.resolve(WebWalkLoader.HUBS), output.hubs)
    }

    /**
     * Finds the generated files that are not what [output] says they should be.
     *
     * @param output The text that the files should have.
     * @param directory The directory of the files.
     * @return The names of the files that are different or missing.
     */
    fun outOfDate(output: Output, directory: Path): List<String> {
        val expected = mapOf(WebWalkLoader.CLIMBS to output.climbs, WebWalkLoader.HUBS to output.hubs)
        val stale = ArrayList<String>()
        for ((file, text) in expected) {
            val path = directory.resolve(file)
            if (!Files.exists(path)) {
                stale += "$file (missing)"
                continue
            }
            val found = Files.readString(path).replace("\r\n", "\n")
            if (found != text) {
                stale += "$file (${firstDifference(text, found)})"
            }
        }
        return stale
    }

    /**
     * Describes the first line where two texts differ, for the report.
     */
    private fun firstDifference(expected: String, found: String): String {
        val expectedLines = expected.lines()
        val foundLines = found.lines()
        for (index in 0 until maxOf(expectedLines.size, foundLines.size)) {
            val a = expectedLines.getOrNull(index)
            val b = foundLines.getOrNull(index)
            if (a != b) {
                return "line ${index + 1}: generated '${a?.trim()}' but the file has '${b?.trim()}'; " +
                        "${expectedLines.size} lines generated, ${foundLines.size} in the file"
            }
        }
        return "no differing line"
    }
}

/**
 * Runs [WebWalkLiveGenerator] from a command or from the launch of the server.
 *
 * @author Hydrozoa
 */
object WebWalkGenerationRunner {

    /**
     * The directory of the files.
     */
    private val DIRECTORY = Path.of("data", "game", "bots", "webwalk")

    /**
     * Gathers on the game thread (so this must be called from it), then finishes on a thread of its own.
     *
     * @param check `true` to only report the files that are out of date instead of writing them.
     * @param exitWhenDone `true` to exit the server once finished, for generating from the command line.
     * @param feedback Called with each line of what happened, on the game thread.
     */
    fun run(check: Boolean, exitWhenDone: Boolean, feedback: (String) -> Unit) {
        fun say(line: String) {
            logger.info("[webwalk] {}", line)
            gameService.submit { feedback(line) }
        }

        val gathered = try {
            WebWalkLiveGenerator.gather()
        } catch (e: Exception) {
            logger.error("Could not gather the web-walker data!", e)
            say("Could not gather the data: $e")
            if (exitWhenDone) System.exit(1)
            return
        }
        say("Gathered ${gathered.climbs.size} climbs and ${gathered.hubs.size} hubs. Linking, which takes a while...")

        Thread({
            var failed = false
            try {
                val output = WebWalkLiveGenerator.finish(gathered, DIRECTORY)
                gathered.report.summary().forEach { say(it) }
                if (check) {
                    val stale = WebWalkLiveGenerator.outOfDate(output, DIRECTORY)
                    say(if (stale.isEmpty()) "The generated files are up to date." else "Out of date: $stale")
                } else {
                    WebWalkLiveGenerator.write(output, DIRECTORY)
                    say("Wrote ${WebWalkLoader.CLIMBS} and ${WebWalkLoader.HUBS} to $DIRECTORY.")
                }
            } catch (e: Throwable) {
                failed = true
                logger.error("Could not generate the web-walker data!", e)
                say("Could not generate the data: $e")
            }
            if (exitWhenDone) {
                System.exit(if (failed) 1 else 0)
            }
        }, "WebWalkGeneratorThread").start()
    }
}
