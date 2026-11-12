package com.qinhuai.ruins.loot

import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.data.WeeklyLimitStore
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.time.LocalDate

/**
 * 每周开箱限额：遗迹 / BOSS 箱 / 老虎机 / 原版箱 四个独立作用域。
 * 支持权限加成、节假日临时加成、超额开补偿箱。
 */
object WeeklyLimitService {

    const val SCOPE_RUIN = "ruin"
    const val SCOPE_BOSS = "boss"
    const val SCOPE_SLOT = "slot"
    const val SCOPE_VANILLA = "vanilla"

    private data class TempBonus(
        val scope: String,
        val start: LocalDate,
        val end: LocalDate,
        val amount: Int,
        val reason: String,
    )

    private var enabled = false
    private var countVanilla = false
    private val baseLimits = HashMap<String, Int>()
    private val permBonuses = HashMap<String, MutableList<Pair<String, Int>>>()
    private val tempBonuses = ArrayList<TempBonus>()
    private val scopeTables = HashMap<String, MutableList<String>>()

    private var mode = "chest"
    private var compTable = "compensation"
    private var compTitle = "&6本周次数已用完 · 安慰奖"
    private var compRows = 3
    private var oncePerChest = true
    private var message = "&e本周开箱次数已用完，这是安慰奖"
    private val commands = ArrayList<String>()
    private val items = ArrayList<Pair<Material, Int>>()

    fun configure(config: FileConfiguration, dataFolder: File) {
        val sec = config.getConfigurationSection("weekly-limit")
        enabled = sec?.getBoolean("enabled", false) ?: false
        countVanilla = sec?.getBoolean("count-vanilla", false) ?: false

        baseLimits.clear()
        permBonuses.clear()
        tempBonuses.clear()
        scopeTables.clear()

        baseLimits[SCOPE_RUIN] = sec?.getInt("scopes.ruin.per-week", 16) ?: 16
        baseLimits[SCOPE_BOSS] = sec?.getInt("scopes.boss.per-week", 3) ?: 3
        baseLimits[SCOPE_SLOT] = sec?.getInt("scopes.slot.per-week", 3) ?: 3
        baseLimits[SCOPE_VANILLA] = sec?.getInt("scopes.vanilla.per-week", 5) ?: 5

        for (scope in baseLimits.keys) {
            val permSec = sec?.getConfigurationSection("scopes.$scope.permission-bonus")
            if (permSec != null) {
                for (node in permSec.getKeys(false)) {
                    permBonuses.getOrPut(scope) { ArrayList() }.add(node to permSec.getInt(node, 0))
                }
            }
            for (raw in sec?.getMapList("scopes.$scope.temporary-bonus") ?: emptyList<Map<*, *>>()) {
                val start = runCatching { LocalDate.parse(raw["start"].toString()) }.getOrNull() ?: continue
                val end = runCatching { LocalDate.parse(raw["end"].toString()) }.getOrNull() ?: start
                val amount = (raw["amount"] as? Number)?.toInt() ?: continue
                tempBonuses.add(TempBonus(scope, start, end, amount, raw["reason"]?.toString() ?: ""))
            }
            val tables = sec?.getStringList("scopes.$scope.tables") ?: emptyList()
            if (tables.isNotEmpty()) scopeTables[scope] = tables.toMutableList()
        }

        mode = (sec?.getString("over-limit.mode") ?: "chest").trim().lowercase()
        compTable = sec?.getString("over-limit.table") ?: "compensation"
        compTitle = sec?.getString("over-limit.title") ?: "&6本周次数已用完 · 安慰奖"
        compRows = (sec?.getInt("over-limit.rows", 3) ?: 3).coerceIn(1, 6)
        oncePerChest = sec?.getBoolean("over-limit.once-per-chest", true) ?: true
        message = sec?.getString("over-limit.message") ?: "&e本周开箱次数已用完，这是安慰奖"
        commands.clear()
        commands.addAll(sec?.getStringList("over-limit.commands") ?: emptyList())
        items.clear()
        for (raw in sec?.getStringList("over-limit.items") ?: emptyList()) {
            val parts = raw.split(":")
            val material = Material.matchMaterial(parts[0].trim()) ?: continue
            items.add(material to (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1).coerceAtLeast(1))
        }

        WeeklyLimitStore.init(
            File(dataFolder, "weekly_limits.yml"),
            sec?.getString("reset-day") ?: "MONDAY",
            sec?.getInt("reset-hour", 4) ?: 4,
        )
    }

