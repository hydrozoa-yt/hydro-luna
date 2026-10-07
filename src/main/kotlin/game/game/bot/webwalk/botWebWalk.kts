package game.bot.webwalk

import api.bot.script.DynamicBotScript
import api.bot.webwalk.walk.WebWalker
import api.predef.*
import api.predef.ext.*
import io.luna.game.model.Position
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.bot.Bot
import io.luna.game.model.mob.bot.BotLogManager
import io.luna.game.model.mob.bot.brain.BotBrain
import io.luna.game.model.mob.bot.brain.BotBrain.BotCoordinator

/**
 * A command that walks one bot to a position with the web-walker, to test it. `::botwebwalk <bot name> <x> <y> [z]` walks to
 * a position, and `::botwebwalk <bot name> me` to where the player is.
 *
 * If no bot with that name is online, a temporary one without a brain is made at the position of the player, so that only
 * the walk is happening. No other bot is touched, and the web-walker is not used by anything else.
 */
cmd("botwebwalk", RIGHTS_DEV) {
    if (args.size < 2) {
        plr.sendMessage("Use ::botwebwalk <bot name> <x> <y> [z], or ::botwebwalk <bot name> me.")
        return@cmd
    }
    val name = args[0]
    val destination = if (args[1].equals("me", true)) plr.position else {
        if (args.size < 3) {
            plr.sendMessage("Use ::botwebwalk <bot name> <x> <y> [z], or ::botwebwalk <bot name> me.")
            return@cmd
        }
        Position(asInt(1), asInt(2), if (args.size > 3) asInt(3) else 0)
    }

    val online = world.players.firstOrNull { it is Bot && it.username.equals(name, true) } as Bot?
    if (online != null) {
        startWebWalk(plr, online, destination)
        return@cmd
    }

    plr.sendMessage("No bot named $name is online, making a temporary one here.")
    val bot = Bot.Builder(ctx).setTemporary().setUsername(name).setBrain(object : BotBrain() {
        override fun process(bot: Bot): BotCoordinator? = null
    }).build()
    bot.speechStack.setDisableAll(true)
    bot.logManager.setStreamType(BotLogManager.BotStreamType.ALL)
    bot.login().thenRun {
        bot.move(plr.position)
        world.scheduleOnce(3) { startWebWalk(plr, bot, destination) }
    }
}

/**
 * Plans a trip for [bot], tells [plr] about it, and sets the bot on its way.
 */
fun startWebWalk(plr: Player, bot: Bot, destination: Position) {
    val plan = try {
        WebWalker.plan(bot, destination)
    } catch (e: Exception) {
        plr.sendMessage("Could not plan a trip: $e")
        logger.error("Could not plan a web walk!", e)
        return
    }
    if (plan == null) {
        plr.sendMessage("${bot.username} has no way from ${bot.position} to $destination.")
        return
    }
    plr.sendMessage("${bot.username}: ${plan.legs.size} legs, cost ${"%.1f".format(plan.cost)}, from ${bot.position} to $destination.")
    plan.legs.groupBy { it.type }.forEach { (type, legs) -> plr.sendMessage("  $type x${legs.size}") }

    bot.scriptStack.pushHead(object : DynamicBotScript(bot) {
        override suspend fun run(): Boolean {
            val result = WebWalker.webWalk(bot, destination)
            plr.sendMessage("${bot.username}: ${if (result.success) "done" else "FAILED"}. ${result.message}")
            return true
        }
    })
}
