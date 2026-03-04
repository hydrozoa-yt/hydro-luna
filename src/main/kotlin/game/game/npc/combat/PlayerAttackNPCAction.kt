package game.npc.combat

import io.luna.game.action.*
import io.luna.game.model.*
import io.luna.game.model.mob.*
import java.util.*

/**
 * An [Action] implementation.
 */
class PlayerAttackNPCAction(private val source:Player, private val target:Mob) : Action<Mob>(source, ActionType.WEAK) {

    override fun run(): Boolean {
        System.out.println("Combat task ran");
        val distance = mob.position.computeLongestDistance(target.position)
        if (distance > 1) {
            System.out.println("Walked by combat task");
            mob.navigator.walkTo(target, Optional.empty(), false);
            return true
        }
        return false
    }

    override fun onFinished() {
        mob.interact(null)
    }
}