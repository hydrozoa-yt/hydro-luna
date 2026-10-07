package api.bot.webwalk

import io.luna.game.cache.Archive
import io.luna.game.cache.Cache
import io.luna.game.cache.codec.MapDecoder
import io.luna.game.cache.map.MapIndexTable
import io.luna.game.cache.map.MapTile
import io.luna.game.model.Position
import io.luna.game.model.`object`.ObjectDirection
import io.luna.game.model.`object`.ObjectType
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the web-walker graph over the terrain of the cache as a PNG, to get a quick idea of what the web looks like.
 *
 * Every tile is painted with the colour of its floor (or blue for water), darkened where it can't be walked on. Nodes are
 * drawn as dots and edges as lines, with a colour for each [EdgeType] and [NodeKind], and a legend in the corner. An edge
 * that has only one end on the drawn plane, like a ladder, is drawn as a ring on that end. One-way edges get an arrowhead.
 *
 * The `renderWebWalkMap` Gradle task runs this, see its properties in `build.gradle.kts`.
 *
 * @author Hydrozoa
 */
object WebWalkMapRenderer {

    /**
     * The tiles that are drawn, from the south-west corner to the north-east corner, both included.
     *
     * @property minX The lowest x.
     * @property minY The lowest y.
     * @property maxX The highest x.
     * @property maxY The highest y.
     */
    data class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {

        /**
         * The number of tiles from west to east.
         */
        val width: Int get() = maxX - minX + 1

        /**
         * The number of tiles from south to north.
         */
        val height: Int get() = maxY - minY + 1

        /**
         * @return `true` if the position is within the bounds, on any plane.
         */
        fun contains(position: Position) = position.x in minX..maxX && position.y in minY..maxY
    }

    /**
     * The tiles of the surface of the world, which is what is drawn unless told otherwise.
     */
    val OVERWORLD = Bounds(2048, 2496, 3903, 3967)

    /**
     * The colour of each type of edge.
     */
    val EDGE_COLORS = mapOf(EdgeType.WALK to Color(255, 235, 59),
                            EdgeType.DOOR to Color(255, 23, 68),
                            EdgeType.GATE to Color(255, 145, 0),
                            EdgeType.CURTAIN to Color(224, 64, 251),
                            EdgeType.LADDER to Color(0, 229, 255),
                            EdgeType.STAIR to Color(255, 255, 255),
                            EdgeType.TRAPDOOR to Color(124, 77, 255),
                            EdgeType.SHIP to Color(41, 121, 255),
                            EdgeType.TELEPORT to Color(118, 255, 3),
                            EdgeType.FAIRY_RING to Color(0, 230, 118))

    /**
     * The colour of each kind of node.
     */
    val NODE_COLORS = mapOf(NodeKind.HUB to Color(255, 255, 255),
                            NodeKind.OBSTACLE to Color(30, 30, 30),
                            NodeKind.TELEPORT_DEST to Color(118, 255, 3),
                            NodeKind.SHIP_PORT to Color(41, 121, 255),
                            NodeKind.FAIRY_RING to Color(0, 230, 118))

    /**
     * The colour of walls.
     */
    private val WALL = Color(240, 240, 240)

    /**
     * The colour of water.
     */
    private const val WATER = 0x2a4f9e

    /**
     * The colour of a floor that the cache gives none for.
     */
    private const val UNKNOWN_FLOOR = 0x6b5b3a

    /**
     * The colour that the cache uses for a floor that has no colour of its own, because it has a texture.
     */
    private const val NO_COLOR = 0xff00ff

    /**
     * Reads the colour of every floor, which are indexed by their id minus one, as the overlays and underlays of the map data
     * are.
     *
     * @param cache An open cache.
     * @return The colour of each floor, as `0xRRGGBB`.
     */
    private fun readFloorColors(cache: Cache): IntArray {
        val buf = Archive.decode(cache.getFile(0, 2)).getFileData("flo.dat")
        try {
            val colors = IntArray(buf.readUnsignedShort())
            for (id in colors.indices) {
                var rgb = UNKNOWN_FLOOR
                while (true) {
                    when (buf.readUnsignedByte().toInt()) {
                        0 -> break
                        1 -> rgb = buf.readUnsignedMedium()
                        2 -> buf.readUnsignedByte()
                        3 -> {}
                        5 -> {}
                        6 -> {
                            while (buf.readByte().toInt() != 10) {
                                // The name of the floor.
                            }
                        }
                        7 -> buf.readUnsignedMedium()
                        else -> {}
                    }
                }
                colors[id] = if (rgb == NO_COLOR) UNKNOWN_FLOOR else rgb
            }
            return colors
        } finally {
            buf.release()
        }
    }

