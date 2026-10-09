package api.bot.webwalk.data

import api.bot.webwalk.data.WebWalkLoader.FAIRY_RINGS
import api.bot.webwalk.data.WebWalkLoader.WALK_GRAPH
import api.bot.webwalk.data.WebWalkLoader.WAYPOINTS_MANUAL
import api.bot.webwalk.data.WebWalkLoader.OBSTACLES
import api.bot.webwalk.data.WebWalkLoader.OBSTACLE_OVERRIDES
import api.bot.webwalk.data.WebWalkLoader.SHIPS
import api.bot.webwalk.data.WebWalkLoader.TELEPORTS
import api.bot.webwalk.generate.ScatterWaypoints
import api.bot.webwalk.model.EdgeAction
import api.bot.webwalk.model.EdgeType
import api.bot.webwalk.model.ItemRequirement
import api.bot.webwalk.model.NodeKind
import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for [WebWalkLoader].
 *
 * @author Hydrozoa
 */
class WebWalkLoaderTest {

    private fun load(vararg sources: Pair<String, String>) = WebWalkLoader.fromSources(mapOf(*sources))

    private fun problems(vararg sources: Pair<String, String>): List<String> {
        val exception = assertThrows(WebWalkDataException::class.java) { load(*sources) }
        return exception.problems
    }

    private val waypoints = """
        { "waypoints": [
            { "id": "lumbridge", "pos": [3222, 3218], "tags": ["town"] },
            { "id": "draynor", "pos": [3093, 3244, 0] }
          ],
          "edges": [ { "from": "lumbridge", "to": "draynor", "cost": 120 } ] }
    """

    private val door = """
        [ { "type": "DOOR", "object": 1512, "pos": [3207, 3214, 0], "from": [3207, 3214, 0], "to": [3208, 3214, 0] } ]
    """

    @Test
    fun noFilesIsAnEmptyGraph() {
        val graph = load()
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
        assertTrue(graph.teleports.isEmpty())
        assertTrue(graph.fairyRings.isEmpty())
    }

    @Test
    fun emptyArraysAreAccepted() {
        val graph = load(SHIPS to "[]", FAIRY_RINGS to "[]", WALK_GRAPH to "[]", OBSTACLES to "[]", TELEPORTS to "  ")
        assertTrue(graph.nodes.isEmpty())
    }

    @Test
    fun waypointsAndTwoWayWalkEdges() {
        val graph = load(WALK_GRAPH to waypoints)
        assertEquals(setOf("lumbridge", "draynor"), graph.nodes.keys)
        assertEquals(Position(3222, 3218, 0), graph.node("lumbridge")!!.position)
        assertEquals(setOf("town"), graph.node("lumbridge")!!.tags)
        assertEquals(NodeKind.WAYPOINT, graph.node("draynor")!!.kind)

        assertEquals(2, graph.edges.size)
        val forward = graph.edgesFrom("lumbridge").single()
        assertEquals("draynor", forward.to)
        assertEquals(EdgeType.WALK, forward.type)
        assertEquals(120, forward.cost)
        assertEquals("lumbridge", graph.edgesFrom("draynor").single().to)
    }

