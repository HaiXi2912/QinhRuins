package com.qinhuai.ruins.editor

import com.qinhuai.ruins.core.LootChest
import com.qinhuai.ruins.core.Mechanism
import com.qinhuai.ruins.core.Objectives
import com.qinhuai.ruins.core.RelPos
import com.qinhuai.ruins.core.SpawnPoint
import com.qinhuai.ruins.core.VariantSlot
import com.qinhuai.ruins.lang.Lang

enum class EditorMode { SPAWN, CHEST, MECH, CORE }

enum class EditorScreen { HOME, SPAWN, CHEST, CORE, MECH }

class EditorSession(
    val templateId: String,
    val anchorId: String,
    val spawnPoints: MutableList<SpawnPoint>,
    val lootChests: MutableList<LootChest>,
    val objectives: Objectives,
    val mechanisms: MutableList<Mechanism>,
    val cores: MutableList<RelPos>,
    val variants: List<VariantSlot> = emptyList(),
    val spawnCommands: List<com.qinhuai.ruins.core.SpawnCommand> = emptyList(),
) {
    var mechBuilder: MechBuilder? = null

    /** 编辑期间玩家手动放置/挖掘的方块（相对坐标 → 新方块数据，null 表示挖掉），保存时增量写入 structure.nbt。 */
    val edits: LinkedHashMap<RelPos, org.bukkit.block.data.BlockData?> = LinkedHashMap()

    var screen: EditorScreen = EditorScreen.HOME
    var mode: EditorMode = EditorMode.SPAWN
    var defaultMob: String = "ZOMBIE"
    var defaultCount: Int = 1
    var defaultStage: Int = 1
    var defaultLootTable: String = "default"
    var defaultUnlockStage: Int = 1

    private var spawnSeq: Int = spawnPoints.size
    private var chestSeq: Int = lootChests.size

    fun addMarker(rel: RelPos): String {
        return when (mode) {
            EditorMode.SPAWN -> {
                val id = "sp${++spawnSeq}"
                spawnPoints.add(SpawnPoint(id, rel, defaultMob, defaultCount, 1, defaultStage))
                Lang.get("editor.marker-spawn-added", "id" to id, "mob" to defaultMob, "count" to defaultCount, "stage" to defaultStage, "x" to rel.x, "y" to rel.y, "z" to rel.z)
            }
            EditorMode.CHEST -> {
                val id = "c${++chestSeq}"
                lootChests.add(LootChest(id, rel, defaultLootTable, true, true, defaultUnlockStage))
                Lang.get("editor.marker-chest-added", "id" to id, "table" to defaultLootTable, "stage" to defaultUnlockStage, "x" to rel.x, "y" to rel.y, "z" to rel.z)
            }
            EditorMode.MECH -> {
                val builder = mechBuilder
                    ?: return Lang.get("editor.marker-need-mech-builder")
                builder.points.add(rel)
                Lang.get("editor.marker-point-added", "n" to builder.points.size, "x" to rel.x, "y" to rel.y, "z" to rel.z)
            }
            EditorMode.CORE -> {
                cores.add(rel)
                Lang.get("editor.marker-core-added", "n" to cores.size, "x" to rel.x, "y" to rel.y, "z" to rel.z)
            }
        }
    }

    fun remove(id: String): Boolean =
        spawnPoints.removeIf { it.id == id } ||
            lootChests.removeIf { it.id == id } ||
            mechanisms.removeIf { it.id == id } ||
            removeCore(id)

    private fun removeCore(id: String): Boolean {
        val index = id.removePrefix("core").toIntOrNull()?.minus(1) ?: return false
        if (index !in cores.indices) return false
        cores.removeAt(index)
        return true
    }
}
