package game.skill.farming

import api.attr.*
import io.luna.game.model.mob.*
import io.luna.game.model.mob.varp.*

/**
 *
 * @author lare96
 */
object Farming {

    /**
     * An attribute representing all herb patches.
     */
    val Player.herbPatches by Attr.map<HerbPatchLocation, HerbPatch> {
        val map = HashMap<HerbPatchLocation, HerbPatch>()
        HerbPatchLocation.values().forEach { location ->
            map[location] = HerbPatch(location)
        }
        map
    }

    /**
     * An attribute representing an allotment patch.
     * todo fix by making it a map like herb patches maybe?
     */
    val Player.allotmentPatch by Attr.obj<AllotmentPatch>{ AllotmentPatch(false) }.persist("allotment-patch")

    fun sendHerbState(plr: Player) {
        var herbState: Int = 0
        plr.herbPatches.values.forEach { patch ->
            herbState += patch.getVarpValue()
        }
        plr.sendVarp(Varp(515, herbState))
    }

}