    @Test
    fun generatedEdgesCanLinkManualWaypoints() {
        val manual = """{ "waypoints": [ { "id": "wizards_tower", "pos": [3109, 3167] } ] }"""
        val graphText = """
            { "waypoints": [ { "id": "draynor", "pos": [3093, 3244, 0] } ],
              "edges": [ { "from": "wizards_tower", "to": "draynor", "bidirectional": false } ] }
        """
        val graph = load(WALK_GRAPH to graphText, WAYPOINTS_MANUAL to manual)
        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.edgesFrom("wizards_tower").size)
        assertNull(graph.edgesFrom("wizards_tower").single().cost)
        assertEquals(0, graph.edgesFrom("draynor").size)
    }

    @Test
    fun manualWaypointsCannotHaveEdges() {
        val manual = """{ "waypoints": [ { "id": "a", "pos": [1, 1] } ], "edges": [] }"""
        assertEquals(listOf("waypoints_manual.json.edges: unknown field"), problems(WAYPOINTS_MANUAL to manual))
    }

    @Test
    fun doorsMakeNodesAndTwoWayEdges() {
        val graph = load(OBSTACLES to door)
        assertEquals(setOf("o:3207,3214,0", "o:3208,3214,0"), graph.nodes.keys)
        assertTrue(graph.nodes.values.all { it.kind == NodeKind.OBSTACLE })
        val edge = graph.edgesFrom("o:3207,3214,0").single()
        assertEquals(EdgeType.DOOR, edge.type)
        assertEquals(EdgeAction(1512, Position(3207, 3214, 0), null), edge.action)
        assertEquals("o:3207,3214,0", graph.edgesFrom("o:3208,3214,0").single().to)
    }

    @Test
    fun laddersAreOneWayAndShareNodes() {
        val ladders = """
            [ { "type": "LADDER", "object": 1746, "pos": [3204, 3207, 0], "from": [3204, 3206, 0], "to": [3204, 3206, 1], "option": "Climb-up" },
              { "type": "LADDER", "object": 1747, "pos": [3204, 3207, 1], "from": [3204, 3206, 1], "to": [3204, 3206, 0] } ]
        """
        val graph = load(OBSTACLES to ladders)
        assertEquals(2, graph.nodes.size)
        assertEquals(2, graph.edges.size)
        val up = graph.edgesFrom("o:3204,3206,0").single()
        assertEquals("o:3204,3206,1", up.to)
        assertEquals("Climb-up", up.action!!.option)
        assertEquals("o:3204,3206,0", graph.edgesFrom("o:3204,3206,1").single().to)
    }

    @Test
    fun overridesAddRequirementsAndDisableObstacles() {
        val doors = """
            [ { "type": "DOOR", "object": 1512, "pos": [3207, 3214, 0], "from": [3207, 3214, 0], "to": [3208, 3214, 0] },
              { "type": "GATE", "object": 1551, "pos": [3100, 3200, 0], "from": [3100, 3200, 0], "to": [3101, 3200, 0] } ]
        """
        val overrides = """
            [ { "pos": [3207, 3214, 0], "requirements": { "items": [ { "id": 1523 } ], "skills": { "thieving": 5 } } },
              { "pos": [3100, 3200, 0], "object": 1551, "disabled": true } ]
        """
        val graph = load(OBSTACLES to doors, OBSTACLE_OVERRIDES to overrides)
        val requirements = graph.edgesFrom("o:3207,3214,0").single().requirements
        assertEquals(listOf(ItemRequirement(1523, 1)), requirements.items)
        assertEquals(mapOf(17 to 5), requirements.skills)
        assertNull(graph.node("o:3100,3200,0"))
    }

    @Test
    fun teleportsMakeDestinationNodesButNoEdges() {
        val teleports = """
            [ { "id": "lumbridge_spell", "kind": "SPELL", "key": "LUMBRIDGE", "dest": [3222, 3218], "cost": 5 },
              { "id": "glory_draynor", "kind": "JEWELLERY", "key": "AMULET_OF_GLORY", "option": 3, "dest": [3093, 3244],
                "cost": 4, "requirements": { "maxWilderness": 30 } },
              { "id": "home", "kind": "HOME", "dest": [3213, 3428], "cost": 20 } ]
        """
        val graph = load(TELEPORTS to teleports)
        assertEquals(3, graph.teleports.size)
        assertTrue(graph.edges.isEmpty())
        assertEquals(NodeKind.TELEPORT_DEST, graph.node("t:glory_draynor")!!.kind)
        val glory = graph.teleports.first { it.id == "glory_draynor" }
        assertEquals(3, glory.option)
        assertEquals(30, glory.requirements.maxWilderness)
        assertNull(graph.teleports.first { it.id == "home" }.key)
    }

    @Test
    fun shipsAndFairyRings() {
        val ships = """
            { "ports": [ { "id": "port_sarim", "pos": [3029, 3217] }, { "id": "karamja", "pos": [2956, 3146] } ],
              "routes": [ { "from": "port_sarim", "to": "karamja", "cost": 30, "requirements": { "coins": 30 } } ] }
        """
        val rings = """
            [ { "id": "alkharid", "code": "AKQ", "pos": [3259, 3167] },
              { "id": "draynor", "code": "BJR", "pos": [3105, 3156], "requirements": { "flags": ["fairy_rings"] } } ]
        """
        val graph = load(SHIPS to ships, FAIRY_RINGS to rings)
        assertEquals(NodeKind.SHIP_PORT, graph.node("port_sarim")!!.kind)
        val route = graph.edgesFrom("port_sarim").single()
        assertEquals(EdgeType.SHIP, route.type)
        assertEquals(30, route.requirements.coins)
        assertEquals(1, graph.edgesFrom("karamja").size)

        assertEquals(setOf("AKQ", "BJR"), graph.fairyRings.map { it.code }.toSet())
        assertEquals(NodeKind.FAIRY_RING, graph.node("fr:alkharid")!!.kind)
        assertEquals(setOf("fairy_rings"), graph.fairyRings.first { it.id == "draynor" }.requirements.flags)
    }

    @Test
    fun wildernessLevelIsComputedForNodes() {
        val graph = load(WALK_GRAPH to """
            { "waypoints": [ { "id": "edgeville", "pos": [3087, 3496] },
                        { "id": "mage_bank", "pos": [3090, 3957] },
                        { "id": "level_one", "pos": [3090, 3523] } ] }
        """)
        assertEquals(0, graph.node("edgeville")!!.wildernessLevel)
        assertEquals(1, graph.node("level_one")!!.wildernessLevel)
        assertEquals(55, graph.node("mage_bank")!!.wildernessLevel)
    }

    @Test
    fun edgesToUnknownNodesAreReported() {
        val found = problems(WALK_GRAPH to """
            { "waypoints": [ { "id": "a", "pos": [1, 1] } ], "edges": [ { "from": "a", "to": "b" } ] }
        """)
        assertEquals(listOf("walk_graph.jsonc.edges[0]: 'to' refers to unknown node 'b'"), found)
    }

    @Test
    fun duplicateIdsAcrossWaypointFilesAreReported() {
        val manual = """{ "waypoints": [ { "id": "lumbridge", "pos": [1, 1] } ] }"""
        val found = problems(WALK_GRAPH to waypoints, WAYPOINTS_MANUAL to manual)
        assertEquals(listOf("waypoints_manual.json.waypoints[0]: duplicate node id 'lumbridge'"), found)
    }

    @Test
    fun unknownFieldsAndBadTypesAreReported() {
        val found = problems(WALK_GRAPH to """
            { "waypoints": [ { "id": "a", "pos": [1, 1], "tagz": [] },
                        { "id": "Bad Id", "pos": [1, 1] },
                        { "id": "c", "pos": [1, 1, 9] },
                        { "id": "d", "pos": "nope" } ] }
        """)
        assertEquals(4, found.size)
        assertTrue(found.any { it.startsWith("walk_graph.jsonc.waypoints[0].tagz: unknown field") })
        assertTrue(found.any { it.startsWith("walk_graph.jsonc.waypoints[1].id: must be lower case") })
        assertTrue(found.any { it.startsWith("walk_graph.jsonc.waypoints[2].pos: must have x and y") })
        assertTrue(found.any { it.startsWith("walk_graph.jsonc.waypoints[3].pos: must be an array") })
    }

    @Test
    fun unknownSkillsAndBadAmountsAreReported() {
        val doors = """
            [ { "type": "DOOR", "object": 1512, "pos": [3207, 3214, 0], "from": [3207, 3214, 0], "to": [3208, 3214, 0],
                "requirements": { "skills": { "cooking": 100, "basketweaving": 1 }, "items": [ { "id": 5, "amount": 0 } ] } } ]
        """
        val found = problems(OBSTACLES to doors)
        assertEquals(3, found.size)
        assertTrue(found.any { it.endsWith("skills.cooking: must be between 1 and 99, but was 100") })
        assertTrue(found.any { it.endsWith("skills.basketweaving: unknown skill") })
        assertTrue(found.any { it.endsWith("items[0].amount: must be between 1 and 2147483647, but was 0") })
    }

    @Test
    fun doorsMustBeNextToTheirTile() {
        val far = """
            [ { "type": "DOOR", "object": 1512, "pos": [1, 1, 0], "from": [1, 1, 0], "to": [3, 1, 0] },
              { "type": "GATE", "object": 1551, "pos": [1, 1, 0], "from": [1, 1, 0], "to": [1, 1, 1] },
              { "type": "WALK", "object": 1, "pos": [1, 1, 0], "from": [1, 1, 0], "to": [2, 1, 0] } ]
        """
        val found = problems(OBSTACLES to far)
        assertEquals(3, found.size)
        assertTrue(found[0].contains("obstacles.jsonc[0].to: must be the tile next to 'from'"))
        assertTrue(found[1].contains("obstacles.jsonc[1].to: must be the tile next to 'from'"))
        assertTrue(found[2].contains("obstacles.jsonc[2].type: must be one of"))
    }

    @Test
    fun duplicateObstaclesAreReported() {
        val found = problems(OBSTACLES to "[" + door.trim().removeSurrounding("[", "]") + "," +
                door.trim().removeSurrounding("[", "]") + "]")
        assertEquals(listOf("obstacles.jsonc[1]: duplicate of an earlier obstacle"), found)
    }

    @Test
    fun overridesThatMatchNothingAreReported() {
        val overrides = """
            [ { "pos": [9, 9, 0], "disabled": true },
              { "pos": [3207, 3214, 0], "object": 999, "disabled": true },
              { "pos": [3207, 3214, 0] } ]
        """
        val found = problems(OBSTACLES to door, OBSTACLE_OVERRIDES to overrides)
        assertEquals(3, found.size)
        assertTrue(found[0].startsWith("obstacle_overrides.json[0]: matches no obstacle"))
        assertTrue(found[1].startsWith("obstacle_overrides.json[1]: matches no obstacle"))
        assertTrue(found[2].startsWith("obstacle_overrides.json[2]: must set at least one of"))
    }

    @Test
    fun teleportRulesDependOnKind() {
        val teleports = """
            [ { "id": "a", "kind": "SPELL", "dest": [1, 1], "cost": 1 },
              { "id": "b", "kind": "JEWELLERY", "key": "RING", "dest": [1, 1], "cost": 1 },
              { "id": "c", "kind": "SPELL", "key": "X", "option": 2, "dest": [1, 1], "cost": 1 },
              { "id": "d", "kind": "TELEPORT", "key": "X", "dest": [1, 1], "cost": 1 },
              { "id": "e", "kind": "HOME", "dest": [1, 1], "cost": 1 },
              { "id": "e", "kind": "HOME", "dest": [2, 2], "cost": 1 } ]
        """
        val found = problems(TELEPORTS to teleports)
        assertEquals(5, found.size)
        assertTrue(found[0].startsWith("teleports.jsonc[0].key: is required"))
        assertTrue(found[1].startsWith("teleports.jsonc[1].option: is required"))
        assertTrue(found[2].startsWith("teleports.jsonc[2].option: is only for"))
        assertTrue(found[3].startsWith("teleports.jsonc[3].kind: must be one of"))
        assertTrue(found[4].startsWith("teleports.jsonc[5]: duplicate"))
    }

    @Test
    fun fairyRingCodesAreChecked() {
        val rings = """
            [ { "id": "a", "code": "AIP", "pos": [1, 1] },
              { "id": "b", "code": "AIP", "pos": [2, 2] },
              { "id": "c", "code": "ZZZ", "pos": [3, 3] } ]
        """
        val found = problems(FAIRY_RINGS to rings)
        assertEquals(2, found.size)
        assertTrue(found[0].startsWith("fairy_rings.json[1].code: duplicate fairy ring code"))
        assertTrue(found[1].startsWith("fairy_rings.json[2].code: must be a letter of A-D"))
    }

    @Test
    fun invalidJsonAndWrongRootsAreReported() {
        val found = problems(WALK_GRAPH to "{ \"waypoints\": [ ", OBSTACLES to "{}", SHIPS to "[1]", TELEPORTS to "5")
        assertEquals(4, found.size)
        assertTrue(found.any { it.startsWith("walk_graph.jsonc: invalid JSON") })
        assertTrue(found.any { it.startsWith("obstacles.jsonc: must be an array") })
        assertTrue(found.any { it.startsWith("ships.json: must be an object") })
        assertTrue(found.any { it.startsWith("teleports.jsonc: must be an array") })
    }

    @Test
    fun everyProblemIsReportedAtOnce() {
        val exception = assertThrows(WebWalkDataException::class.java) {
            load(WALK_GRAPH to """{ "waypoints": [ { "id": "a" } ] }""", FAIRY_RINGS to """[ { "id": "b" } ]""")
        }
        assertEquals(3, exception.problems.size)
        assertTrue(exception.message!!.startsWith("Invalid web-walker data (3 problems)"))
    }

    @Test
    fun shippedDataFilesLoad() {
        val graph = WebWalkLoader.load(Path.of("data/game/bots/webwalk"))
        assertNotNull(graph)
    }

    @Test
    fun shippedGraphConnectsTheMainTowns() {
        val graph = WebWalkLoader.load(Path.of("data/game/bots/webwalk"))

        // Walk, open doors and gates, and climb, without caring what the bot needs.
        val reached = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue += "zone_lumbridge"
        reached += "zone_lumbridge"
        while (queue.isNotEmpty()) {
            for (edge in graph.edgesFrom(queue.removeFirst())) {
                if (reached.add(edge.to)) {
                    queue += edge.to
                }
            }
        }
        for (town in listOf("zone_varrock", "zone_falador", "zone_draynor", "zone_edgeville", "zone_burthorpe")) {
            assertTrue(town in reached, "$town can't be reached from Lumbridge.")
        }
    }

    @Test
    fun shippedScatterWaypointsAreConnectedAndCloseTogether() {
        val graph = WebWalkLoader.load(Path.of("data/game/bots/webwalk"))
        val scatter = graph.nodes.values.filter { ScatterWaypoints.TAG in it.tags }
        assertTrue(scatter.size > 1000, "Only ${scatter.size} scattered waypoints.")

        // Every scattered waypoint is walkable to one that is close, which is within the range of the route pathfinder.
        val lonely = scatter.filter { node -> graph.edgesFrom(node.id).none { it.type == EdgeType.WALK } }
        assertTrue(lonely.isEmpty(), "Scattered waypoints without a walk: ${lonely.take(5).map { it.id }}")
        for (node in scatter) {
            val nearest = graph.edgesFrom(node.id).filter { it.type == EdgeType.WALK }
                .minOf { graph.node(it.to)!!.position.computeLongestDistance(node.position) }
            assertTrue(nearest <= 56, "${node.id} is $nearest tiles from its nearest neighbour.")
        }

        // Walking, climbing and opening doors from the start of the game reaches most of the mainland. Places behind crossings
        // that aren't doors yet, such as ships, the Shantay Pass and Mort Myre, are not reached until those are added.
        val reached = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue += "zone_lumbridge"
        reached += "zone_lumbridge"
        while (queue.isNotEmpty()) {
            for (edge in graph.edgesFrom(queue.removeFirst())) {
                if (reached.add(edge.to)) {
                    queue += edge.to
                }
            }
        }
        val reachable = scatter.count { it.id in reached }
        assertTrue(reachable > 500, "Only $reachable scattered waypoints can be reached from Lumbridge.")
    }
}
