package api.bot.webwalk.walk

import api.bot.webwalk.data.WebWalkLoader
import api.bot.webwalk.model.WebWalkGraph
import api.bot.webwalk.plan.BotCapabilities
import api.bot.webwalk.plan.DoorCrossings
import api.bot.webwalk.plan.PathfinderWalkEstimator
import api.bot.webwalk.plan.WebWalkPlan
import api.bot.webwalk.plan.WebWalkPlanner
import api.predef.*
import io.luna.game.model.Position
import io.luna.game.model.mob.bot.Bot
import java.nio.file.Path
import java.util.Random

/**
 * Walks bots anywhere in the world: plans a trip, and takes the bot along it, through doors and up ladders.
 *
 * This is separate from the zone travel of bots (`BotActionHandler.travelTo`), which it doesn't use and doesn't change. A bot
 * only walks this way when a script, or the `::botwebwalk` command, asks it to.
 *
 * @author Hydrozoa
 */
object WebWalker {

    /**
     * The directory of the files of the web.
     */
    private val DIRECTORY = Path.of("data", "game", "bots", "webwalk")

    /**
     * The web, which is read the first time that it is needed and is shared by all bots.
     */
    private val graph: WebWalkGraph by lazy { WebWalkLoader.load(DIRECTORY) }

    /**
     * The planner, which is made the first time that it is needed. It reads the web and the collision of the world, and is
     * shared by all bots.
     */
    private val planner: WebWalkPlanner by lazy {
        WebWalkPlanner(graph, PathfinderWalkEstimator(world.collisionManager, doors = DoorCrossings.of(graph)))
    }

    /**
     * Plans a trip for a bot, without taking it. Must be called on the game thread, because it reads the bot as it is.
     *
     * @param bot The bot.
     * @param destination Where it is going.
     * @param random Where the variation of the route comes from.
     * @return The trip, or `null` if the bot can't get there.
     */
    fun plan(bot: Bot, destination: Position, random: Random = Random()): WebWalkPlan? =
        planner.plan(bot.position, destination, BotCapabilities.snapshot(bot, graph.teleports), bot.personality.intelligence,
                     random)

    /**
     * Walks a bot to a destination. Must be called from a bot script, on the game thread.
     *
     * @param bot The bot.
     * @param destination Where it is going.
     * @param radius How close to the destination, in tiles on the same plane, is close enough.
     * @return How it went.
     */
    suspend fun webWalk(bot: Bot, destination: Position, radius: Int = 0): WebWalkResult {
        val plan = try {
            plan(bot, destination)
        } catch (e: Exception) {
            logger.error("Could not plan a web walk for ${bot.username}!", e)
            return WebWalkResult(false, "Could not plan the trip: $e")
        } ?: return WebWalkResult(false, "No trip from ${bot.position} to $destination.")

        bot.log("WebWalk: $plan")
        val disabledBefore = bot.reflex.isDisableCombatReflex
        try {
            bot.reflex.isDisableCombatReflex = true
            bot.actionHandler.widgets.clickRunning(true)
            val result = WebWalkExecutor(bot, bot.actionHandler, destination, radius).execute(plan)
            bot.log("WebWalk: ${result.message}")
            return result
        } finally {
            bot.reflex.isDisableCombatReflex = disabledBefore
        }
    }
}
