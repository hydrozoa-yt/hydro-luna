package game.skill.farming

import io.luna.game.model.mob.varp.*

/**
 * Represents any allotment patch.
 * Uses varp id 504 and 505
 *
 * @author hydrozoa
 */
class AllotmentPatch(val southEastern: Boolean) : FarmingPatch() {

    private var plantType: SeedType? = null

    override fun getVarpValue(): Int {
        var varpValue: Int = 3
        if (needsRaking()) {
            varpValue = 3 - weeds
        } else if (plantType == null) {
            varpValue = 3
        }

        if (southEastern) {
            varpValue = varpValue shl 8
        }

        return varpValue
    }
}