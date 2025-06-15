package world.location.varrock

import api.predef.*
import api.predef.ext.*
import io.luna.game.event.impl.ServerStateChangedEvent.ServerLaunchEvent
import io.luna.game.model.*
import java.util.*

on(ServerLaunchEvent::class) {
    // Bankers of west bank
    //world.addNpc(494, 2945, 3366).defaultDirection = Optional.of(Direction.NORTH)
}