package api.bot.webwalk.walk

import api.bot.Suspendable.waitFor
import api.bot.action.BotActionHandler
import api.bot.webwalk.model.EdgeType
import api.bot.webwalk.plan.PlanLeg
import api.bot.webwalk.plan.WebWalkPlan
import api.predef.*
import api.predef.ext.*
import engine.obj.Trapdoor
import io.luna.game.model.Position
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.dialogue.OptionDialogue
import io.luna.game.model.mob.movement.NavigationResult
import io.luna.game.model.`object`.GameObject
import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Takes a bot along a [WebWalkPlan], one leg at a time, until it is at the destination.
 *
 * - Walks go through the navigator of the bot. A walk that is followed by another walk is let go of a few tiles short, so
 *   that the bot doesn't stop between them.
 * - Doors, gates and curtains are opened if they are closed, and then walked through. If another bot opened the door in the
 *   meantime, that's fine.
 * - Ladders and stairs are climbed with the option that the plan says, and when that is "Climb" the bot also chooses up or
 *   down in the dialogue that asks.
 * - Trapdoors are opened if they are closed, then climbed down.
 * - Teleports, ships and fairy rings are not supported yet, so a plan with one of those fails when it gets to it.
 *
 * Nothing is retried beyond a door that fails to open, and the walk stops at the first leg that fails. The bot is also done
 * as soon as it is within the radius of the destination, even if the plan has legs left.
 *
 * @param bot The bot.
 * @param handler The actions of the bot.
 * @param destination Where the bot is going.
 * @param radius How close to the destination, in tiles on the same plane, is close enough.
 *
 * @author Hydrozoa
 */
