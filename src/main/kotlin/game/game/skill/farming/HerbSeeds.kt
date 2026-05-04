package game.skill.farming

import io.luna.game.model.item.*

/**
 * Data for herb seeds.
 *
 * @author hydrozoa
 */
enum class HerbSeeds(val seedId: Int,
                     val level: Int,
                     val crop: Item,
                     val plantXp: Double,
                     val harvestXp: Double) {

    GUAM(5291, 9, Item.byName("Guam leaf"), 11.5, 13.5),
    MARRENTILL(5292, 14, Item.byName("Marrentill"), 13.5, 15.0),
    TARROMIN(5293, 19, Item.byName("Tarromin"), 16.0, 18.0),
    HARRALANDER(5294, 26, Item.byName("Harralander"), 21.5, 24.0),
    RANARR(5295, 32, Item.byName("Ranarr weed"), 27.0, 30.5),
    TOADFLAX(5296, 38, Item.byName("Toadflax"), 34.0, 38.5),
    IRIT(5297, 44, Item.byName("Irit leaf"), 43.0, 48.5),
    AVANTOE(5298, 50, Item.byName("Avantoe"), 54.5, 61.5),
    KWUARM(5299, 56, Item.byName("Kwuarm"), 69.0, 77.5),
    SNAPDRAGON(5300, 62, Item.byName("Snapdragon"), 87.5, 98.5),
    CADENTINE(5301, 67, Item.byName("Cadantine"), 106.5, 120.0),
    LANTADYME(5302, 73, Item.byName("Lantadyme"), 134.5, 151.5),
    DWARF_WEED(5303, 79, Item.byName("Dwarf weed"), 170.5, 192.0),
    TORSTOL(5304, 85, Item.byName("Torstol"), 199.5, 224.5);

    companion object {
        private val seeds = values().associateBy(HerbSeeds::seedId)
        fun lookup(seedId: Int): HerbSeeds? = seeds[seedId]
    }
}