package com.qinhuai.ruins.editor

import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.QinhRuins
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.generation.AnchorManager
import com.qinhuai.ruins.loot.ChestRegistry
import com.qinhuai.ruins.mechanism.MechanismService
import com.qinhuai.ruins.structure.StructurePatch
import com.qinhuai.ruins.template.BlueprintRegistry
import com.qinhuai.ruins.template.TemplateRegistry
import org.bukkit.entity.Player
import java.io.File

object EditorCommands {

    fun start(player: Player, templateId: String) {
        val template = TemplateRegistry.get(templateId)
        if (template == null) {
            Lang.send(player, "editor.template-not-found", "id" to templateId)
            return
        }
        var anchor = AnchorManager.nearby(player.location, 128.0).firstOrNull { it.templateId == templateId }
        if (anchor == null) {
            val outcome = AnchorManager.spawn(template, player.location.block.location)
            if (outcome.anchor == null) {
                Lang.send(player, "editor.auto-place-failed", "msg" to outcome.result.message)
                return
            }
            anchor = outcome.anchor
            ChestRegistry.rebuild(AnchorManager.all())
            com.qinhuai.ruins.realm.CoreRegistry.rebuild(AnchorManager.all())
            Lang.send(player, "editor.auto-placed", "id" to anchor.id)
        }
        EditorManager.begin(player, templateId, anchor.id, BlueprintRegistry.get(templateId))
        listOf(
            Lang.get("editor.intro-title", "id" to templateId),
            Lang.get("editor.intro-anchor", "id" to anchor.id),
            Lang.get("editor.intro-home"),
            Lang.get("editor.intro-screens"),
            Lang.get("editor.intro-marker"),
            Lang.get("editor.intro-chat"),
            Lang.get("editor.intro-cmd"),
        ).forEach { TextUtil.sendColored(player, it) }
    }

    fun mode(player: Player, modeName: String) {
        val session = EditorManager.get(player) ?: return notEditing(player)
        session.screen = when (modeName.lowercase()) {
            "chest" -> EditorScreen.CHEST
            "core" -> EditorScreen.CORE
            "mech" -> EditorScreen.MECH
            else -> EditorScreen.SPAWN
        }
        session.mode = EditorTools.screenMode(session.screen)
        EditorTools.equip(player, session)
        Lang.send(player, "editor.switched-to", "screen" to session.screen)
    }

    fun set(player: Player, field: String, value: String) {
        val session = EditorManager.get(player) ?: return notEditing(player)
        when (field.lowercase()) {
            "mob" -> session.defaultMob = value
            "count" -> session.defaultCount = value.toIntOrNull()?.coerceAtLeast(1) ?: return invalid(player)
            "stage" -> session.defaultStage = value.toIntOrNull()?.coerceAtLeast(1) ?: return invalid(player)
            "loot" -> session.defaultLootTable = value
            "unlock" -> session.defaultUnlockStage = value.toIntOrNull()?.coerceAtLeast(1) ?: return invalid(player)
            else -> {
                Lang.send(player, "editor.unknown-field")
                return
            }
        }
        Lang.send(player, "editor.default-set", "field" to field, "value" to value)
    }

    fun list(player: Player) {
        val session = EditorManager.get(player) ?: return notEditing(player)
        Lang.send(player, "editor.list-spawns", "count" to session.spawnPoints.size)
        session.spawnPoints.forEach {
            Lang.send(player, "editor.list-spawn-entry",
                "id" to it.id, "mob" to it.mob, "count" to it.count, "stage" to it.stage, "x" to it.pos.x, "y" to it.pos.y, "z" to it.pos.z)
        }
        Lang.send(player, "editor.list-chests", "count" to session.lootChests.size)
        session.lootChests.forEach {
            Lang.send(player, "editor.list-chest-entry",
                "id" to it.id, "table" to it.lootTable, "stage" to it.unlockStage, "x" to it.pos.x, "y" to it.pos.y, "z" to it.pos.z)
        }
        Lang.send(player, "editor.list-mechs", "count" to session.mechanisms.size)
        session.mechanisms.forEach {
            Lang.send(player, "editor.list-mech-entry",
                "id" to it.id, "trigger" to it.trigger.type.name.lowercase(), "actions" to it.actions.joinToString(",") { a -> a.type.name.lowercase() })
        }
        Lang.send(player, "editor.list-cores", "count" to session.cores.size)
        session.cores.forEachIndexed { i, p ->
            Lang.send(player, "editor.list-core-entry", "n" to (i + 1), "x" to p.x, "y" to p.y, "z" to p.z)
        }
    }

    fun remove(player: Player, id: String) {
        val session = EditorManager.get(player) ?: return notEditing(player)
        if (session.remove(id)) {
            Lang.send(player, "editor.marker-removed", "id" to id)
        } else {
            Lang.send(player, "editor.marker-not-found", "id" to id)
        }
    }

    fun save(player: Player) {
        val session = EditorManager.get(player) ?: return notEditing(player)
        session.mechBuilder?.build()?.let { mech ->
            session.mechanisms.removeIf { it.id == mech.id }
            session.mechanisms.add(mech)
            session.mechBuilder = null
            Lang.send(player, "editor.auto-finish-mech", "id" to mech.id)
        }
        val plugin = QinhRuins.instance
        val file = File(plugin.dataFolder, "templates/${session.templateId}/blueprint.yml")
        BlueprintWriter.write(file, session)
        // 编辑器里手动放置/挖掉的方块，增量写回 structure.nbt（不整块重 capture，保留 barrier 占位和其它未改动方块）
        val structureFile = TemplateRegistry.get(session.templateId)?.structureFile ?: "structure.nbt"
        val patched = StructurePatch.apply(
            File(plugin.dataFolder, "templates/${session.templateId}/$structureFile"), session.edits)
        if (patched > 0) Lang.send(player, "editor.structure-patched", "n" to patched)
        BlueprintRegistry.load(File(plugin.dataFolder, "templates"))
        ChestRegistry.rebuild(AnchorManager.all())
        MechanismService.rebuild(AnchorManager.all())
        com.qinhuai.ruins.realm.CoreRegistry.rebuild(AnchorManager.all())
        EditorManager.end(player)
        Lang.send(player, "editor.saved",
            "id" to session.templateId, "spawns" to session.spawnPoints.size, "chests" to session.lootChests.size, "mechs" to session.mechanisms.size)
    }

    fun cancel(player: Player) {
        if (EditorManager.end(player) == null) return notEditing(player)
        Lang.send(player, "editor.cancelled")
    }

    private fun notEditing(player: Player) {
        Lang.send(player, "editor.not-editing")
    }

    private fun invalid(player: Player) {
        Lang.send(player, "editor.invalid-number")
    }
}
