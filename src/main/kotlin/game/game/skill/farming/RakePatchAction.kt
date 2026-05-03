package game.skill.farming

import api.predef.*
import api.predef.ext.*
import game.player.*
import io.luna.game.action.*
import io.luna.game.action.impl.LockedAction
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.varp.*
import io.luna.game.model.`object`.GameObject

/**
 * todo
 * @author lare96
 */
class RakePatchAction(plr: Player, private val patchObject: GameObject) : Action<Player>(plr, ActionType.SOFT) {

    private var ticksLeft = 10
    private var varp = 0

    override fun run(): Boolean {
        mob.sendMessage("Raking the patch..."+ticksLeft)
        mob.animation(Animations.SUPERHEAT)

        mob.sendVarp(Varp(varp, 10))
        mob.sendMessage("Varp: "+varp)

        if (varp >= 725) {
            return true
        }
        varp += 1
        return false
    }

    override fun getDelay(): Int {
        return 6
    }
}