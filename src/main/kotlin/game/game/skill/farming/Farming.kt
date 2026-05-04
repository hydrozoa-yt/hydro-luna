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
    val Player.herbPatches by Attr.map<HerbPatchLocation, HerbPatch> {
        HerbPatchLocation.values().associateWith { location -> HerbPatch(location) }
    }.persist("herb-patches")

    /**
     * An attribute representing an allotment patch.
     */
    val Player.allotmentPatch by Attr.obj<AllotmentPatch>{ AllotmentPatch(false) }.persist("allotment-patch")

    val HERB_PATCHES = intArrayOf(8150)
    val ALLOTMENT_PATCHES = intArrayOf(8551, 8550)

}