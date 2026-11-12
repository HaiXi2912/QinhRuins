package com.qinhuai.ruins.loot

import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.core.Durations
import com.qinhuai.ruins.data.SharedVesselStore
import com.qinhuai.ruins.data.VesselStore
import com.qinhuai.ruins.generation.AnchorManager
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.template.TemplateRegistry
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.Lidded
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.BrewerInventory
import org.bukkit.inventory.FurnaceInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

object VesselService {

    class VesselHolder(val key: String, val blockLocation: Location) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    private var enabled = true
    private var timed = false
    private var cooldownMillis = 0L
    private var rows = 3

    fun configure(config: FileConfiguration) {
        enabled = config.getBoolean("vessel.enabled", true)
        timed = config.getString("vessel.refresh-mode", "per-player-once").equals("timed", true)
        cooldownMillis = Durations.seconds(config.getString("vessel.refresh-cooldown") ?: "30m") * 1000
        rows = config.getInt("vessel.rows", 3).coerceIn(1, 6)
    }

    fun tryOpen(block: Block, player: Player): Boolean {
        if (!enabled) return false
        if (block.state !is Container) return false
        val anchor = AnchorManager.containing(block.location) ?: return false
        val template = TemplateRegistry.get(anchor.templateId) ?: return false
        val tableName = template.containerTables[block.type.name] ?: template.containerTable ?: return false
        val table = LootTableRegistry.get(tableName) ?: return false
        if (table.containers.isNotEmpty() && block.type.name !in table.containers) return false
        val scope = WeeklyLimitService.scopeOfTable(tableName)
        if (!WeeklyLimitService.tryConsume(player, scope)) {
            WeeklyLimitService.deny(player, scope, "vessel|${anchor.id}|${canonicalLoc(block)}")
            return true
        }
        if (template.vesselMode == "shared") {
            populateShared(player, anchor.id, block, table, tableName)
            return false
        }
        open(player, anchor.id, block, table, tableName)
        return true
    }

    private fun populateShared(player: Player, anchorId: String, block: Block, table: LootTable, tableName: String) {
        val key = sharedKey(anchorId, canonicalLoc(block))
        if (SharedVesselStore.isPopulated(key)) return
        val loot = LootService.roll(table, player, false, ignoreGrowth = true).toMutableList()
        val event = com.qinhuai.ruins.api.RuinLootEvent(player, anchorId, tableName, loot)
        Bukkit.getPluginManager().callEvent(event)
        val container = block.state as? Container ?: return
        if (!event.isCancelled) fillContainer(container, event.items)
        SharedVesselStore.markPopulated(key)
    }

    private fun fillContainer(container: Container, loot: List<ItemStack>) {
        when (val inv = container.inventory) {
            is FurnaceInventory -> {
                val leftover = ArrayList<ItemStack>()
                for (item in loot) when {
                    isFuel(item.type) && inv.fuel == null -> inv.fuel = item
                    inv.smelting == null -> inv.smelting = item
                    inv.result == null -> inv.result = item
                    else -> leftover.add(item)
                }
                dropOverflow(container, leftover)
            }
            is BrewerInventory -> {
                val leftover = ArrayList<ItemStack>()
                var bottle = 0
                for (item in loot) when {
                    item.type == Material.BLAZE_POWDER && inv.fuel == null -> inv.fuel = item
                    isPotion(item.type) && bottle < 3 -> { inv.setItem(bottle, item); bottle++ }
                    inv.ingredient == null -> inv.ingredient = item
                    else -> leftover.add(item)
                }
                dropOverflow(container, leftover)
            }
            else -> dropOverflow(container, inv.addItem(*loot.toTypedArray()).values)
        }
    }

    private fun dropOverflow(container: Container, items: Collection<ItemStack>) {
        if (items.isEmpty()) return
        val loc = container.location
        val world = loc.world ?: return
        items.forEach { world.dropItemNaturally(loc.clone().add(0.5, 1.0, 0.5), it) }
    }

    private fun isFuel(type: Material): Boolean {
        val name = type.name
        return type == Material.COAL || type == Material.CHARCOAL || type == Material.COAL_BLOCK ||
            type == Material.BLAZE_ROD || type == Material.LAVA_BUCKET || type == Material.DRIED_KELP_BLOCK ||
            type == Material.STICK || type == Material.BAMBOO ||
            name.endsWith("_LOG") || name.endsWith("_PLANKS") || name.endsWith("_WOOD")
    }

    private fun isPotion(type: Material): Boolean =
        type == Material.POTION || type == Material.SPLASH_POTION || type == Material.LINGERING_POTION ||
            type == Material.GLASS_BOTTLE

    private fun sharedKey(anchorId: String, loc: Location): String =
        "$anchorId|${loc.world?.name}:${loc.blockX}:${loc.blockY}:${loc.blockZ}"

    private fun canonicalLoc(block: Block): Location =
        (block.state as? Container)?.inventory?.location ?: block.location

    private fun open(player: Player, anchorId: String, block: Block, table: LootTable, tableName: String) {
        val key = key(player, anchorId, canonicalLoc(block))
        val record = VesselStore.get(key)
        val now = System.currentTimeMillis()
        val fresh = record == null || (timed && now - record.openedAt >= cooldownMillis)

        val holder = VesselHolder(key, block.location)
        val inventory = Bukkit.createInventory(holder, rows * 9, TextUtil.toComponent(Lang.get("realm.vessel-gui-title")))
        holder.backing = inventory

        if (fresh) {
            val loot = LootService.roll(table, player, true).toMutableList()
            val event = com.qinhuai.ruins.api.RuinLootEvent(player, anchorId, tableName, loot)
            Bukkit.getPluginManager().callEvent(event)
            val slots = scatter(inventory, if (event.isCancelled) emptyList() else event.items)
            VesselStore.put(key, now, slots)
        } else {
            record.slots.forEach { (slot, item) -> if (slot < inventory.size) inventory.setItem(slot, item) }
        }

        (block.state as? Lidded)?.open()
        player.openInventory(inventory)
    }

    private fun scatter(inventory: Inventory, loot: List<ItemStack>): Map<Int, ItemStack> {
        val slots = (0 until inventory.size).shuffled().take(loot.size)
        val result = HashMap<Int, ItemStack>()
        loot.forEachIndexed { index, item ->
            val slot = slots.getOrNull(index) ?: return@forEachIndexed
            inventory.setItem(slot, item)
            result[slot] = item
        }
        return result
    }

    fun saveOnClose(holder: VesselHolder, inventory: Inventory) {
        val slots = HashMap<Int, ItemStack>()
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (!item.type.isAir) slots[i] = item
        }
        VesselStore.updateSlots(holder.key, slots)
        VesselStore.save()
        (holder.blockLocation.block.state as? Lidded)?.close()
    }

    private fun key(player: Player, anchorId: String, loc: Location): String =
        "${player.uniqueId}|$anchorId|${loc.world?.name}:${loc.blockX}:${loc.blockY}:${loc.blockZ}"
}
