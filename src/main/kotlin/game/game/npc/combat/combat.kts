package game.npc.combat

import api.predef.*
import io.luna.game.event.impl.ServerStateChangedEvent.ServerLaunchEvent
import io.luna.game.model.def.*

on(ServerLaunchEvent::class) {
    val foeIds = mutableSetOf<Int>()

    NpcDefinition.ALL
        .filter { npcDefinition -> npcDefinition?.hasAction(1, "Attack") ?: false }
        .forEach { npcDefinition -> foeIds.add(npcDefinition.id) }

    System.out.println("Found attackable foes:"+foeIds.size)

    for (id in foeIds) {
        npc3(id) {
            plr.sendMessage("Attacking "+NpcDefinition.ALL.get(id).get().name+"...")
            plr.submitAction(PlayerAttackNPCAction(plr, targetNpc))
        }
    }
}
