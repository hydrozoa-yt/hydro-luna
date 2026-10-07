package api.bot.webwalk.walk

import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for the parts of [WebWalkExecutor] that don't need a bot.
 *
 * @author Hydrozoa
 */
class WebWalkExecutorTest {

    @Test
    fun climbsBetweenPlanesGoUpOrDown() {
        assertTrue(WebWalkExecutor.isUp(Position(3205, 3209, 0), Position(3205, 3209, 1)))
        assertTrue(WebWalkExecutor.isUp(Position(3205, 3209, 0), Position(3205, 3209, 2)))
        assertFalse(WebWalkExecutor.isUp(Position(3205, 3209, 1), Position(3205, 3209, 0)))
        assertFalse(WebWalkExecutor.isUp(Position(3205, 3209, 2), Position(3205, 3209, 1)))
    }

    @Test
    fun climbsIntoADungeonGoDownAndOutOfOneGoUp() {
        // Dungeons are 6400 tiles north of the surface.
        assertFalse(WebWalkExecutor.isUp(Position(3209, 3216, 0), Position(3209, 9616, 0)))
        assertTrue(WebWalkExecutor.isUp(Position(3209, 9616, 0), Position(3209, 3216, 0)))
    }

    @Test
    fun aClimbThatMovesNowhereMuchGoesUp() {
        assertTrue(WebWalkExecutor.isUp(Position(3200, 3200, 0), Position(3202, 3201, 0)))
    }

    @Test
    fun optionsAreNumberedFromOneAndIgnoreCase() {
        val actions = listOf("Climb", "Climb-up", "Climb-down", null, null)

        assertEquals(1, WebWalkExecutor.optionNumber(actions, "Climb"))
        assertEquals(2, WebWalkExecutor.optionNumber(actions, "climb-up"))
        assertEquals(3, WebWalkExecutor.optionNumber(actions, "CLIMB-DOWN"))
    }

    @Test
    fun missingOptionsAndOptionsPastTheThirdHaveNoNumber() {
        val actions = listOf("Open", null, null, "Examine", "Search")

        assertNull(WebWalkExecutor.optionNumber(actions, "Close"))
        assertNull(WebWalkExecutor.optionNumber(actions, "Examine"))
        assertNull(WebWalkExecutor.optionNumber(emptyList(), "Open"))
        assertEquals(1, WebWalkExecutor.optionNumber(actions, "Open"))
    }
}
