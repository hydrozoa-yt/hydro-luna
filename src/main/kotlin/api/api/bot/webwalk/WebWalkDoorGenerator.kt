package api.bot.webwalk

import com.google.gson.JsonParser
import io.luna.game.cache.Cache
import io.luna.game.cache.codec.MapDecoder
import io.luna.game.cache.map.MapIndexTable
import io.luna.game.model.Position
import io.luna.game.model.`object`.ObjectType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Makes `obstacles.json`, which holds every closed door, gate and curtain of the world.
 *
 * The doors come from the ids in `data/game/world/doors/` that are found in the map data of the cache. This needs no
 * running server, so it also runs as a test to make sure that the committed file is still up to date, and from
 * the `generateWebWalkDoors` Gradle task to write it.
 *
 * Only closed doors are listed, because open ones can be walked through. Only straight walls are listed, because those
 * are the only ones that [game.obj.doors.Doors] swings as gates and double doors. Diagonal doors, and doors that are not
 * walls, are left out and reported.
 *
 * The tiles on either side of a door are checked with the tile data of the cache alone, so a tile that is only blocked by
 * an object is still taken to be a tile that can be stood on. This is why doors are not checked against collision here.
 *
 * @author Hydrozoa
 */
object WebWalkDoorGenerator {

    /**
     * The files of door ids and the kind of obstacle that their doors make.
     */
    private val DOOR_FILES = listOf("doors.json" to EdgeType.DOOR,
                                    "double_doors.json" to EdgeType.DOOR,
                                    "gates.json" to EdgeType.GATE,
                                    "curtains.json" to EdgeType.CURTAIN)

    /**
     * The result of a generation.
     *
     * @property obstacles The text of `obstacles.json`.
     * @property report What was made and what was left out.
     */
    class Result(val obstacles: String, val report: GenerationReport)

    /**
     * Reads the ids of the closed doors, keyed to the kind of obstacle that they make.
     *
     * @param directory The directory of the door files, which is `data/game/world/doors`.
     * @return The kind of obstacle for each id of a closed door.
     */
    fun readClosedIds(directory: Path): Map<Int, EdgeType> {
        val ids = LinkedHashMap<Int, EdgeType>()
        for ((file, type) in DOOR_FILES) {
            val path = directory.resolve(file)
            if (!Files.exists(path)) {
                continue
            }
            val doors = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonArray }
            for (door in doors) {
                ids[door.asJsonObject.get("closed").asInt] = type
            }
        }
        return ids
    }

    /**
     * Opens the cache and decodes its map data.
     *
     * @return The map data.
     */
    fun loadMap(): MapIndexTable {
        val cache = Cache()
        cache.open()
        cache.runDecoders(null, MapDecoder())
        cache.waitForDecoders()
        val table = cache.mapIndexTable
        cache.close()
        return table
    }

    /**
     * Makes the obstacles of every closed door in the map data.
     *
     * @param table The map data.
     * @param closedIds The kind of obstacle for the id of each closed door.
     * @return The obstacles, which are not sorted, and what was left out.
     */
    fun generate(table: MapIndexTable, closedIds: Map<Int, EdgeType>): Pair<List<GeneratedObstacle>, GenerationReport> {
        val report = GenerationReport()
        val map = MapTiles(table)
        val walls = LinkedHashMap<PlacedWall, Position>()
        for (placed in table.objectSet) {
            val type = closedIds[placed.objectId] ?: continue
            if (placed.type != ObjectType.STRAIGHT_WALL) {
                report.skipped += "${type.name} ${placed.objectId} at ${DoorObstacles.describe(placed.position)}: " +
                        "a ${placed.type} rather than a straight wall"
                continue
            }
            val wall = PlacedWall(placed.objectId, placed.position, placed.rotation, type)
            if (walls.putIfAbsent(wall, placed.position) != null) {
                report.skipped += "${type.name} ${placed.objectId} at ${DoorObstacles.describe(placed.position)}: " +
                        "listed twice in the map"
            }
        }

        // The tiles that are stood on are one level lower where the wall is on a bridge, like the walls themselves.
        val obstacles = ArrayList<GeneratedObstacle>()
        for (wall in walls.keys) {
            val lowered = if (map.isBridged(wall.position)) 1 else 0
            val level = wall.position.z - lowered
            if (level < 0) {
                report.skipped += "${wall.type} ${wall.objectId} at ${DoorObstacles.describe(wall.position)}: " +
                        "below the bottom floor"
                continue
            }
            val from = Position(wall.position.x, wall.position.y, level)
            val other = DoorObstacles.otherSide(wall.position, wall.direction)
            val to = Position(other.x, other.y, level)
            if (!map.canStand(from) || !map.canStand(to)) {
                val tile = if (!map.canStand(from)) from else to
                report.skipped += "${wall.type} ${wall.objectId} at ${DoorObstacles.describe(wall.position)}: " +
                        "nothing to stand on at ${DoorObstacles.describe(tile)}"
                continue
            }
            obstacles += GeneratedObstacle(wall.type, wall.objectId, wall.position, from, to)
            report.count(wall.type.name.lowercase() + "s")
        }
        return Pair(obstacles, report)
    }

    /**
     * Makes `obstacles.json` from the files of the project.
     *
     * @param doorDirectory The directory of the door files.
     * @param table The map data.
     * @return The text of the file and what was left out.
     */
    fun generate(doorDirectory: Path, table: MapIndexTable): Result {
        val (obstacles, report) = generate(table, readClosedIds(doorDirectory))
        return Result(WebWalkWriter.obstacles(obstacles), report)
    }

    /**
     * Writes `obstacles.json`. Run by the `generateWebWalkDoors` Gradle task from the root of the project.
     *
     * @param args Optionally the directory to write to, which is `data/game/bots/webwalk` by default.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val output = Path.of(args.firstOrNull() ?: "data/game/bots/webwalk")
        val result = generate(Path.of("data/game/world/doors"), loadMap())
        Files.createDirectories(output)
        Files.writeString(output.resolve(WebWalkLoader.OBSTACLES), result.obstacles)
        result.report.summary().forEach { println(it) }
        println("Wrote ${output.resolve(WebWalkLoader.OBSTACLES)}")
        System.exit(0)
    }

    /**
     * Looks at the tiles of the map data.
     */
    private class MapTiles(private val table: MapIndexTable) {

        /**
         * Determines if a tile of the map data, which has map coordinates, is on a bridge.
         */
        fun isBridged(position: Position): Boolean = tile(position.x, position.y, 1)?.isBridge == true

        /**
         * Determines if a tile, at the height that it is stood on, has a floor that is not blocked.
         */
        fun canStand(position: Position): Boolean {
            // A bridged column has its tiles one level up in the map data.
            val raw = if (isBridged(position)) position.z + 1 else position.z
            if (raw > 3) {
                return false
            }
            val tile = tile(position.x, position.y, raw) ?: return false
            return !tile.isBlocked && (tile.overlay != 0 || tile.underlay != 0)
        }

        /**
         * Gets a tile of the map data, or `null` if the map has none there.
         */
        private fun tile(x: Int, y: Int, z: Int) =
            Position(x, y, z).let { if (table.indexTable.containsKey(it.region)) table.getTile(it) else null }
    }
}
