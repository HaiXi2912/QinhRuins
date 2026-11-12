package com.qinhuai.ruins.data

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 每周开箱计数，按玩家 + 作用域（ruin / vanilla）分别统计。
 * 周键按 reset-day + reset-hour 计算，访问时懒重置。
 */
object WeeklyLimitStore {

    private lateinit var file: File
    private val counts = HashMap<String, Int>()
    private val weeks = HashMap<String, String>()
    private val compensated = HashMap<String, MutableSet<String>>()

    private var resetDay: DayOfWeek = DayOfWeek.MONDAY
    private var resetHour: Int = 4
    private var zone: ZoneId = ZoneId.systemDefault()

    fun init(file: File, resetDayName: String, resetHourValue: Int, zoneId: ZoneId = ZoneId.systemDefault()) {
        this.file = file
        this.resetDay = runCatching { DayOfWeek.valueOf(resetDayName.trim().uppercase()) }.getOrDefault(DayOfWeek.MONDAY)
        this.resetHour = resetHourValue.coerceIn(0, 23)
        this.zone = zoneId
        load()
    }

    fun currentWeekKey(): String = weekStart(LocalDateTime.now(zone)).toString()

    fun used(player: UUID, scope: String): Int {
        val key = key(player, scope)
        rollover(key)
        return counts[key] ?: 0
    }

    fun tryConsume(player: UUID, scope: String, limit: Int): Boolean {
        if (limit <= 0) return true
        val key = key(player, scope)
        rollover(key)
        val used = counts[key] ?: 0
        if (used >= limit) return false
        counts[key] = used + 1
        save()
        return true
    }

    fun hasCompensated(player: UUID, scope: String, chestKey: String): Boolean {
        val key = key(player, scope)
        rollover(key)
        return compensated[key]?.contains(chestKey) == true
    }

    fun markCompensated(player: UUID, scope: String, chestKey: String) {
        val key = key(player, scope)
        rollover(key)
        val set = compensated.getOrPut(key) { HashSet() }
        if (set.add(chestKey)) save()
    }

    private fun key(player: UUID, scope: String): String = "$player|$scope"

    private fun rollover(key: String) {
        val week = currentWeekKey()
        if (weeks[key] == week) return
        weeks[key] = week
        counts[key] = 0
        compensated.remove(key)
        save()
    }

    private fun weekStart(now: LocalDateTime): LocalDate {
        val today = now.toLocalDate()
        val diff = (today.dayOfWeek.value - resetDay.value + 7) % 7
        var start = today.minusDays(diff.toLong())
        if (start == today && now.hour < resetHour) start = start.minusWeeks(1)
        return start
    }

    private fun load() {
        counts.clear()
        weeks.clear()
        compensated.clear()
        if (!::file.isInitialized || !file.exists()) return
        val yml = YamlConfiguration.loadConfiguration(file)
        val players = yml.getConfigurationSection("players") ?: return
        for (rawUuid in players.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: continue
            val scopes = players.getConfigurationSection(rawUuid) ?: continue
            for (scope in scopes.getKeys(false)) {
                val key = key(uuid, scope)
                weeks[key] = scopes.getString("$scope.week") ?: continue
                counts[key] = scopes.getInt("$scope.count", 0)
                compensated[key] = scopes.getStringList("$scope.compensated").toMutableSet()
            }
        }
    }

    private fun save() {
        if (!::file.isInitialized) return
        val yml = YamlConfiguration()
        for ((key, week) in weeks) {
            val sep = key.indexOf('|')
            if (sep <= 0) continue
            val uuid = key.substring(0, sep)
            val scope = key.substring(sep + 1)
            yml.set("players.$uuid.$scope.week", week)
            yml.set("players.$uuid.$scope.count", counts[key] ?: 0)
            yml.set("players.$uuid.$scope.compensated", compensated[key]?.toList() ?: emptyList<String>())
        }
        runCatching {
            file.parentFile?.mkdirs()
            yml.save(file)
        }
    }
}
