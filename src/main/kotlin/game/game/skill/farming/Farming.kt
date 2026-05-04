package game.skill.farming

import api.attr.*
import io.luna.game.model.mob.*

/**
 *
 * @author lare96
 */
object Farming {

    /**
     * An attribute representing an herb patch.
     */
    val Player.herbPatch by Attr.obj<HerbPatch>{ HerbPatch() }.persist("herb-patch")

    /**
     * An attribute representing an allotment patch.
     */
    val Player.allotmentPatch by Attr.obj<AllotmentPatch>{ AllotmentPatch(false) }.persist("allotment-patch")



    val HERB_PATCHES = intArrayOf(8150)
    val ALLOTMENT_PATCHES = intArrayOf(8551, 8550)

}