    fun isEnabled(): Boolean = enabled

    /** 按战利品表名判定作用域；没配 tables 的默认算遗迹箱。 */
    fun scopeOfTable(table: String?): String {
        if (table != null) {
            for ((scope, tables) in scopeTables) {
                if (tables.any { it.equals(table, ignoreCase = true) }) return scope
            }
        }
        return SCOPE_RUIN
    }

    fun limitFor(player: Player, scope: String): Int {
        var limit = baseLimits[scope] ?: 0
        for ((perm, bonus) in permBonuses[scope] ?: emptyList()) {
            if (player.hasPermission(perm)) limit += bonus
        }
        val today = LocalDate.now()
        for (t in tempBonuses) {
            if (t.scope == scope && !today.isBefore(t.start) && !today.isAfter(t.end)) limit += t.amount
        }
        return limit.coerceAtLeast(0)
    }

    fun used(player: Player, scope: String): Int = WeeklyLimitStore.used(player.uniqueId, scope)

    fun remaining(player: Player, scope: String): Int =
        (limitFor(player, scope) - used(player, scope)).coerceAtLeast(0)

    fun resetDate(): String = WeeklyLimitStore.currentWeekKey()

    fun tryConsume(player: Player, scope: String): Boolean {
        if (!enabled) return true
        if (scope == SCOPE_VANILLA && !countVanilla) return true
        return WeeklyLimitStore.tryConsume(player.uniqueId, scope, limitFor(player, scope))
    }

    /** 超额：开补偿箱 / 给物品 / 只提示，同一箱子每周只补一次。 */
    fun deny(player: Player, scope: String, chestKey: String) {
        if (oncePerChest && WeeklyLimitStore.hasCompensated(player.uniqueId, scope, chestKey)) {
            TextUtil.sendColored(player, "&7这个箱子本周已经领过补偿了")
            return
        }
        if (oncePerChest) WeeklyLimitStore.markCompensated(player.uniqueId, scope, chestKey)
        if (message.isNotBlank()) TextUtil.sendColored(player, message)
        when (mode) {
            "chest" -> openCompensation(player)
            "reward" -> {
                for (cmd in commands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.name))
                }
                for ((material, amount) in items) {
                    val overflow = player.inventory.addItem(ItemStack(material, amount))
                    overflow.values.forEach { player.world.dropItemNaturally(player.location, it) }
                }
            }
        }
    }

    private fun openCompensation(player: Player) {
        val table = LootTableRegistry.get(compTable) ?: return
        val loot = LootService.roll(table, player, false)
        val size = compRows * 9
        val inventory = Bukkit.createInventory(null, size, TextUtil.toComponent(compTitle))
        loot.take(size).forEachIndexed { index, item -> inventory.setItem(index, item) }
        player.openInventory(inventory)
    }

    /** PAPI：%qinhruins_weekly_used% / _limit% / _remaining% / _reset%，可加 boss_/slot_/vanilla_ 前缀。 */
    fun placeholder(player: Player, params: String): String? {
        val p = params.lowercase()
        if (!p.startsWith("weekly")) return null
        val scope = when {
            p.contains("boss") -> SCOPE_BOSS
            p.contains("slot") -> SCOPE_SLOT
            p.contains("vanilla") -> SCOPE_VANILLA
            else -> SCOPE_RUIN
        }
        return when {
            p.endsWith("_used") -> used(player, scope).toString()
            p.endsWith("_limit") -> limitFor(player, scope).toString()
            p.endsWith("_remaining") -> remaining(player, scope).toString()
            p.endsWith("_reset") -> resetDate()
            else -> null
        }
    }
}
