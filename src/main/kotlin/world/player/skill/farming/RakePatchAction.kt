package world.player.skill.farming

import io.luna.game.action.impl.LockedAction
import io.luna.game.model.mob.Player
import io.luna.game.model.`object`.GameObject

class RakePatchAction(plr: Player, private val patchObject: GameObject) : LockedAction(plr) {

    override fun run(): Boolean {
        return true
    }
}