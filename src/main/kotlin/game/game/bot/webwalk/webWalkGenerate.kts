package game.bot.webwalk

import api.bot.webwalk.WebWalkGenerationRunner
import api.predef.*
import api.predef.ext.*
import io.luna.game.event.impl.ServerStateChangedEvent.ServerLaunchEvent

/**
 * The system property that makes the server generate the web-walker data once it has launched, and then exit. Its value
 * is `check` to only report the files that are out of date, and anything else to write them. It is set by the
 * `generateWebWalk` Gradle task.
 */
val GENERATE_PROPERTY = "luna.webwalk.generate"

/**
 * Generates the data in the first tick after the server has launched.
 */
on(ServerLaunchEvent::class) {
    val mode = System.getProperty(GENERATE_PROPERTY)
    if (mode != null) {
        world.scheduleOnce(1) {
            WebWalkGenerationRunner.run(mode == "check")
        }
    }
}
