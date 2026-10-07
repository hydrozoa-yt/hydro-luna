package api.bot.webwalk.plan

import io.luna.game.model.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [DoorCrossings].
 *
 * @author Hydrozoa
 */
class DoorCrossingsTest {

    private val door = DoorCrossings(listOf(Pair(Position(10, 10), Position(11, 10))))

    @Test
    fun aStepThroughTheDoorCrosses() {
        assertTrue(door.crosses(Position(8, 10), listOf(Position(13, 10))))
        assertTrue(door.crosses(Position(13, 10), listOf(Position(8, 10))))
    }

    @Test
    fun aRouteBesideTheDoorDoesNot() {
        assertFalse(door.crosses(Position(8, 11), listOf(Position(13, 11))))
        assertFalse(door.crosses(Position(8, 10), listOf(Position(10, 10))))
        assertFalse(door.crosses(Position(8, 10), listOf(Position(8, 14), Position(14, 14))))
    }

    @Test
    fun aDiagonalStepAcrossTheDoorCrosses() {
        assertTrue(door.crosses(Position(10, 9), listOf(Position(11, 10), Position(12, 10)))
                || door.crosses(Position(10, 11), listOf(Position(11, 10))))
    }

    @Test
    fun aDoorOnAnotherPlaneIsNotCrossed() {
        assertFalse(door.crosses(Position(8, 10, 1), listOf(Position(13, 10, 1))))
        assertFalse(DoorCrossings(emptyList()).crosses(Position(8, 10), listOf(Position(13, 10))))
    }
}
