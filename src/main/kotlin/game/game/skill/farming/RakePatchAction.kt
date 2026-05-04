package game.skill.farming

import api.attr.*
import api.predef.*
import api.predef.ext.*
import game.player.*
import game.skill.farming.Farming.allotmentPatch
import game.skill.farming.Farming.herbPatch
import io.luna.game.action.*
import io.luna.game.model.item.*
import io.luna.game.model.mob.Player
import io.luna.game.model.`object`.GameObject

/**
 * todo
 * @author hydrozoa
 */
class RakePatchAction(plr: Player, private val patchObject: GameObject) : Action<Player>(plr, ActionType.SOFT, true, 4) {

    override fun onSubmit() {
        mob.sendMessage("Raking the patch...")
    }

    override fun run(): Boolean {
        var patch: FarmingPatch? = null
        if (Farming.HERB_PATCHES.contains(patchObject.id)) {
            patch = mob.herbPatch
        } else if (Farming.ALLOTMENT_PATCHES.contains(patchObject.id)) {
            patch = mob.allotmentPatch
        } else {
            System.err.println("Unknown patch raked")
            return true
        }

        if (!patch.needsRaking()) {
            mob.sendMessage("There's nothing left to rake.")
            return true
        }

        mob.animation(Animations.SUPERHEAT) // dummy just for visual feedback, remove once correct anim is found
        // todo send correct animation
        // todo send rake gfx

        var completedRaking = patch.rake() ?: true
        mob.sendVarp(patch.getVarp())
        mob.inventory.add(Item.byName("Weeds"))

        return completedRaking
    }
}