package game.skill.farming

import io.luna.game.model.mob.varp.*

/**
 * Represents any herb patch.
 *
 * @hydrozoa
 */
class HerbPatch(val location: HerbPatchLocation) : FarmingPatch() {

    private var plantType: HerbSeeds? = null
    private var growthStage = 0

    override fun getVarp(): Varp {
        var varpValue: Int = 3
        if (needsRaking()) {
            varpValue = 3 - weeds
        } else if (plantType == null) {
            varpValue = 3
        }
        varpValue = varpValue shl location.shifts

        return Varp(515, varpValue)
    }
}