class WebWalkExecutor(private val bot: Bot,
                      private val handler: BotActionHandler,
                      private val destination: Position,
                      private val radius: Int = 0) {

    companion object {

        /**
         * The longest a walk of a single leg may take. Legs are short, so this only catches a bot that is stuck.
         */
        private val WALK_TIMEOUT = 60.seconds

        /**
         * How long to wait for a door to open, a ladder to be climbed, or a dialogue to show.
         */
        private val ACTION_TIMEOUT = 10.seconds

        /**
         * How many times a door is tried before giving up on it.
         */
        private const val DOOR_ATTEMPTS = 3

        /**
         * How far, in tiles, a bot may land from where a climb says, because landings can be anywhere in an area.
         */
        private const val LANDING_SLACK = 8

        /**
         * How close, in tiles, a bot has to be to the end of a walk for it to carry on with another walk.
         */
        private const val WALK_SLACK = 3

        /**
         * Determines if a climb goes up, according to where it starts and where it ends.
         *
         * A climb changes the plane, or else it goes into or out of a dungeon, which is 6400 tiles north of the surface.
         */
        fun isUp(from: Position, to: Position): Boolean {
            if (from.z != to.z) {
                return to.z > from.z
            }
            val north = to.y - from.y
            return if (Math.abs(north) > 3000) north < 0 else true
        }

        /**
         * Finds the number of the menu option of an object, from 1, which is the first one that has a name.
         *
         * @param actions The actions of the object.
         * @param name The name of the option wanted, with any case.
         * @return The number of the option, or `null` if the object has none of that name, or it is past the third.
         */
        fun optionNumber(actions: List<String?>, name: String): Int? {
            val index = actions.indexOfFirst { it != null && it.equals(name, ignoreCase = true) }
            return if (index in 0..2) index + 1 else null
        }
    }

    /**
     * Takes the bot along a plan.
     *
     * @param plan The plan.
     * @return How it went.
     */
    suspend fun execute(plan: WebWalkPlan): WebWalkResult {
        for ((index, leg) in plan.legs.withIndex()) {
            if (atDestination()) {
                return done(plan, index, "Arrived early, before leg ${index + 1} of ${plan.legs.size}.")
            }
            val next = plan.legs.getOrNull(index + 1)
            val failure = when (leg.type) {
                EdgeType.WALK -> walk(leg.to, if (next?.type == EdgeType.WALK) WALK_SLACK else 0)
                EdgeType.DOOR, EdgeType.GATE, EdgeType.CURTAIN -> crossDoor(leg)
                EdgeType.LADDER, EdgeType.STAIR -> climb(leg)
                EdgeType.TRAPDOOR -> climbTrapdoor(leg)
                else -> "${leg.type} legs are not supported yet"
            }
            if (failure != null) {
                return WebWalkResult(false, "Leg ${index + 1} of ${plan.legs.size} (${leg.type} to ${leg.to}) failed: " +
                        failure, plan, index)
            }
        }
        if (!atDestination()) {
            return WebWalkResult(false, "The plan is done, but the bot is at ${bot.position}, not near $destination.",
                                 plan, plan.legs.size)
        }
        return done(plan, plan.legs.size, "Arrived.")
    }

    /**
     * Makes the result of a walk that worked.
     */
    private fun done(plan: WebWalkPlan, legs: Int, message: String): WebWalkResult {
        bot.navigator.cancel()
        return WebWalkResult(true, message, plan, legs)
    }

    /**
     * Determines if the bot is close enough to the destination.
     */
    private fun atDestination(): Boolean =
        bot.position.z == destination.z && bot.position.computeLongestDistance(destination) <= radius

    /**
     * Determines if the bot is within a number of tiles of a position, on the same plane.
     */
    private fun near(position: Position, tiles: Int): Boolean =
        bot.position.z == position.z && bot.position.computeLongestDistance(position) <= tiles

    /**
     * Walks to a position.
     *
     * @param target The position.
     * @param slack How many tiles short the bot may be let go, because another walk comes next, or `0` to stop exactly.
     * @return `null` if the bot got there, or why it didn't.
     */
    private suspend fun walk(target: Position, slack: Int): String? {
        val future: CompletableFuture<NavigationResult> = bot.navigator.navigate(target, true)
        val finished = waitFor(WALK_TIMEOUT) { future.isDone || (slack > 0 && near(target, slack)) || atDestination() }
        if (!finished) {
            bot.navigator.cancel()
            return "timed out walking to $target from ${bot.position}"
        }
        if (!future.isDone) {
            // Let go short of the target, for the next walk or because the bot has arrived.
            return null
        }
        if (future.isCancelled) {
            return "the walk to $target was interrupted"
        }
        val result = future.await()
        return if (result == NavigationResult.REACHED) null else "could not walk to $target from ${bot.position} ($result)"
    }

    /**
     * Finds an object by position and one of its ids, or `null` if it is not there.
     */
    private fun find(position: Position, vararg ids: Int): GameObject? =
        world.objects.findAll(position).filter { it.id in ids }.findFirst().orElse(null)

    /**
     * Opens a door, gate or curtain if it is closed, and walks through it.
     *
     * @return `null` if the bot got through, or why it didn't.
     */
    private suspend fun crossDoor(leg: PlanLeg): String? {
        val action = leg.action ?: return "the leg has no object"
        val closedId = action.objectId ?: return "the leg has no object id"
        val position = action.objectPosition ?: return "the leg has no object position"
        var lastProblem = "the door did not open"
        repeat(DOOR_ATTEMPTS) {
            val door = find(position, closedId)
            if (door != null) {
                if (!handler.interactions.interact(1, door)) {
                    lastProblem = "could not reach the door $closedId at $position"
                    return@repeat
                }
                if (!waitFor(ACTION_TIMEOUT) { find(position, closedId) == null }) {
                    lastProblem = "the door $closedId at $position did not open"
                    return@repeat
                }
            }
            val failure = walk(leg.to, 0)
            if (failure == null) {
                return null
            }
            lastProblem = failure
        }
        return lastProblem
    }

    /**
     * Climbs a ladder or staircase.
     *
     * @return `null` if the bot arrived, or why it didn't.
     */
    private suspend fun climb(leg: PlanLeg): String? {
        val action = leg.action ?: return "the leg has no object"
        val id = action.objectId ?: return "the leg has no object id"
        val position = action.objectPosition ?: return "the leg has no object position"
        val obj = find(position, id) ?: return "there is no object $id at $position"
        val name = action.option ?: "Climb"
        val option = optionNumber(obj.def().actions, name) ?: return "object $id has no '$name' option"

        if (!handler.interactions.interact(option, obj)) {
            return "could not reach the object $id at $position"
        }
        if (name.equals("Climb", ignoreCase = true)) {
            // "Climb" asks which way, unless the bot is already on its way.
            waitFor(ACTION_TIMEOUT) { bot.overlays[OptionDialogue::class] != null || arrived(leg) }
            if (!arrived(leg) && !handler.widgets.clickDialogueOption(if (isUp(leg.from, leg.to)) 1 else 2)) {
                return "could not choose a direction"
            }
        }
        return if (awaitArrival(leg)) null else
            "the bot did not arrive near ${leg.to}, it is at ${bot.position}"
    }

    /**
     * Opens a trapdoor if it is closed, then climbs down it.
     *
     * @return `null` if the bot arrived, or why it didn't.
     */
    private suspend fun climbTrapdoor(leg: PlanLeg): String? {
        val action = leg.action ?: return "the leg has no object"
        val id = action.objectId ?: return "the leg has no object id"
        val position = action.objectPosition ?: return "the leg has no object position"
        val trapdoor = Trapdoor.entries.firstOrNull { it.closed == id || it.open == id }
            ?: return "object $id is not a known trapdoor"
        val ids = listOfNotNull(trapdoor.closed, trapdoor.open).toIntArray()

        var obj = find(position, *ids) ?: return "there is no trapdoor $id at $position"
        if (obj.id == trapdoor.closed) {
            if (!handler.interactions.interact(1, obj)) {
                return "could not reach the trapdoor at $position"
            }
            if (!waitFor(ACTION_TIMEOUT) { find(position, trapdoor.open) != null }) {
                return "the trapdoor at $position did not open"
            }
            obj = find(position, trapdoor.open) ?: return "the trapdoor at $position closed again"
        }
        if (!handler.interactions.interact(1, obj)) {
            return "could not reach the open trapdoor at $position"
        }
        return if (awaitArrival(leg)) null else
            "the bot did not arrive near ${leg.to}, it is at ${bot.position}"
    }

    /**
     * Determines if the bot has landed where a climb leads.
     */
    private fun arrived(leg: PlanLeg): Boolean = near(leg.to, LANDING_SLACK)

    /**
     * Waits until the bot has landed where a climb leads and is free to move again. The climb locks the bot until it is
     * done, and a walk that starts while it is locked is dropped, which is why being in place is not enough.
     *
     * @return `true` if the bot landed and was free before the timeout.
     */
    private suspend fun awaitArrival(leg: PlanLeg): Boolean = waitFor(ACTION_TIMEOUT) { arrived(leg) && !bot.isLocked }
}
