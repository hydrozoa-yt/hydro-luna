package world.obj.doors

import api.predef.*
import api.predef.ext.*
import io.luna.game.event.impl.ServerStateChangedEvent.ServerLaunchEvent
import io.luna.game.model.mob.*
import io.luna.game.model.`object`.*

Doors.nextDoor.forEach(
    {(key, value) ->
        object1(key) {
            handleDoorClick(gameObject, plr)
        }
        object1(value) {
            handleDoorClick(gameObject, plr)
        }
    }
)

fun handleDoorClick(gameObject: GameObject, plr: Player) {
    System.out.println("Clicked door: "+gameObject+" "+gameObject.direction)
    if (Doors.openDoors.contains(gameObject.position)) {
        Doors.closeDoor(world, gameObject)
        plr.sendMessage("Ran closeDoor()")
    } else {
        Doors.openDoor(world, gameObject)
        plr.sendMessage("Ran openDoor()")
    }
}