    /**
     * Opens the cache and reads its map data and floor colours.
     *
     * @return The map data, and the colour of each floor.
     */
    fun loadTerrain(): Pair<MapIndexTable, IntArray> {
        val cache = Cache()
        cache.open()
        val floors = try {
            readFloorColors(cache)
        } catch (e: Exception) {
            IntArray(0)
        }
        cache.runDecoders(null, MapDecoder())
        cache.waitForDecoders()
        val table = cache.mapIndexTable
        cache.close()
        return table to floors
    }

    /**
     * Draws the graph.
     *
     * @param table The map data.
     * @param floors The colour of each floor.
     * @param graph The graph.
     * @param plane The plane to draw.
     * @param bounds The tiles to draw.
     * @param scale The number of pixels along the side of a tile.
     * @return The image, with north at the top.
     */
    fun render(table: MapIndexTable, floors: IntArray, graph: WebWalkGraph, plane: Int, bounds: Bounds, scale: Int):
            BufferedImage {
        val image = BufferedImage(bounds.width * scale, bounds.height * scale, BufferedImage.TYPE_INT_RGB)
        paintTerrain(image, table, floors, plane, bounds, scale)

        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val walls = drawWalls(g, table, plane, bounds, scale)
        val centre = scale / 2.0
        fun px(p: Position) = (p.x - bounds.minX) * scale + centre
        fun py(p: Position) = (bounds.maxY - p.y) * scale + centre
        fun visible(p: Position) = p.z == plane && bounds.contains(p)

        // An edge and its reverse are drawn once.
        val drawn = HashSet<Pair<String, String>>()
        val oneWay = graph.edges.map { it.from to it.to }.toHashSet()
        val counts = HashMap<EdgeType, Int>()
        val lineWidth = max(1f, scale / 3f)
        val dot = max(3.0, scale * 1.6)

        // The walks go under the rest, so that the doors and ladders on them can be seen.
        for (edge in graph.edges.sortedBy { if (it.type == EdgeType.WALK) 0 else 1 }) {
            val a = graph.node(edge.from) ?: continue
            val b = graph.node(edge.to) ?: continue
            val aShown = visible(a.position)
            val bShown = visible(b.position)
            if (!aShown && !bShown) {
                continue
            }
            val key = if (edge.from < edge.to) edge.from to edge.to else edge.to to edge.from
            if (!drawn.add(key)) {
                continue
            }
            counts.merge(edge.type, 1, Int::plus)
            g.color = EDGE_COLORS.getValue(edge.type)
            if (aShown && bShown) {
                g.stroke = BasicStroke(lineWidth)
                g.drawLine(px(a.position).roundToInt(), py(a.position).roundToInt(),
                           px(b.position).roundToInt(), py(b.position).roundToInt())
                if ((edge.to to edge.from) !in oneWay) {
                    arrowhead(g, px(a.position), py(a.position), px(b.position), py(b.position), dot)
                }
            } else {
                val end = if (aShown) a.position else b.position
                g.stroke = BasicStroke(max(1f, lineWidth))
                val r = dot * 1.6
                g.drawOval((px(end) - r).roundToInt(), (py(end) - r).roundToInt(), (r * 2).roundToInt(), (r * 2).roundToInt())
            }
        }

        val kinds = HashMap<NodeKind, Int>()
        for (node in graph.nodes.values) {
            if (!visible(node.position)) {
                continue
            }
            kinds.merge(node.kind, 1, Int::plus)
            val x = px(node.position)
            val y = py(node.position)
            g.color = Color.BLACK
            g.fillOval((x - dot / 2 - 1).roundToInt(), (y - dot / 2 - 1).roundToInt(), (dot + 2).roundToInt(), (dot + 2).roundToInt())
            g.color = NODE_COLORS.getValue(node.kind)
            g.fillOval((x - dot / 2).roundToInt(), (y - dot / 2).roundToInt(), dot.roundToInt(), dot.roundToInt())
        }

        legend(g, plane, counts, kinds, walls)
        g.dispose()
        return image
    }

