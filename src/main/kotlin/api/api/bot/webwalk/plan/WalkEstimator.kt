package api.bot.webwalk.plan

import io.luna.game.model.Position
import io.luna.game.model.collision.CollisionManager
import io.luna.game.model.path.FallbackPathfinder
import io.luna.game.model.path.PathResultType
import io.luna.game.model.path.astar.LongRangePathfinder
import io.luna.game.model.path.route.RoutePathfinder
import io.luna.game.model.path.route.RouteStrategy

/**
 * Tells how long it takes to walk between two positions.
 *
 * The planner asks this to link the start and the destination of a trip to the nodes of the graph. It is not asked about
 * anything that is already linked in the graph.
 *
 * @author Hydrozoa
 */
fun interface WalkEstimator {

    /**
     * Finds the time that it takes to walk between two positions.
     *
     * @param from Where the walk starts.
     * @param to Where the walk ends.
     * @return The time, in game ticks while running, or `null` if the positions can't be walked between.
     */
    fun ticks(from: Position, to: Position): Int?
}

/**
 * A [WalkEstimator] that finds the time that it takes to run in a straight line, and that all positions on the same plane
 * can be walked between. It knows nothing of walls, so it is only for tests and for planning without a world.
 *
 * @author Hydrozoa
 */
object StraightLineWalkEstimator : WalkEstimator {

    override fun ticks(from: Position, to: Position): Int? {
        if (from.z != to.z) {
            return null
        }
        return ticksForTiles(from.computeLongestDistance(to))
    }
}

/**
 * Computes the ticks that it takes to run a number of tiles, which is two tiles in a tick.
 */
fun ticksForTiles(tiles: Int): Int = (tiles + 1) / 2

/**
 * A [WalkEstimator] that finds the route with the pathfinders that bots use, and counts its tiles.
 *
 * It reads the snapshots of the collision, so it is safe to use from any thread.
 *
 * @param collision The collision of the world.
 *
 * @author Hydrozoa
 */
class PathfinderWalkEstimator(collision: CollisionManager) : WalkEstimator {

    /**
     * The pathfinder that finds the routes. Routes beyond the range of the route finder go to a long range pathfinder.
     */
    private val pathfinder =
        FallbackPathfinder<Position>(RoutePathfinder(collision, 1, 0, RouteStrategy.NORMAL)) { origin ->
            LongRangePathfinder(collision, origin.z)
        }

    override fun ticks(from: Position, to: Position): Int? {
        val result = pathfinder.find(from, to)
        if (result.type != PathResultType.COMPLETE && result.type != PathResultType.EMPTY) {
            return null
        }
        var tiles = 0
        var previous = from
        for (waypoint in result.path) {
            tiles += previous.computeLongestDistance(waypoint)
            previous = waypoint
        }
        return ticksForTiles(tiles)
    }
}
