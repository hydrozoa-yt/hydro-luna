package api.bot.webwalk.plan

import api.bot.webwalk.data.WebWalkLoader
import api.bot.webwalk.model.EdgeType
import api.bot.webwalk.model.WebWalkGraph
import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Tests for [WebWalkPlanner], on small graphs that are made by hand.
 *
 * @author Hydrozoa
 */
class WebWalkPlannerTest {

    /**
     * Makes a graph from the contents of the data files.
     */
    private fun graph(hubs: String = """{ "hubs": [] }""", obstacles: String = "[]", climbs: String = "[]",
                      teleports: String = "[]", ships: String = "[]", rings: String = "[]"): WebWalkGraph =
        WebWalkLoader.fromSources(mapOf(WebWalkLoader.HUBS to hubs, WebWalkLoader.OBSTACLES to obstacles,
                                        WebWalkLoader.CLIMBS to climbs, WebWalkLoader.TELEPORTS to teleports,
                                        WebWalkLoader.SHIPS to ships, WebWalkLoader.FAIRY_RINGS to rings))

    private fun planner(graph: WebWalkGraph, estimator: WalkEstimator = StraightLineWalkEstimator,
                        costs: PlannerCosts = PlannerCosts()) = WebWalkPlanner(graph, estimator, costs)

    private fun pos(x: Int, y: Int, z: Int = 0) = Position(x, y, z)

    /**
     * Three hubs in a line, 100 tiles apart, which is beyond where a position is linked to a node, with edges of 20 ticks.
     */
    private val line = """
        { "hubs": [ { "id": "a", "pos": [100, 100] }, { "id": "b", "pos": [200, 100] }, { "id": "c", "pos": [300, 100] } ],
          "edges": [ { "from": "a", "to": "b", "cost": 20 }, { "from": "b", "to": "c", "cost": 20 } ] }
    """

    @Test
    fun aTripFromAPositionToItselfHasNoLegs() {
        val plan = planner(graph(line)).plan(pos(100, 100), pos(100, 100), CapabilitySnapshot())

        assertNotNull(plan)
        assertTrue(plan!!.legs.isEmpty())
        assertEquals(0.0, plan.cost)
    }

    @Test
    fun aShortTripIsWalkedDirectly() {
        val plan = planner(graph(line)).plan(pos(100, 110), pos(120, 110), CapabilitySnapshot())!!

        assertEquals(1, plan.legs.size)
        assertEquals(EdgeType.WALK, plan.legs[0].type)
        assertEquals(10.0, plan.cost)
        assertEquals(pos(100, 110), plan.start)
        assertEquals(pos(120, 110), plan.destination)
    }

    @Test
    fun aLongTripFollowsTheEdgesOfTheGraph() {
        val plan = planner(graph(line)).plan(pos(100, 102), pos(300, 102), CapabilitySnapshot())!!

        // Walks to a, then on to b and c, then to the destination.
        assertEquals(listOf(pos(100, 100), pos(200, 100), pos(300, 100), pos(300, 102)), plan.legs.map { it.to })
        assertTrue(plan.legs.all { it.type == EdgeType.WALK })
        assertEquals(plan.legs.sumOf { it.cost }, plan.cost)
        for ((first, second) in plan.legs.zipWithNext()) {
            assertEquals(first.to, second.from)
        }
    }

    @Test
    fun aDestinationThatCantBeLinkedCantBeReached() {
        assertNull(planner(graph(line)).plan(pos(100, 100), pos(900, 900), CapabilitySnapshot()))
        // And neither can another plane, since nothing links the planes.
        assertNull(planner(graph(line)).plan(pos(100, 100), pos(100, 100, 1), CapabilitySnapshot()))
    }

    @Test
    fun theCheapestRouteIsTaken() {
        val hubs = """
            { "hubs": [ { "id": "a", "pos": [100, 100] }, { "id": "mid", "pos": [140, 100] },
                        { "id": "far", "pos": [140, 160] }, { "id": "z", "pos": [180, 100] } ],
              "edges": [ { "from": "a", "to": "mid", "cost": 20 }, { "from": "mid", "to": "z", "cost": 20 },
                         { "from": "a", "to": "far", "cost": 20 }, { "from": "far", "to": "z", "cost": 60 } ] }
        """
        val plan = planner(graph(hubs)).plan(pos(100, 100), pos(180, 100), CapabilitySnapshot())!!

        assertTrue(plan.legs.any { it.to == pos(140, 100) })
        assertFalse(plan.legs.any { it.to == pos(140, 160) })
    }

