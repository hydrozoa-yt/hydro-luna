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
import kotlinx.coroutines.future.await
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

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
     * The pool that plans run on. It is small and its own, so that plans don't wait for the path searches of the navigators
     * (and the other way around). Its threads don't keep the server from exiting.
     */
    private val planPool: ExecutorService by lazy {
        val count = AtomicInteger()
        Executors.newFixedThreadPool(PLANNER_THREADS) { task ->
            Thread(task, "WebWalkPlanner-${count.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    /**
     * The number of threads that plan trips.
     */
    private const val PLANNER_THREADS = 2

    /**
     * Plans a trip for a bot, without taking it, on the thread that calls it. Must be called on the game thread, because it
     * reads the bot as it is. Prefer [planAsync], which doesn't hold up the game thread.
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
     * Plans a trip for a bot, without taking it, on the pool of the planner. Must be called on the game thread, because the
     * bot is read as it is now, and the search is done on the position and the capabilities that it has now.
     *
     * @param bot The bot.
     * @param destination Where it is going.
     * @param random Where the variation of the route comes from.
     * @return A future of the trip, or of `null` if the bot can't get there. It is completed on a thread of the pool, so
     * code that has to run on the game thread should continue on [io.luna.game.GameService.gameExecutor].
     */
    fun planAsync(bot: Bot, destination: Position, random: Random = Random()): CompletableFuture<WebWalkPlan?> {
        // The web and the planner are made here the first time, on the game thread, rather than on the pool.
        val planner = planner
        val start = bot.position
        val snapshot = BotCapabilities.snapshot(bot, graph.teleports)
        val intelligence = bot.personality.intelligence
        return CompletableFuture.supplyAsync({ planner.plan(start, destination, snapshot, intelligence, random) }, planPool)
    }

    /**
     * Walks a bot to a destination. Must be called from a bot script, on the game thread. The trip is planned on the pool of
     * the planner while the script waits, and the walk carries on on the game thread.
     *
     * @param bot The bot.
     * @param destination Where it is going.
     * @param radius How close to the destination, in tiles on the same plane, is close enough.
     * @return How it went.
     */
    suspend fun webWalk(bot: Bot, destination: Position, radius: Int = 0): WebWalkResult {
        val plan = try {
            planAsync(bot, destination).await()
        } catch (e: CancellationException) {
            throw e
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
