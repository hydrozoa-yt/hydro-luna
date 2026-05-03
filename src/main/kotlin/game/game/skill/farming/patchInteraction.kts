package game.skill.farming

import api.predef.*
import io.luna.game.model.mob.Player
import io.luna.game.model.mob.varp.*

// config 504 = allotment plots Falador, specific bits for each plot

// raking herb patch falador
useItem(5341).onObject(8150) {
    plr.submitAction(RakePatchAction(plr, gameObject))
    plr.sendVarp(Varp(515, 10))
}

// raking allotment patch falador
useItem(5341).onObject(8551) {
    plr.sendMessage("Raking the allotment patch")
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