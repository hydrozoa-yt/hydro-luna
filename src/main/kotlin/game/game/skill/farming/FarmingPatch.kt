package game.skill.farming;

import io.luna.game.model.mob.varp.*

/**
 * Represents any farming patch.
 * @author hydrozoa
 */
abstract class FarmingPatch {

    private var isDead: Boolean = false
    private var isDiseased: Boolean = false
    protected var weeds: Int = 3

    fun needsRaking(): Boolean {
        return weeds > 0
    }

    /**
     * Does one cycle of raking the patch.
     * @return Returns {@code true} if completed raking, otherwise false
     */
    fun rake(): Boolean {
        weeds -= 1
        return weeds <= 0
    }

    abstract fun getVarp(): Varp
}
