package game.skill.farming

import io.luna.game.model.mob.varp.*

/**
 * Represents any herb patch.
 *
 * @hydrozoa
 */
class HerbPatch(val location: HerbPatchLocation) : FarmingPatch() {

    var plantType: HerbSeeds? = null
    var growthStage = 0 // from 0 to 5

    override fun getVarpValue(): Int {
        var varpValue: Int = 3
        if (needsRaking()) {
            varpValue = 3 - weeds
        } else if (plantType == null) {
            varpValue = 3
        } else if (plantType != null)  {
            varpValue = 3 + growthStage
        }
        varpValue = varpValue shl location.shifts
        return varpValue
    }

    /**
     * @return true if successfully planted
     */
    fun plant(seed: HerbSeeds): Boolean {
        if (weeds > 0) {
            return false
        }
        plantType = seed
        return true
    }

    fun reset() {
        plantType = null
        growthStage = 0
        weeds = 3
    }
}