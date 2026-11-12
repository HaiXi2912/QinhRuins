package com.qinhuai.ruins.combat

import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.QinhRuins
import com.qinhuai.ruins.core.Anchor
import com.qinhuai.ruins.generation.AnchorManager
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.loot.ChestRegistry
import com.qinhuai.ruins.mechanism.MechanismService
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent

/**
 * 遗迹保护：
 *  - 战利品箱（QR 注册的箱子 / 配置了容器表的容器）无论是否通关都不可破坏
 *  - 矿物、矿物块、粗矿块：任何阶段都允许挖，正常掉落，不恢复
 *  - 其他结构方块：允许挖，不掉落，10 秒（restore-delay-ticks）后自动恢复
 *  - 玩家放置的方块：允许放，10 秒（place-restore-ticks）后自动消失，用于垫脚
 *  - 爆炸不会破坏遗迹内非矿物方块
 */
object RuinProtectionListener : Listener {

    private var enabled = true
    private var protectChests = true
    private var chestMessage = true
    private var breakRestoreTicks = 200L
    private var placeRestoreTicks = 200L
    private var noDrop = true
    private val oreTags = ArrayList<Tag<Material>>()
    private val oreBlocks = HashSet<Material>()
    private val pendingRestore = HashMap<Location, BlockData>()
    private val pendingPlace = HashMap<Location, Material>()

    fun configure(config: FileConfiguration) {
        enabled = config.getBoolean("protection.enabled", true)
        protectChests = config.getBoolean("protection.chests", true)
        chestMessage = config.getBoolean("protection.chest-message", true)
        breakRestoreTicks = config.getLong("protection.restore-delay-ticks", 200L).coerceAtLeast(1L)
        placeRestoreTicks = config.getLong("protection.place-restore-ticks", breakRestoreTicks).coerceAtLeast(1L)
        noDrop = config.getBoolean("protection.no-drop", true)

        oreTags.clear()
        for (raw in config.getStringList("protection.ore-tags")) {
            val key = NamespacedKey.fromString(raw.removePrefix("#")) ?: continue
            Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material::class.java)?.let { oreTags.add(it) }
        }
        oreBlocks.clear()
        for (raw in config.getStringList("protection.ore-blocks")) {
            Material.matchMaterial(raw)?.let { oreBlocks.add(it) }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!enabled) return
        if (event.player.hasPermission("qinhruins.admin")) return
        val block = event.block
        val anchor = AnchorManager.containing(block.location) ?: return
        if (MechanismService.isBreakTrigger(block)) return

        if (protectChests && isLootContainer(block, anchor)) {
            event.isCancelled = true
            if (chestMessage) TextUtil.sendColored(event.player, Lang.get("core.protect-chest"))
            return
        }
        if (isOre(block.type)) return
        if (noDrop) {
            event.setDropItems(false)
            event.setExpToDrop(0)
        }
        scheduleRestore(block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!enabled) return
        if (event.player.hasPermission("qinhruins.admin")) return
        if (AnchorManager.containing(event.block.location) == null) return
        schedulePlaceRemoval(event.block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (!enabled) return
        event.blockList().removeIf { block ->
            AnchorManager.containing(block.location) != null && !isOre(block.type)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (!enabled) return
        event.blockList().removeIf { block ->
            AnchorManager.containing(block.location) != null && !isOre(block.type)
        }
    }

    private fun scheduleRestore(block: Block) {
        val loc = block.location.clone()
        val data = block.blockData.clone()
        pendingRestore[loc] = data
        Bukkit.getScheduler().runTaskLater(QinhRuins.instance, Runnable {
            val saved = pendingRestore.remove(loc) ?: return@Runnable
            val current = loc.block
            if (current.type.isAir) current.blockData = saved
        }, breakRestoreTicks)
    }

    private fun schedulePlaceRemoval(block: Block) {
        val loc = block.location.clone()
        val placed = block.type
        pendingPlace[loc] = placed
        Bukkit.getScheduler().runTaskLater(QinhRuins.instance, Runnable {
            val saved = pendingPlace.remove(loc) ?: return@Runnable
            val current = loc.block
            if (current.type == saved) current.type = Material.AIR
        }, placeRestoreTicks)
    }

    private fun isLootContainer(block: Block, anchor: Anchor): Boolean {
        if (ChestRegistry.at(block) != null) return true
        if (block.state !is org.bukkit.block.Container) return false
        val template = com.qinhuai.ruins.template.TemplateRegistry.get(anchor.templateId) ?: return false
        return template.containerTable != null || template.containerTables.isNotEmpty()
    }

    private fun isOre(type: Material): Boolean {
        if (type in oreBlocks) return true
        return oreTags.any { it.isTagged(type) }
    }
}
