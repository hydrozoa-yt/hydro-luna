package api.bot.webwalk.plan

import api.bot.webwalk.data.WebWalkLoader
import api.bot.webwalk.model.EdgeType
import game.item.degradable.jewellery.TeleportJewellery
import game.skill.magic.Rune
import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for [TeleportAvailability] and for planning with the teleports that are shipped with the web.
 *
 * @author Hydrozoa
 */
class TeleportAvailabilityTest {

    private val held = mapOf(Rune.AIR.id to 5, Rune.LAW.id to 1)

    @Test
    fun runesAreNeededInTheAmountRequired() {
        val varrock = listOf(Rune.AIR to 3, Rune.FIRE to 1, Rune.LAW to 1)

        assertFalse(TeleportAvailability.hasRunes(varrock, { held[it] ?: 0 }, emptySet()))
        assertTrue(TeleportAvailability.hasRunes(listOf(Rune.AIR to 3, Rune.LAW to 1), { held[it] ?: 0 }, emptySet()))
        assertFalse(TeleportAvailability.hasRunes(listOf(Rune.AIR to 6), { held[it] ?: 0 }, emptySet()))
    }

    @Test
    fun aStaffStandsInForItsRunes() {
        val varrock = listOf(Rune.AIR to 3, Rune.FIRE to 1, Rune.LAW to 1)

        assertTrue(TeleportAvailability.hasRunes(varrock, { held[it] ?: 0 }, setOf(Rune.FIRE)))
        assertTrue(TeleportAvailability.hasRunes(varrock, { if (it == Rune.LAW.id) 1 else 0 }, setOf(Rune.AIR, Rune.FIRE)))
        assertFalse(TeleportAvailability.hasRunes(varrock, { 0 }, setOf(Rune.AIR, Rune.FIRE)))
    }

    @Test
    fun repeatedRunesAreAddedUp() {
        assertFalse(TeleportAvailability.hasRunes(listOf(Rune.AIR to 3, Rune.AIR to 3), { held[it] ?: 0 }, emptySet()))
        assertTrue(TeleportAvailability.hasRunes(listOf(Rune.AIR to 2, Rune.AIR to 3), { held[it] ?: 0 }, emptySet()))
    }

    @Test
    fun jewelleryThatDoesntCrumbleIsUselessOnceItHasNoCharge() {
        val glory = TeleportAvailability.chargedIds(TeleportJewellery.AMULET_OF_GLORY)
        assertEquals(listOf(1712, 1710, 1708, 1706), glory)
        assertFalse(1704 in glory)

        // A games necklace crumbles on its last charge, so every piece of it has one.
        assertEquals(TeleportJewellery.GAMES_NECKLACE.items, TeleportAvailability.chargedIds(TeleportJewellery.GAMES_NECKLACE))
    }

    @Test
    fun theShippedTeleportsAreAllLinkedToTheWalkableWorld() {
        val graph = WebWalkLoader.load(Path.of("data/game/bots/webwalk"))

        assertTrue(graph.teleports.size >= 25, "Only ${graph.teleports.size} teleports.")
        assertTrue(graph.teleports.any { it.id == "home" })
        assertTrue(graph.teleports.any { it.id == "spell_camelot" })
        // The Ape Atoll spell lands in a pocket that nothing else in the web leads to yet (it is behind something that isn't a
        // door), so it is the one known teleport that can't be walked on from.
        val knownGaps = setOf("spell_ape_atoll")
        val stranded = graph.teleports.filter { teleport ->
            graph.edgesFrom(teleport.nodeId).none { it.type == EdgeType.WALK }
        }.map { it.id }.toSet()
        assertEquals(knownGaps, stranded, "Teleports that can't be walked on from.")
    }

    @Test
    fun aBotThatCanCastUsesTheSpellForALongTrip() {
        val graph = WebWalkLoader.load(Path.of("data/game/bots/webwalk"))
        val planner = WebWalkPlanner(graph, StraightLineWalkEstimator)
        val lumbridge = graph.node("zone_lumbridge")!!.position
        val camelot = graph.teleports.first { it.id == "spell_camelot" }.destination
        val destination = Position(camelot.x, camelot.y + 3, camelot.z)

        val walking = planner.plan(lumbridge, destination, CapabilitySnapshot())
        val casting = planner.plan(lumbridge, destination, CapabilitySnapshot(usableTeleports = setOf("spell_camelot")))!!

        val teleport = casting.legs.single { it.type == EdgeType.TELEPORT }
        assertEquals("spell_camelot", teleport.teleport!!.id)
        assertEquals(1, casting.legs.count { it.type == EdgeType.TELEPORT })
        if (walking != null) {
            assertTrue(casting.cost < walking.cost, "Casting ${casting.cost} should beat walking ${walking.cost}.")
        }
    }
}
