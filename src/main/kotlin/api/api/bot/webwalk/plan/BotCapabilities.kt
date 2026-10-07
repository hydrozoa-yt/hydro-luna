package api.bot.webwalk.plan

import engine.controllers.WildernessLocatableController.wildernessLevel
import io.luna.game.model.item.ItemContainer
import io.luna.game.model.mob.Skill
import io.luna.game.model.mob.bot.Bot

/**
 * Copies what a bot can do into a [CapabilitySnapshot].
 *
 * @author Hydrozoa
 */
object BotCapabilities {

    /**
     * Takes a snapshot of a bot. Must be called on the game thread, because it reads the bot as it is.
     *
     * The items are those in the inventory and equipment of the bot. Its bank is left out, because a trip can't use what
     * a bot has to go and get first.
     *
     * @param bot The bot.
     * @param usableTeleports The ids of the teleports that the bot can use right now. These are not worked out here yet,
     * as that needs the spells and jewellery to be matched to the teleports of `teleports.json`.
     * @param flags The unlocks of the bot.
     * @return The snapshot.
     */
    fun snapshot(bot: Bot, usableTeleports: Set<String> = emptySet(), flags: Set<String> = emptySet()):
            CapabilitySnapshot {
        val skills = Skill.IDS.associateWith { bot.skills.getSkill(it).staticLevel }
        val items = HashMap<Int, Int>()
        count(bot.inventory, items)
        count(bot.equipment, items)
        return CapabilitySnapshot(skills, items, flags, bot.wildernessLevel, usableTeleports)
    }

    /**
     * Adds the amount of every item of a container to a total.
     */
    private fun count(container: ItemContainer, totals: MutableMap<Int, Int>) {
        for (item in container) {
            if (item != null) {
                totals.merge(item.id, item.amount, Int::plus)
            }
        }
    }
}
