package api.bot.webwalk.generate

import io.luna.game.model.Position
import java.util.Random

/**
 * Spreads hubs over an area, so that no tile of it is far from a node of the web-walker graph.
 *
 * The area is divided into square cells, and each cell gets one hub at a random tile of its own, if it has a tile that can
 * be stood on. Two hubs of neighbouring cells are never farther apart than twice the size of a cell, so the size of a cell
 * decides how far the walks of the graph are, which the pathfinder that does them has a range for.
 *
 * The tiles are random, but they are the same every time for the same seed: each cell draws from a generator of its own,
 * which only depends on the seed and the position of the cell, so the order the cells are visited in doesn't matter.
 *
 * @author Hydrozoa
 */
object ScatterHubs {

    /**
     * The tag of every hub made here.
     */
    const val TAG = "scatter"

    /**
     * How many random tiles are tried in a cell before it is given up on.
     */
    private const val TRIES = 12

    /**
     * Makes the hubs of an area.
     *
     * @param seed The seed of the random tiles.
     * @param cellSize The width and length of a cell, in tiles. Hubs of cells next to each other are at most
     * `2 * cellSize - 1` tiles apart.
     * @param minX The smallest x of the area.
     * @param minY The smallest y of the area.
     * @param maxX The largest x of the area.
     * @param maxY The largest y of the area.
     * @param plane The plane of the hubs.
     * @param occupied The cells that already have a node and are left empty, written as `x / cellSize` and `y / cellSize`.
     * @param standable Determines if a tile can be stood on, including that it is in the area that is wanted.
     * @return The hubs, which are not sorted. The id of a hub is `scatter_<cell x>_<cell y>`.
     */
    fun generate(seed: Long, cellSize: Int, minX: Int, minY: Int, maxX: Int, maxY: Int, plane: Int,
                 occupied: Set<Pair<Int, Int>>, standable: (Position) -> Boolean): List<GeneratedHub> {
        require(cellSize > 0) { "The size of a cell must be positive." }
        val hubs = ArrayList<GeneratedHub>()
        for (cellX in minX / cellSize..maxX / cellSize) {
            for (cellY in minY / cellSize..maxY / cellSize) {
                if (Pair(cellX, cellY) in occupied) {
                    continue
                }
                val random = Random((seed * 1_000_003L + cellX * 73_856_093L) xor (cellY * 19_349_663L))
                for (attempt in 0 until TRIES) {
                    val x = cellX * cellSize + random.nextInt(cellSize)
                    val y = cellY * cellSize + random.nextInt(cellSize)
                    if (x < minX || x > maxX || y < minY || y > maxY) {
                        continue
                    }
                    val position = Position(x, y, plane)
                    if (standable(position)) {
                        hubs += GeneratedHub("${TAG}_${cellX}_$cellY", position, setOf(TAG))
                        break
                    }
                }
            }
        }
        return hubs
    }
}