    /**
     * A wall at x = 140, with a door in it and a long way around.
     */
    private val wall = """
        { "hubs": [ { "id": "west", "pos": [100, 100] }, { "id": "east", "pos": [180, 100] },
                    { "id": "north", "pos": [140, 180] } ],
          "edges": [ { "from": "west", "to": "north", "cost": 60 }, { "from": "north", "to": "east", "cost": 60 } ] }
    """
    private val door = """
        [ { "type": "DOOR", "object": 1512, "pos": [139, 100, 0], "from": [139, 100, 0], "to": [140, 100, 0],
            "requirements": { "items": [ { "id": 1523 } ] } } ]
    """
    private val doorWithLink = """
        { "hubs": [ { "id": "west", "pos": [100, 100] }, { "id": "east", "pos": [180, 100] },
                    { "id": "north", "pos": [140, 180] } ],
          "edges": [ { "from": "west", "to": "north", "cost": 60 }, { "from": "north", "to": "east", "cost": 60 },
                     { "from": "west", "to": "o:139,100,0", "cost": 20 }, { "from": "o:140,100,0", "to": "east", "cost": 20 } ] }
    """

    @Test
    fun anEdgeIsOnlyUsedByABotThatMeetsItsRequirements() {
        val graph = graph(doorWithLink, door)
        // A wall stands at x = 140, so nothing on one side of it can be walked to from the other.
        val planner = planner(graph, { from, to ->
            if ((from.x < 140) != (to.x < 140)) null else StraightLineWalkEstimator.ticks(from, to)
        })

        val without = planner.plan(pos(100, 100), pos(180, 100), CapabilitySnapshot())!!
        assertFalse(without.legs.any { it.type == EdgeType.DOOR }, "A bot without the key used the door.")
        assertTrue(without.cost > 100)

        val with = planner.plan(pos(100, 100), pos(180, 100), CapabilitySnapshot(items = mapOf(1523 to 1)))!!
        val leg = with.legs.single { it.type == EdgeType.DOOR }
        assertEquals(1512, leg.action!!.objectId)
        assertTrue(with.cost < without.cost)
    }

    @Test
    fun skillLevelsAreRequired() {
        val climbs = """
            [ { "type": "LADDER", "object": 2113, "pos": [100, 101, 0], "from": [100, 100, 0], "to": [100, 100, 1],
                "requirements": { "skills": { "mining": 60 } } } ]
        """
        val hubs = """{ "hubs": [ { "id": "up", "pos": [100, 110, 1] } ], "edges": [ { "from": "up", "to": "o:100,100,1", "cost": 5 } ] }"""
        val planner = planner(graph(hubs, climbs = climbs))

        assertNull(planner.plan(pos(100, 99), pos(100, 110, 1), CapabilitySnapshot(skillLevels = mapOf(14 to 59))))
        val plan = planner.plan(pos(100, 99), pos(100, 110, 1), CapabilitySnapshot(skillLevels = mapOf(14 to 60)))!!
        assertEquals(listOf(EdgeType.WALK, EdgeType.LADDER, EdgeType.WALK), plan.legs.map { it.type })
    }

    @Test
    fun shipsNeedCoins() {
        val ships = """
            { "ports": [ { "id": "dock", "pos": [100, 100] }, { "id": "island", "pos": [300, 100] } ],
              "routes": [ { "from": "dock", "to": "island", "cost": 30, "requirements": { "coins": 30 } } ] }
        """
        val planner = planner(graph(ships = ships))

        assertNull(planner.plan(pos(100, 101), pos(300, 101), CapabilitySnapshot(items = mapOf(995 to 29))))
        val plan = planner.plan(pos(100, 101), pos(300, 101), CapabilitySnapshot(items = mapOf(995 to 30)))!!
        assertEquals(EdgeType.SHIP, plan.legs.single { it.type == EdgeType.SHIP }.type)
        // The fare is a small penalty on top of the time: 30 ticks, and 30 coins at 0.02.
        assertEquals(30.6, plan.legs.single { it.type == EdgeType.SHIP }.cost, 0.001)
    }

