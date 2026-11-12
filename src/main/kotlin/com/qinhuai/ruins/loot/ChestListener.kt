package com.qinhuai.ruins.loot

import com.qinhuai.corelib.util.ServerCompat
import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.combat.ObjectiveTracker
import com.qinhuai.ruins.data.LootClaimStore
import com.qinhuai.ruins.lang.Lang
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

object ChestListener : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val ref = ChestRegistry.at(block)
        if (ref == null) {
            if (VesselService.tryOpen(block, event.player)) event.isCancelled = true
            return
        }

        event.isCancelled = true
        val player = event.player
        val chest = ref.chest

        if (chest.unlockStage > ObjectiveTracker.effectiveStage(ref.anchorId)) {
            TextUtil.sendColored(player, Lang.get("messages.chest-locked"))
            return
        }

        if (chest.perPlayerOnce && LootClaimStore.has(player.uniqueId, ref.anchorId, chest.id)) {
            TextUtil.sendColored(player, Lang.get("messages.chest-claimed"))
            return
        }

        val scope = WeeklyLimitService.scopeOfTable(chest.lootTable)
        if (!WeeklyLimitService.tryConsume(player, scope)) {
            WeeklyLimitService.deny(player, scope, "chest|${ref.anchorId}|${chest.id}")
            return
        }

        val table = LootTableRegistry.get(chest.lootTable)
        if (table == null) {
            TextUtil.sendColored(player, Lang.get("realm.chest-table-missing", "table" to chest.lootTable))
            return
        }

        val loot = LootService.roll(table, player, chest.growthScaled)
        if (chest.perPlayerOnce) LootClaimStore.add(player.uniqueId, ref.anchorId, chest.id)
        giveAll(player, loot)
        ServerCompat.resolveSound("ENTITY_PLAYER_LEVELUP")?.let { player.playSound(player.location, it, 1.0f, 1.4f) }
        TextUtil.sendColored(player, Lang.get("realm.chest-opened", "count" to loot.size))
    }

    private fun giveAll(player: Player, items: List<ItemStack>) {
        if (items.isEmpty()) return
        val overflow = player.inventory.addItem(*items.toTypedArray())
        overflow.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }
}
