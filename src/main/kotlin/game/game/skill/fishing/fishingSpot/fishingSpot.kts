package game.skill.fishing.fishingSpot

import api.predef.*
import io.luna.game.model.*
import io.luna.game.event.impl.ServerStateChangedEvent.ServerLaunchEvent

// Begin processing for rotating fishing spots.
on(ServerLaunchEvent::class) {
    world.schedule(FishingSpotHandler)
}

/* Enable rotating locations for fishing spots here. If a fishing spot is
    not manually added here they will not rotate locations. */

// Just a test and example.
/*FishingSpotHandler.add(spawn = true) {
    id = 316
    home = Position(3222, 3222)
    away += Position(3222, 3221)
}*/

// Draynor village
FishingSpotHandler.add(spawn = true) {
    id = 316
    home = Position(3084, 3230)
    away += Position(3085, 3229)
}
FishingSpotHandler.add(spawn = true) {
    id = 316
    home = Position(3086, 3228)
    away += Position(3086, 3223)
}