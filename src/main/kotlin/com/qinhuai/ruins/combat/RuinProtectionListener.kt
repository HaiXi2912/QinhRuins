package com.qinhuai.ruins.combat

import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.QinhRuins
import com.qinhuai.ruins.core.Anchor
import com.qinhuai.ruins.core.AnchorState
import com.qinhuai.ruins.generation.AnchorManager
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.loot.ChestRegistry
import com.qinhuai.ruins.mechanism.MechanismService
import com.qinhuai.ruins.realm.RealmManager
import com.qinhuai.ruins.template.BlueprintRegistry
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
 *  - 战利品箱（QR 注册的箱子）无论是否通关都不可破坏
 *  - 通关前：结构方块不可破坏/放置
 *  - 通关后：按 protection.after-clear 处理（deny=仍锁 / restore=可破坏但自动恢复 / allow=自由）
 *  - 矿物、矿物块、粗矿块：任何阶段都允许挖，正常掉落，不恢复
 */
object RuinProtectionListener : Listener {

    enum class AfterClear { DENY, RESTORE, ALLOW }

    private var enabled = true
    private var protectChests = true
    private var chestMessage = true
    private var afterClear = AfterClear.RESTORE
    private var restoreDelayTicks = 100L
    private var noDrop = true
    private val oreTags = ArrayList<Tag<Material>>()
    private val oreBlocks = HashSet<Material>()
    private val pendingRestore = HashMap<Location, BlockData>()

    fun configure(config: FileConfiguration) {
        enabled = config.getBoolean("protection.enabled", true)
        protectChests = config.getBoolean("protection.chests", true)
        chestMessage = config.getBoolean("protection.chest-message", true)
        afterClear = when (config.getString("protection.after-clear", "restore")?.lowercase()) {
            "deny" -> AfterClear.DENY
            "allow" -> AfterClear.ALLOW
            else -> AfterClear.RESTORE
        }
        restoreDelayTicks = config.getLong("protection.restore-delay-ticks", 100L).coerceAtLeast(1L)
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
        if (isLocked(anchor)) {
            event.isCancelled = true
            TextUtil.sendColored(event.player, Lang.get("core.protect-no-break"))
            return
        }
        when (afterClear) {
            AfterClear.DENY -> {
                event.isCancelled = true
                TextUtil.sendColored(event.player, Lang.get("core.protect-no-break"))
            }
            AfterClear.ALLOW -> Unit
            AfterClear.RESTORE -> {
                if (noDrop) {
                    event.setDropItems(false)
                    event.setExpToDrop(0)
                }
                scheduleRestore(block)
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!enabled) return
        if (event.player.hasPermission("qinhruins.admin")) return
        val anchor = AnchorManager.containing(event.block.location) ?: return
        if (isLocked(anchor)) event.isCancelled = true
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
        }, restoreDelayTicks)
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

    private fun isLocked(anchor: Anchor): Boolean {
        if (RealmManager.isRealm(anchor.id)) return true
        val blueprint = BlueprintRegistry.get(anchor.templateId) ?: return false
        if (blueprint.objectives.isNotEmpty()) return !ObjectiveTracker.isCompleted(anchor.id)
        if (blueprint.bossGate != null) return anchor.state != AnchorState.CLEARED
        return false
    }
}
