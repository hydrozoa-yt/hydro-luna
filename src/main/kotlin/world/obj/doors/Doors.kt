package world.obj.doors

import api.predef.*
import api.predef.ext.*
import io.luna.game.model.*
import io.luna.game.model.chunk.*
import io.luna.game.model.`object`.*

object Doors {

    /**
     * Map of closed door ids mapped to their corresponding open door
     */
    val nextDoor: Map<Int, Int> = mapOf(
        1533 to 1534,
        1530 to 1531,
    )

    /**
     * Map of open doors mapped by their position, each entry is a pair of the closed door static object and the open door dynamic object.
     */
    val openDoors: MutableMap<Position, Pair<GameObject, GameObject>> = mutableMapOf()

    fun openDoor(world: World, closedDoor: GameObject) {
        var nextDoorId: Int = -1
        if (nextDoor.contains(closedDoor.id)) {
            nextDoorId = nextDoor.get(closedDoor.id)!!
        }
        val openDoorObj = GameObject.createDynamic(
            ctx,
            if (nextDoorId != -1) nextDoorId else closedDoor.id,
            closedDoor.position,
            ObjectType.STRAIGHT_WALL,
            ObjectDirection.SOUTH,
            ChunkUpdatableView.globalView())
        world.addObject(openDoorObj) // test
        openDoors.put(closedDoor.position, Pair(closedDoor, openDoorObj))
    }

    fun closeDoor(world: World, openDoor: GameObject) {
        if (!openDoors.contains(openDoor.position)) {
            return
        }
        val doorEntry = openDoors.get(openDoor.position)!!
        val closedObj = doorEntry.first

        val closedDoorDynamicObj = GameObject.createDynamic(ctx, closedObj.id, closedObj.position, closedObj.objectType, closedObj.direction, ChunkUpdatableView.globalView())
        world.addObject(closedDoorDynamicObj)
        openDoors.remove(openDoor.position)
    }
}