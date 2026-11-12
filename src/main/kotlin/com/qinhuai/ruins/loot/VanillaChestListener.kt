package com.qinhuai.ruins.loot

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.loot.Lootable

/**
 * 原版遗迹箱子也纳入每周限额：只统计带战利品表的容器
 * （世界生成时挂表 = 结构箱子；玩家自己放的箱子没有表，不计数）。
 */
object VanillaChestListener : Listener {

    private val CONTAINERS = setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL)

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.type !in CONTAINERS) return
        if (ChestRegistry.at(block) != null) return
        val lootable = block.state as? Lootable ?: return
        if (lootable.lootTable == null) return
        val player = event.player
        if (!WeeklyLimitService.tryConsume(player, WeeklyLimitService.SCOPE_VANILLA)) {
            event.isCancelled = true
            val key = "vanilla|${block.world.name}|${block.x}|${block.y}|${block.z}"
            WeeklyLimitService.deny(player, WeeklyLimitService.SCOPE_VANILLA, key)
        }
    }
}
