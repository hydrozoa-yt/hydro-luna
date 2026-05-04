package game.skill.farming

import api.predef.*
import game.skill.farming.Farming.herbPatch
import io.luna.game.event.impl.*
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.varp.*

// config 504 = allotment plots Falador, specific bits for each plot
// config 505 = allotment plots Ardy, probably specific bits for each plot

/* == config 515
 * bitshifting 16 times leftly changes the herb patch in ardy
 * bitshifting 12 times leftly changes the herb patch in catherby
 * bitshifting 0 times leftly changes the herb patch in falador
 */

// object 7849 = flower patch ardy
// object 8151 = herb patch catherby
// object 8152 = herb patch ardy
// object 8552 = northern allotment patch catherby
// object 8553 = southern allotment patch catherby
// object 8554 = northern allotment patch ardy
// object 8555 = southern allotment patch ardy


/*
 * each herb patch has its own unique id
 */


// todo hook mapupdate event to send correct patches

// raking herb patch falador
/*useItem(5341).onObject(8150) {
    plr.submitAction(RakePatchAction(plr, gameObject))
}

// raking allotment patch falador
useItem(5341).onObject(8551) {
    plr.sendMessage("Raking the allotment patch")
}*/

Farming.HERB_PATCHES.forEach { id ->
    useItem(5341).onObject(id) {
        plr.submitAction(RakePatchAction(plr, gameObject))
    }
}

Farming.ALLOTMENT_PATCHES.forEach { id ->
    useItem(5341).onObject(id) {
        plr.submitAction(RakePatchAction(plr, gameObject))
    }
}

// Send farming state when logged in
// todo send when region is loaded
on(LoginEvent::class) {
    plr.sendVarp(plr.herbPatch.getVarp())
}

useItem(6032).onObject(7836) {
    plr.sendMessage("Composting")
    plr.sendVarp(Varp(511, 10))
}

useItem(6034).onObject(7836) {
    plr.sendVarp(Varp(512, 10))
    plr.sendMessage("Supercomposting")
}

npc1(2323) {
    plr.sendMessage("Talking")
}