    @Test
    fun fairyRingsNeedTheUnlock() {
        val rings = """
            [ { "id": "one", "code": "AIP", "pos": [100, 100] }, { "id": "two", "code": "BJQ", "pos": [500, 500] } ]
        """
        val planner = planner(graph(rings = rings))

        assertNull(planner.plan(pos(100, 101), pos(500, 501), CapabilitySnapshot()))
        val plan = planner.plan(pos(100, 101), pos(500, 501), CapabilitySnapshot(flags = setOf("fairy_rings")))!!
        val leg = plan.legs.single { it.type == EdgeType.FAIRY_RING }
        assertEquals("BJQ", leg.fairyRing!!.code)
        assertEquals(pos(500, 500), leg.to)
    }

    private val teleports = """
        [ { "id": "far_spell", "kind": "SPELL", "key": "FALADOR", "dest": [300, 100], "cost": 5 },
          { "id": "near_ring", "kind": "JEWELLERY", "key": "GLORY", "option": 3, "dest": [102, 100], "cost": 4,
            "requirements": { "maxWilderness": 20 } } ]
    """

    @Test
    fun teleportsAreOnlyUsedWhenTheBotCanUseThemAndTheyAreCheaper() {
        val planner = planner(graph(line, teleports = teleports))
        val trip = { snapshot: CapabilitySnapshot -> planner.plan(pos(100, 102), pos(300, 102), snapshot)!! }

        assertTrue(trip(CapabilitySnapshot()).legs.none { it.type == EdgeType.TELEPORT })

        val teleported = trip(CapabilitySnapshot(usableTeleports = setOf("far_spell")))
        val leg = teleported.legs.single { it.type == EdgeType.TELEPORT }
        assertEquals("far_spell", leg.teleport!!.id)
        // The cast is 5 ticks and 6 for the runes, then 1 tick to the destination, instead of 40.
        assertEquals(12.0, teleported.cost, 0.001)
    }

    @Test
    fun aTeleportIsNotWorthItForAShortTrip() {
        val planner = planner(graph(line, teleports = teleports))
        val plan = planner.plan(pos(100, 102), pos(110, 102), CapabilitySnapshot(usableTeleports = setOf("far_spell")))!!

        assertTrue(plan.legs.none { it.type == EdgeType.TELEPORT })
    }

    @Test
    fun teleportsHaveTheirOwnRequirements() {
        val spells = """
            [ { "id": "locked", "kind": "SPELL", "key": "X", "dest": [300, 100], "cost": 1,
                "requirements": { "skills": { "magic": 50 } } } ]
        """
        val planner = planner(graph(line, teleports = spells))
        val usable = setOf("locked")

        val low = planner.plan(pos(100, 102), pos(300, 102), CapabilitySnapshot(usableTeleports = usable))!!
        assertTrue(low.legs.none { it.type == EdgeType.TELEPORT })
        val high = planner.plan(pos(100, 102), pos(300, 102),
                                CapabilitySnapshot(skillLevels = mapOf(6 to 50), usableTeleports = usable))!!
        assertTrue(high.legs.any { it.type == EdgeType.TELEPORT })
    }

    @Test
    fun aBotInTheDeepWildernessWalksOutBeforeItTeleports() {
        // Hubs from the safe edge of the wilderness (y 3519) deep into it. The teleport can't be used above level 20.
        val hubs = """
            { "hubs": [ { "id": "edge", "pos": [3000, 3500] }, { "id": "mid", "pos": [3000, 3560] },
                        { "id": "deep", "pos": [3000, 3700] } ],
              "edges": [ { "from": "deep", "to": "mid", "cost": 40 }, { "from": "mid", "to": "edge", "cost": 20 } ] }
        """
        val spells = """
            [ { "id": "home", "kind": "SPELL", "key": "VARROCK", "dest": [3200, 3200], "cost": 5,
                "requirements": { "maxWilderness": 20 } } ]
        """
        val graph = graph(hubs, teleports = spells)
        val plan = planner(graph).plan(pos(3000, 3701), pos(3200, 3201),
                                       CapabilitySnapshot(usableTeleports = setOf("home")))!!

        val teleportAt = plan.legs.indexOfFirst { it.type == EdgeType.TELEPORT }
        assertTrue(teleportAt > 0, "The bot teleported from where it can't.")
        assertTrue(plan.legs[teleportAt].from.y < 3520 + 20 * 8, "Teleported from level above 20: ${plan.legs[teleportAt].from}")
    }

