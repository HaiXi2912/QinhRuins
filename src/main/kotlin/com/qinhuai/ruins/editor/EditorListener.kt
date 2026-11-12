package com.qinhuai.ruins.editor

import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.core.RelPos
import com.qinhuai.ruins.generation.AnchorManager
import com.qinhuai.ruins.loot.LootTableRegistry
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

object EditorListener : Listener {

    private val lastNav = HashMap<UUID, Long>()

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (EditorManager.get(event.player) != null) EditorManager.end(event.player)
    }

    /** 只记录玩家手动放置的方块，流体/重力/爆炸等环境变化不算。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val session = EditorManager.get(event.player) ?: return
        recordBlock(session, event.block, event.block.blockData)
    }

    /** 只记录玩家手动挖掉的方块。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val session = EditorManager.get(event.player) ?: return
        recordBlock(session, event.block, null)
    }

    private fun recordBlock(session: EditorSession, block: Block, data: org.bukkit.block.data.BlockData?) {
        val anchor = AnchorManager.get(session.anchorId) ?: return
        if (block.world != anchor.location.world) return
        session.edits[RelPos(
            block.x - anchor.location.blockX,
            block.y - anchor.location.blockY,
            block.z - anchor.location.blockZ,
        )] = data
    }

    @EventHandler
    fun onDrop(event: org.bukkit.event.player.PlayerDropItemEvent) {
        if (EditorManager.isEditing(event.player)) event.isCancelled = true
    }

    @EventHandler
    fun onSwap(event: org.bukkit.event.player.PlayerSwapHandItemsEvent) {
        if (EditorManager.isEditing(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        if (!EditorTools.isEditorItem(item)) return
        val player = event.player
        val session = EditorManager.get(player) ?: return
        val action = EditorTools.actionOf(item) ?: return
        event.isCancelled = true
        event.setUseItemInHand(Event.Result.DENY)
        event.setUseInteractedBlock(Event.Result.DENY)

        val left = event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK
        val right = event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK
        if (!left && !right) return
        val sneak = player.isSneaking

        when (action) {
            EditorAction.MARKER -> if (event.action == Action.LEFT_CLICK_BLOCK) placeMarker(player, session, event.clickedBlock)
            EditorAction.COUNT -> {
                session.defaultCount = stepValue(session.defaultCount, left, sneak, 1)
                EditorManager.refreshTools(player)
            }
            EditorAction.STAGE -> {
                session.defaultStage = stepValue(session.defaultStage, left, false, 1)
                EditorManager.refreshTools(player)
            }
            EditorAction.UNLOCK -> {
                session.defaultUnlockStage = stepValue(session.defaultUnlockStage, left, false, 1)
                EditorManager.refreshTools(player)
            }
            EditorAction.LOOT -> handleLoot(player, session, left, sneak)
            EditorAction.MOB -> if (right && !navThrottled(player)) askMob(player, session)
            EditorAction.GOTO_SPAWN -> if (right && !navThrottled(player)) navigate(player, session, EditorScreen.SPAWN)
            EditorAction.GOTO_CHEST -> if (right && !navThrottled(player)) navigate(player, session, EditorScreen.CHEST)
            EditorAction.GOTO_CORE -> if (right && !navThrottled(player)) navigate(player, session, EditorScreen.CORE)
            EditorAction.GOTO_MECH -> if (right && !navThrottled(player)) navigate(player, session, EditorScreen.MECH)
            EditorAction.BACK -> if (right && !navThrottled(player)) navigate(player, session, EditorScreen.HOME)
            EditorAction.OVERVIEW -> if (right && !navThrottled(player)) EditorGui.openList(player, session)
            EditorAction.MECH_PANEL -> if (right && !navThrottled(player)) openMech(player, session)
            EditorAction.SAVE -> if (right && !navThrottled(player)) EditorCommands.save(player)
            EditorAction.CANCEL -> if (right && !navThrottled(player)) EditorCommands.cancel(player)
        }
    }

    private fun navThrottled(player: Player): Boolean {
        val now = System.currentTimeMillis()
        val last = lastNav[player.uniqueId] ?: 0L
        if (now - last < 250L) return true
        lastNav[player.uniqueId] = now
        return false
    }

    private fun navigate(player: Player, session: EditorSession, screen: EditorScreen) {
        session.screen = screen
        session.mode = EditorTools.screenMode(screen)
        EditorTools.equip(player, session)
    }

    private fun askMob(player: Player, session: EditorSession) {
        ChatInputService.await(player, Lang.get("editor.ask-mob")) { input ->
            session.defaultMob = input
            EditorManager.refreshTools(player)
            Lang.send(player, "editor.default-mob-set", "mob" to input)
        }
    }

    private fun handleLoot(player: Player, session: EditorSession, left: Boolean, sneak: Boolean) {
        if (sneak && left) {
            ChatInputService.await(player, Lang.get("editor.ask-loot")) { input ->
                session.defaultLootTable = input
                EditorManager.refreshTools(player)
                Lang.send(player, "editor.loot-set", "table" to input)
            }
            return
        }
        session.defaultLootTable = cycleLoot(session.defaultLootTable, !left)
        EditorManager.refreshTools(player)
    }

    private fun openMech(player: Player, session: EditorSession) {
        if (session.mechBuilder == null) {
            ChatInputService.await(player, Lang.get("editor.ask-mech-id")) { id ->
                session.mechBuilder = MechBuilder(id)
                EditorManager.refreshTools(player)
                Lang.send(player, "editor.mech-build-start", "id" to id)
                EditorGui.openMech(player, session)
            }
            return
        }
        EditorGui.openMech(player, session)
    }

    private fun placeMarker(player: Player, session: EditorSession, block: Block?) {
        if (block == null) return
        val anchor = AnchorManager.get(session.anchorId)
        if (anchor == null) {
            Lang.send(player, "editor.anchor-lost")
            return
        }
        if (block.world != anchor.location.world) {
            Lang.send(player, "editor.wrong-world")
            return
        }
        val rel = RelPos(
            block.x - anchor.location.blockX,
            block.y - anchor.location.blockY,
            block.z - anchor.location.blockZ,
        )
        TextUtil.sendColored(player, session.addMarker(rel))
        EditorManager.refreshTools(player)
        if (session.mode == EditorMode.MECH) {
            Lang.send(player, "editor.world-coords", "x" to block.x, "y" to block.y, "z" to block.z)
        }
    }

    private fun stepValue(current: Int, increase: Boolean, sneak: Boolean, min: Int): Int {
        val magnitude = if (sneak) 10 else 1
        val delta = if (increase) magnitude else -magnitude
        return (current + delta).coerceAtLeast(min)
    }

    private fun cycleLoot(current: String, backward: Boolean): String {
        val ids = LootTableRegistry.ids()
        if (ids.isEmpty()) return current
        val index = ids.indexOf(current)
        if (index < 0) return ids[0]
        val delta = if (backward) -1 else 1
        val next = ((index + delta) % ids.size + ids.size) % ids.size
        return ids[next]
    }
}
