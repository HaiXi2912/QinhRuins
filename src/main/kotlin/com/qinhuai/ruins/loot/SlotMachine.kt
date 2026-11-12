package com.qinhuai.ruins.loot

import com.qinhuai.corelib.util.ItemUtils
import com.qinhuai.corelib.util.ServerCompat
import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.QinhRuins
import com.qinhuai.ruins.data.SpinStore
import com.qinhuai.ruins.generation.AnchorManager
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.template.TemplateRegistry
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.util.Random

object SlotMachine {

    const val DISPLAY_SLOT = 13
    const val BUTTON_SLOT = 22

    class SlotHolder(val anchorId: String) : InventoryHolder {
        lateinit var backing: Inventory
        var spinning = false
        var done = false
        override fun getInventory(): Inventory = backing
    }

    fun grantOnClear(anchorId: String) {
        val anchor = AnchorManager.get(anchorId) ?: return
        val template = TemplateRegistry.get(anchor.templateId) ?: return
        if (template.clearRewardTable == null) return
        val world = anchor.location.world ?: return
        for (player in world.getNearbyPlayers(anchor.location, 48.0)) {
            SpinStore.grant(player.uniqueId, anchorId)
            TextUtil.sendColored(player, Lang.get("realm.spin-ready"))
        }
    }

    fun openNearest(player: Player) {
        val anchorId = SpinStore.anchorsFor(player.uniqueId).firstOrNull {
            TemplateRegistry.get(AnchorManager.get(it)?.templateId ?: "")?.clearRewardTable != null
        }
        if (anchorId == null) {
            TextUtil.sendColored(player, Lang.get("realm.no-spin-available"))
            return
        }
        open(player, anchorId)
    }

    private fun open(player: Player, anchorId: String) {
        val holder = SlotHolder(anchorId)
        val inventory = Bukkit.createInventory(holder, 27, TextUtil.toComponent(Lang.get("realm.spin-gui-title")))
        holder.backing = inventory
        val filler = ItemUtils.createItem(Material.GRAY_STAINED_GLASS_PANE, 1, " ")
        for (slot in 0 until 27) inventory.setItem(slot, filler)
        inventory.setItem(DISPLAY_SLOT, ItemUtils.createItem(Material.NETHER_STAR, 1, Lang.get("realm.spin-display-placeholder")))
        inventory.setItem(BUTTON_SLOT, ItemUtils.createItem(Material.LEVER, 1, Lang.get("realm.spin-button-name"), listOf(Lang.get("realm.spin-button-lore"))))
        player.openInventory(inventory)
    }

    fun onButton(player: Player, holder: SlotHolder) {
        if (holder.spinning || holder.done) return
        if (!SpinStore.canSpin(player.uniqueId, holder.anchorId)) {
            TextUtil.sendColored(player, Lang.get("realm.spin-already-claimed"))
            return
        }
        val anchor = AnchorManager.get(holder.anchorId) ?: return
        val template = TemplateRegistry.get(anchor.templateId) ?: return
        val table = LootTableRegistry.get(template.clearRewardTable ?: return) ?: return
        val pool = LootService.pool(table, player)
        if (pool.isEmpty()) {
            TextUtil.sendColored(player, Lang.get("realm.spin-no-suitable-reward"))
            return
        }
        val reward = LootService.pickOne(player, pool, true) ?: return
        if (!WeeklyLimitService.tryConsume(player, WeeklyLimitService.SCOPE_SLOT)) {
            WeeklyLimitService.deny(player, WeeklyLimitService.SCOPE_SLOT, "slot|${holder.anchorId}")
            return
        }
        SpinStore.consume(player.uniqueId, holder.anchorId)
        holder.spinning = true
        val samples = pool.mapNotNull { LootService.build(it, player, false) }.ifEmpty { listOf(reward) }
        animate(player, holder, samples, reward)
    }

    private fun animate(player: Player, holder: SlotHolder, samples: List<ItemStack>, reward: ItemStack) {
        val inventory = holder.backing
        inventory.setItem(BUTTON_SLOT, ItemUtils.createItem(Material.BARRIER, 1, Lang.get("realm.spin-spinning")))
        val random = Random()
        object : BukkitRunnable() {
            private var step = 0
            override fun run() {
                if (step >= 24) {
                    inventory.setItem(DISPLAY_SLOT, reward)
                    inventory.setItem(BUTTON_SLOT, ItemUtils.createItem(Material.EMERALD_BLOCK, 1, Lang.get("realm.spin-claimed")))
                    holder.spinning = false
                    holder.done = true
                    val overflow = player.inventory.addItem(reward.clone())
                    overflow.values.forEach { player.world.dropItemNaturally(player.location, it) }
                    TextUtil.sendColored(player, Lang.get("realm.spin-won"))
                    ServerCompat.resolveSound("ENTITY_PLAYER_LEVELUP")?.let { player.playSound(player.location, it, 1.0f, 1.5f) }
                    cancel()
                    return
                }
                inventory.setItem(DISPLAY_SLOT, samples[random.nextInt(samples.size)])
                ServerCompat.resolveSound("UI_BUTTON_CLICK")?.let { player.playSound(player.location, it, 0.4f, 1.0f) }
                step++
            }
        }.runTaskTimer(QinhRuins.instance, 2L, 2L)
    }
}