    /**
     * Two ways from the west to the east: a short one through the wilderness, and a longer one around it.
     */
    private val wildernessHubs = """
        { "hubs": [ { "id": "west", "pos": [3000, 3400] }, { "id": "east", "pos": [3100, 3400] },
                    { "id": "wild", "pos": [3050, 3600] }, { "id": "around", "pos": [3050, 3300] } ],
          "edges": [ { "from": "west", "to": "wild", "cost": 25 }, { "from": "wild", "to": "east", "cost": 25 },
                     { "from": "west", "to": "around", "cost": 70 }, { "from": "around", "to": "east", "cost": 70 } ] }
    """

    @Test
    fun theWildernessIsAvoidedWhenThereIsAnotherWay() {
        val plan = planner(graph(wildernessHubs)).plan(pos(3000, 3401), pos(3100, 3401), CapabilitySnapshot())!!

        assertFalse(plan.legs.any { it.to == pos(3050, 3600) }, "Went through the wilderness: $plan")
        assertTrue(plan.legs.any { it.to == pos(3050, 3300) })
    }

    @Test
    fun theWildernessIsUsedWhenThereIsNoOtherWay() {
        val hubs = """
            { "hubs": [ { "id": "west", "pos": [3000, 3400] }, { "id": "east", "pos": [3100, 3400] },
                        { "id": "wild", "pos": [3050, 3600] } ],
              "edges": [ { "from": "west", "to": "wild", "cost": 25 }, { "from": "wild", "to": "east", "cost": 25 } ] }
        """
        val plan = planner(graph(hubs)).plan(pos(3000, 3401), pos(3100, 3401), CapabilitySnapshot())

        assertNotNull(plan)
        assertTrue(plan!!.legs.any { it.to == pos(3050, 3600) })
        assertTrue(plan.cost > 250, "The wilderness should be expensive: ${plan.cost}")
    }

    @Test
    fun theStartAndDestinationAreOnlyLinkedToNodesTheyCanWalkTo() {
        // The wall is at x = 120: nothing west of it can walk to anything east of it.
        val estimator = WalkEstimator { from, to ->
            if ((from.x < 120) != (to.x < 120)) null else StraightLineWalkEstimator.ticks(from, to)
        }
        val hubs = """
            { "hubs": [ { "id": "inside", "pos": [100, 100] }, { "id": "outside_near", "pos": [125, 100] },
                        { "id": "gate", "pos": [118, 100] }, { "id": "outside_far", "pos": [160, 100] } ],
              "edges": [ { "from": "inside", "to": "gate", "cost": 9 }, { "from": "gate", "to": "outside_near", "cost": 5 },
                         { "from": "outside_near", "to": "outside_far", "cost": 18 } ] }
        """
        val plan = planner(graph(hubs), estimator).plan(pos(121, 100), pos(100, 101), CapabilitySnapshot())

        // The nearest node to the start is outside_near, so it is linked, but the direct walk is not possible.
        assertNotNull(plan)
        assertTrue(plan!!.legs.none { it.from == pos(121, 100) && it.to == pos(100, 101) })
        assertEquals(pos(125, 100), plan.legs.first().to)
    }

    @Test
    fun intelligentBotsAlwaysTakeTheBestTrip() {
        val graph = graph(twoRoutes)
        val planner = planner(graph)
        val plans = (0 until 20).map { planner.plan(pos(100, 101), pos(300, 101), CapabilitySnapshot(), 1.0, Random(it.toLong())) }

        assertEquals(1, plans.map { plan -> plan!!.legs.map { it.to } }.toSet().size)
    }

    /**
     * Two routes whose costs are nearly the same: 40 + 40 ticks against 41 + 41.
     */
    private val twoRoutes = """
        { "hubs": [ { "id": "start", "pos": [100, 100] }, { "id": "north", "pos": [200, 160] },
                    { "id": "south", "pos": [200, 40] }, { "id": "finish", "pos": [300, 100] } ],
          "edges": [ { "from": "start", "to": "north", "cost": 40 }, { "from": "north", "to": "finish", "cost": 40 },
                     { "from": "start", "to": "south", "cost": 41 }, { "from": "south", "to": "finish", "cost": 41 } ] }
    """

    @Test
    fun lessIntelligentBotsVaryTheirRoutesWithinATolerance() {
        val planner = planner(graph(twoRoutes))
        val plans = (0 until 60).map { planner.plan(pos(100, 101), pos(300, 101), CapabilitySnapshot(), 0.0, Random(it.toLong()))!! }

        val routes = plans.map { plan -> plan.legs.any { it.to == pos(200, 160) } }.toSet()
        assertEquals(setOf(true, false), routes, "Every bot took the same route.")
        val best = plans.minOf { it.cost }
        assertTrue(plans.all { it.cost <= best + 8.0 + 1.0 }, "A bot took a much worse route.")
    }

