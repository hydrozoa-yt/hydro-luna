package world.location.falador

import api.predef.*
import api.predef.ext.*
import io.luna.game.event.impl.ServerStateChangedEvent.ServerLaunchEvent
import api.shop.dsl.ShopHandler
import io.luna.game.model.item.shop.BuyPolicy
import io.luna.game.model.item.shop.Currency
import io.luna.game.model.item.shop.RestockPolicy

val shopkeeperId = 580

/**
 * Flynns mace shop in Falador.
 */
ShopHandler.create("Flynn's Mace Market.") {
    buy = BuyPolicy.EXISTING
    restock = RestockPolicy.SLOW
    currency = Currency.COINS

    sell {
        "Bronze mace" x 5
        "Iron mace" x 4
        "Steel mace" x 4
        "Mithril mace" x 3
        "Adamant mace" x 2
    }

    open {
        npc2 += shopkeeperId
    }
}

/**
 * Dialogue for "Talk" option.
 */
npc1(shopkeeperId) {
    plr.newDialogue()
        .npc(targetNpc.id, "Hello. Do you want to buy or sell any maces?")
        .options("No thanks.", {
            plr.newDialogue().player("No thanks.").open()
        }, "Well, I'll have a look, at least.", {
            it.interfaces.openShop("Flynn's Mace Market.")
        })
        .open()
}

/**
 * Spawn Flynn NPC.
 */
on(ServerLaunchEvent::class) {
    world.addNpc(id = shopkeeperId,
        x = 2950,
        y = 3387)
}