    /**
     * Draws the walls of the map data along the edges of the tiles that they stand on. A straight wall is one edge, a wall
     * corner is two, and diagonal walls are drawn across the tile.
     *
     * @return The number of walls drawn.
     */
    private fun drawWalls(g: java.awt.Graphics2D, table: MapIndexTable, plane: Int, bounds: Bounds, scale: Int): Int {
        g.color = WALL
        g.stroke = BasicStroke(max(1f, scale / 4f), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
        var drawn = 0
        for (obj in table.objectSet) {
            val type = obj.type
            if (type != ObjectType.STRAIGHT_WALL && type != ObjectType.WALL_CORNER &&
                type != ObjectType.DIAGONAL_CORNER_WALL && type != ObjectType.DIAGONAL_WALL) {
                continue
            }
            val pos = obj.position
            if (!bounds.contains(pos) || !table.indexTable.containsKey(pos.region)) {
                continue
            }
            // Walls on a bridge are one plane up in the map data, like its floor.
            val bridged = pos.z < 3 && table.getTile(Position(pos.x, pos.y, 1)).isBridge
            if (pos.z - (if (bridged) 1 else 0) != plane) {
                continue
            }
            val left = (pos.x - bounds.minX) * scale
            val top = (bounds.maxY - pos.y) * scale
            val right = left + scale
            val bottom = top + scale
            val rotation = when (obj.rotation) {
                ObjectDirection.WEST -> 0
                ObjectDirection.NORTH -> 1
                ObjectDirection.EAST -> 2
                else -> 3
            }
            fun edge(side: Int) = when (side % 4) {
                0 -> g.drawLine(left, top, left, bottom)
                1 -> g.drawLine(left, top, right, top)
                2 -> g.drawLine(right, top, right, bottom)
                else -> g.drawLine(left, bottom, right, bottom)
            }
            when (type) {
                ObjectType.STRAIGHT_WALL -> edge(rotation)
                ObjectType.WALL_CORNER -> {
                    edge(rotation)
                    edge(rotation + 1)
                }
                ObjectType.DIAGONAL_CORNER_WALL ->
                    // Cuts across the corner that the edges 'rotation' and 'rotation + 1' meet at.
                    when (rotation) {
                        0 -> g.drawLine(left, (top + bottom) / 2, (left + right) / 2, top)
                        1 -> g.drawLine((left + right) / 2, top, right, (top + bottom) / 2)
                        2 -> g.drawLine(right, (top + bottom) / 2, (left + right) / 2, bottom)
                        else -> g.drawLine((left + right) / 2, bottom, left, (top + bottom) / 2)
                    }
                else ->
                    if (rotation % 2 == 0) g.drawLine(left, top, right, bottom) else g.drawLine(left, bottom, right, top)
            }
            drawn++
        }
        return drawn
    }

    /**
     * Paints the floor of every tile.
     */
    private fun paintTerrain(image: BufferedImage, table: MapIndexTable, floors: IntArray, plane: Int, bounds: Bounds,
                             scale: Int) {
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        val width = image.width
        for (region in table.allRegions) {
            val origin = region.absPosition
            if (origin.x + 63 < bounds.minX || origin.x > bounds.maxX || origin.y + 63 < bounds.minY || origin.y > bounds.maxY) {
                continue
            }
            val grid = table.tileSet.getGrid(table.indexTable[region])
            for (dx in 0 until 64) {
                for (dy in 0 until 64) {
                    val x = origin.x + dx
                    val y = origin.y + dy
                    if (x !in bounds.minX..bounds.maxX || y !in bounds.minY..bounds.maxY) {
                        continue
                    }
                    // The floor of a bridge is on the plane above it in the map data.
                    val bridged = plane < 3 && grid.getTile(dx, dy, plane + 1).isBridge
                    val tile = grid.getTile(dx, dy, if (bridged) plane + 1 else plane)
                    val colour = colourOf(tile, floors)
                    val left = (x - bounds.minX) * scale
                    val top = (bounds.maxY - y) * scale
                    for (row in top until top + scale) {
                        java.util.Arrays.fill(pixels, row * width + left, row * width + left + scale, colour)
                    }
                }
            }
        }
    }

    /**
     * The colour of one tile, which is dark where it can't be walked on and black where there is no floor.
     */
    private fun colourOf(tile: MapTile, floors: IntArray): Int {
        if (tile.isWater) {
            return WATER
        }
        val id = if (tile.overlay != 0) tile.overlay else tile.underlay
        if (id == 0) {
            return 0
        }
        val rgb = floors.getOrNull(id - 1) ?: UNKNOWN_FLOOR
        val factor = if (tile.isBlocked) 0.45 else 0.8
        val r = ((rgb shr 16 and 0xff) * factor).toInt()
        val g = ((rgb shr 8 and 0xff) * factor).toInt()
        val b = ((rgb and 0xff) * factor).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * Draws an arrowhead on the middle of the line from a to b.
     */
    private fun arrowhead(g: java.awt.Graphics2D, ax: Double, ay: Double, bx: Double, by: Double, size: Double) {
        val angle = atan2(by - ay, bx - ax)
        val mx = (ax + bx) / 2
        val my = (ay + by) / 2
        val spread = Math.PI / 6
        val x = IntArray(3)
        val y = IntArray(3)
        x[0] = (mx + cos(angle) * size).roundToInt()
        y[0] = (my + sin(angle) * size).roundToInt()
        x[1] = (mx - cos(angle - spread) * size).roundToInt()
        y[1] = (my - sin(angle - spread) * size).roundToInt()
        x[2] = (mx - cos(angle + spread) * size).roundToInt()
        y[2] = (my - sin(angle + spread) * size).roundToInt()
        g.fillPolygon(x, y, 3)
    }

    /**
     * Draws the legend, with what is drawn and how many of each, in the top-left corner.
     */
    private fun legend(g: java.awt.Graphics2D, plane: Int, edges: Map<EdgeType, Int>, nodes: Map<NodeKind, Int>,
                       walls: Int) {
        val rows = EdgeType.entries.filter { it in edges }.map { Triple(EDGE_COLORS.getValue(it), "${it.name} edges", edges.getValue(it)) } +
                NodeKind.entries.filter { it in nodes }.map { Triple(NODE_COLORS.getValue(it), "${it.name} nodes", nodes.getValue(it)) } +
                Triple(WALL, "walls", walls)
        val font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        g.font = font
        val line = 26
        val boxWidth = 260
        val boxHeight = line * (rows.size + 1) + 16
        g.color = Color(0, 0, 0, 200)
        g.fillRoundRect(12, 12, boxWidth, boxHeight, 12, 12)
        g.color = Color.WHITE
        g.drawString("Web walk, plane $plane", 24, 12 + 8 + 18)
        for ((index, row) in rows.withIndex()) {
            val y = 12 + 8 + line * (index + 1) + 18
            g.color = row.first
            g.fillRect(24, y - 12, 22, 12)
            g.color = Color.WHITE
            g.drawString("${row.second}: ${row.third}", 56, y)
        }
    }

    /**
     * Reads a system property, which the Gradle task sets, as a number.
     */
    private fun intProperty(name: String, default: Int): Int =
        System.getProperty(name)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: default

    /**
     * Runs the renderer. Properties are `luna.webwalk.map.plane`, `scale`, `bounds` (`minX,minY,maxX,maxY`, or `auto` to
     * fit the nodes of the plane), `dir` (of the web-walker files) and `out` (the PNG to write).
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val plane = intProperty("luna.webwalk.map.plane", 0)
        val scale = intProperty("luna.webwalk.map.scale", 3).coerceAtLeast(1)
        val directory = Path.of(System.getProperty("luna.webwalk.map.dir", "data/game/bots/webwalk"))
        val out = Path.of(System.getProperty("luna.webwalk.map.out", "build/webwalk/webwalk-map.png"))

        val graph = WebWalkLoader.load(directory)
        println("Loaded ${graph.nodes.size} nodes and ${graph.edges.size} edges from $directory.")

        val boundsText = System.getProperty("luna.webwalk.map.bounds")?.trim().orEmpty()
        val bounds = when {
            boundsText == "auto" -> {
                val onPlane = graph.nodes.values.map { it.position }.filter { it.z == plane }
                require(onPlane.isNotEmpty()) { "No nodes on plane $plane to fit the bounds to." }
                Bounds(onPlane.minOf { it.x } - 32, onPlane.minOf { it.y } - 32,
                       onPlane.maxOf { it.x } + 32, onPlane.maxOf { it.y } + 32)
            }
            boundsText.isNotEmpty() -> {
                val (a, b, c, d) = boundsText.split(',').map { it.trim().toInt() }
                Bounds(a, b, c, d)
            }
            else -> OVERWORLD
        }

        println("Decoding the cache...")
        val (table, floors) = loadTerrain()
        println("Drawing plane $plane, x ${bounds.minX}-${bounds.maxX}, y ${bounds.minY}-${bounds.maxY} at $scale px per tile...")
        val image = render(table, floors, graph, plane, bounds, scale)

        Files.createDirectories(out.toAbsolutePath().parent)
        ImageIO.write(image, "png", out.toFile())
        println("Wrote ${out.toAbsolutePath()} (${image.width}x${image.height}).")
    }
}