    @Test
    fun routesTooMuchWorseAreNeverTaken() {
        val hubs = """
            { "hubs": [ { "id": "start", "pos": [100, 100] }, { "id": "north", "pos": [200, 160] },
                        { "id": "south", "pos": [200, 40] }, { "id": "finish", "pos": [300, 100] } ],
              "edges": [ { "from": "start", "to": "north", "cost": 40 }, { "from": "north", "to": "finish", "cost": 40 },
                         { "from": "start", "to": "south", "cost": 80 }, { "from": "south", "to": "finish", "cost": 80 } ] }
        """
        val planner = planner(graph(hubs))
        for (seed in 0 until 40) {
            val plan = planner.plan(pos(100, 101), pos(300, 101), CapabilitySnapshot(), 0.0, Random(seed.toLong()))!!
            assertTrue(plan.legs.any { it.to == pos(200, 160) })
        }
    }

    @Test
    fun theSameSeedGivesTheSameTrip() {
        val planner = planner(graph(twoRoutes))
        val first = planner.plan(pos(100, 101), pos(300, 101), CapabilitySnapshot(), 0.3, Random(5))!!
        val second = planner.plan(pos(100, 101), pos(300, 101), CapabilitySnapshot(), 0.3, Random(5))!!

        assertEquals(first.legs, second.legs)
    }

    @Test
    fun theSnapshotMeetsRequirements() {
        val snapshot = CapabilitySnapshot(skillLevels = mapOf(14 to 60), items = mapOf(995 to 100, 1523 to 2),
                                          flags = setOf("fairy_rings"), wildernessLevel = 10)
        val requirements = api.bot.webwalk.model.Requirements(
            skills = mapOf(14 to 60), items = listOf(api.bot.webwalk.model.ItemRequirement(1523, 2)), coins = 100,
            flags = setOf("fairy_rings"), maxWilderness = 10)

        assertTrue(snapshot.meets(requirements))
        assertFalse(snapshot.meets(requirements.copy(skills = mapOf(14 to 61))))
        assertFalse(snapshot.meets(requirements.copy(items = listOf(api.bot.webwalk.model.ItemRequirement(1523, 3)))))
        assertFalse(snapshot.meets(requirements.copy(coins = 101)))
        assertFalse(snapshot.meets(requirements.copy(flags = setOf("other"))))
        assertFalse(snapshot.meets(requirements.copy(maxWilderness = 9)))
        assertTrue(snapshot.meets(requirements.copy(maxWilderness = 9), wildernessAt = 0))
        assertEquals(100, snapshot.coins)
    }

    @Test
    fun tripsAreFoundQuicklyOnTheShippedGraph() {
        val graph = WebWalkLoader.load(java.nio.file.Path.of("data/game/bots/webwalk"))
        val planner = planner(graph)
        val lumbridge = graph.node("zone_lumbridge")!!.position
        val varrock = graph.node("zone_varrock")!!.position
        val falador = graph.node("zone_falador")!!.position
        planner.plan(lumbridge, varrock, CapabilitySnapshot())

        val started = System.nanoTime()
        val trips = listOf(lumbridge to varrock, varrock to falador, falador to lumbridge)
        for ((from, to) in trips) {
            val plan = planner.plan(from, to, CapabilitySnapshot())
            assertNotNull(plan, "No trip from $from to $to.")
            assertEquals(from, plan!!.start)
            assertEquals(to, plan.destination)
            for ((first, second) in plan.legs.zipWithNext()) {
                assertEquals(first.to, second.from)
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 2000, "Three trips took $millis ms.")
    }

    @Test
    fun tripsThatCostTheSameTakeTheFewestLegs() {
        // The start is on a hub, so walking to it is free, and so is not bothering: both ways cost the same.
        val teleports = """
            [ { "id": "far_spell", "kind": "SPELL", "key": "FALADOR", "dest": [300, 100], "cost": 5 } ]
        """
        val planner = planner(graph(line, teleports = teleports))
        val plan = planner.plan(pos(100, 100), pos(300, 102), CapabilitySnapshot(usableTeleports = setOf("far_spell")))!!

        assertEquals(EdgeType.TELEPORT, plan.legs.first().type, "Walked before teleporting: $plan")
    }
}
