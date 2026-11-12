package com.qinhuai.ruins.generation

import com.qinhuai.ruins.core.Anchor
import com.qinhuai.ruins.core.AnchorState
import com.qinhuai.ruins.core.Durations
import com.qinhuai.ruins.core.RuinTemplate
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.template.TemplateRegistry
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.util.Random

object GenerationDirector {

    private data class WeightEntry(val templateId: String, val weight: Int)

    private val random = Random()
    private var task: BukkitTask? = null
    private var logger: java.util.logging.Logger? = null

    private var enabled = false
    private var chunkChance = 0
    private var liveEnabled = true
    private var liveIntervalTicks = 12000L
    private var liveChance = 0.25
    private var minRadius = 80
    private var maxRadius = 220
    private var regionSize = 512
    private var maxPerRegion = 3
    private var minSpacing = 48
    private var regenMillis = 0L
    private var notifyAdmins = true
    private var suppressAnnounce = false
    private var weights = emptyList<WeightEntry>()

    private const val RECYCLE_SAFE_RADIUS = 64.0

    fun isEnabled(): Boolean = enabled

    fun start(plugin: JavaPlugin, section: ConfigurationSection?) {
        stop()
        logger = plugin.logger
        load(section)
        if (!enabled) return
        if (liveEnabled || regenMillis > 0) {
            task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, liveIntervalTicks, liveIntervalTicks)
        }
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun load(section: ConfigurationSection?) {
        if (section == null) {
            enabled = false
            return
        }
        enabled = section.getBoolean("enabled", false)
        chunkChance = section.getInt("chunk-chance", 0)
        liveEnabled = section.getBoolean("live.enabled", true)
        val intervalSeconds = Durations.seconds(section.getString("live.interval") ?: "10m").coerceAtLeast(20L)
        liveIntervalTicks = intervalSeconds * 20L
        liveChance = section.getDouble("live.chance", 0.25)
        minRadius = section.getInt("live.min-radius", 80).coerceAtLeast(16)
        maxRadius = section.getInt("live.max-radius", 220).coerceAtLeast(minRadius + 1)
        regionSize = section.getInt("density.region-size", 512).coerceAtLeast(16)
        maxPerRegion = section.getInt("density.max-per-region", 3)
        minSpacing = section.getInt("density.min-spacing", 48)
        regenMillis = (section.getDouble("regen-hours", 0.0) * 3600_000.0).toLong()
        notifyAdmins = section.getBoolean("notify-admins", true)
        weights = parseWeights(section)
    }

    private fun parseWeights(section: ConfigurationSection): List<WeightEntry> {
        val result = ArrayList<WeightEntry>()
        for (raw in section.getMapList("weights")) {
            var template: String? = null
            var weight = 1
            for ((key, value) in raw) {
                when (key?.toString()) {
                    "template" -> template = value?.toString()
                    "weight" -> weight = (value as? Number)?.toInt() ?: 1
                }
            }
            val id = template ?: continue
            if (weight > 0) result.add(WeightEntry(id, weight))
        }
        return result
    }

    fun onNewChunk(world: World, chunkX: Int, chunkZ: Int) {
        if (!enabled || chunkChance <= 0) return
        if (random.nextInt(chunkChance) != 0) return
        attempt(world, chunkX * 16 + 8, chunkZ * 16 + 8, chunkX, chunkZ)
    }

    private fun tick() {
        if (!enabled) return
        if (liveEnabled) liveTick()
        if (regenMillis > 0) regenTick()
    }

    private fun liveTick() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (random.nextDouble() > liveChance) continue
            val world = player.world
            val angle = random.nextDouble() * Math.PI * 2
            val dist = minRadius + random.nextDouble() * (maxRadius - minRadius)
            val x = player.location.blockX + (Math.cos(angle) * dist).toInt()
            val z = player.location.blockZ + (Math.sin(angle) * dist).toInt()
            val chunkX = x shr 4
            val chunkZ = z shr 4
            if (!world.isChunkLoaded(chunkX, chunkZ)) continue
            attempt(world, x, z, chunkX, chunkZ)
        }
    }

    private fun attempt(world: World, x: Int, z: Int, chunkX: Int, chunkZ: Int) {
        if (!densityOk(world, x, z)) return
        val template = pickTemplate(world) ?: return
        NaturalGenerator.enqueue(world.name, chunkX, chunkZ, template.id)
    }

    private fun regenTick() {
        val now = System.currentTimeMillis()
        val due = AnchorManager.all().firstOrNull {
            it.state == AnchorState.CLEARED &&
                it.clearedAt in 1 until (now - regenMillis) &&
                it.location.world != null &&
                !playersNear(it.center())
        } ?: return
        val result = AnchorRecycler.recycle(due.id)
        if (result.found) {
            logger?.info("遗迹 ${due.id} 已消退再生：附近无玩家、通关已超 ${regenMillis / 3600_000L} 小时，名额已腾出")
        }
    }

    private fun playersNear(loc: Location): Boolean {
        val world = loc.world ?: return false
        val r2 = RECYCLE_SAFE_RADIUS * RECYCLE_SAFE_RADIUS
        return Bukkit.getOnlinePlayers().any { it.world == world && it.location.distanceSquared(loc) <= r2 }
    }

    fun globalMinSpacing(): Int = if (enabled) minSpacing else 0

    fun pickForChunk(world: World): RuinTemplate? = pickTemplate(world)

    fun densityOk(world: World, x: Int, z: Int): Boolean {
        if (!enabled) return true
        if (maxPerRegion > 0) {
            val rx = Math.floorDiv(x, regionSize)
            val rz = Math.floorDiv(z, regionSize)
            var count = 0
            for (anchor in AnchorManager.all()) {
                if (anchor.state == AnchorState.RECYCLED) continue
                if (anchor.location.world?.name != world.name) continue
                if (Math.floorDiv(anchor.location.blockX, regionSize) == rx &&
                    Math.floorDiv(anchor.location.blockZ, regionSize) == rz
                ) {
                    count++
                    if (count >= maxPerRegion) return false
                }
            }
        }
        return true
    }

    private fun pickTemplate(world: World): RuinTemplate? {
        val eligible = effectiveWeights()
            .mapNotNull { entry -> TemplateRegistry.get(entry.templateId)?.let { it to entry.weight } }
            .filter { (tpl, _) -> tpl.generation.enabled && com.qinhuai.ruins.template.GenerationProfiles.eligibleWorld(tpl.generation, world) }
        if (eligible.isEmpty()) return null
        val topPriority = eligible.minOf { it.first.generation.priority }
        val table = eligible.filter { it.first.generation.priority == topPriority }
        val total = table.sumOf { it.second }
        if (total <= 0) return null
        var roll = random.nextInt(total)
        for ((tpl, weight) in table) {
            if (roll < weight) return tpl
            roll -= weight
        }
        return table.last().first
    }

    private fun effectiveWeights(): List<WeightEntry> {
        if (weights.isNotEmpty()) return weights
        return TemplateRegistry.all()
            .filter { it.generation.enabled }
            .map { tpl ->
                val weight = if (tpl.generationWeight > 0) {
                    tpl.generationWeight
                } else {
                    val gen = tpl.generation
                    if (gen.probabilityDenominator > 0) {
                        ((gen.probabilityNumerator.toDouble() / gen.probabilityDenominator) * 100_000).toInt().coerceAtLeast(1)
                    } else {
                        1
                    }
                }
                WeightEntry(tpl.id, weight)
            }
    }

    fun announceSpawn(template: RuinTemplate, anchor: Anchor) {
        val loc = anchor.location
        logger?.info("自然生成 ${template.id} @ ${loc.world?.name} ${loc.blockX},${loc.blockY},${loc.blockZ} (${anchor.id})")
        if (!notifyAdmins || suppressAnnounce) return
        val msg = Lang.get(
            "cmd.gen-announce",
            "name" to template.display,
            "world" to (loc.world?.name ?: ""),
            "x" to loc.blockX, "y" to loc.blockY, "z" to loc.blockZ,
            "id" to anchor.id,
        )
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("qinhruins.admin")) {
                val legacy = LegacyComponentSerializer.legacySection()
                val tp = legacy.deserialize(Lang.get("cmd.gen-announce-tp"))
                    .clickEvent(ClickEvent.runCommand("/qr tp ${anchor.id}"))
                    .hoverEvent(HoverEvent.showText(legacy.deserialize(Lang.get("cmd.gen-announce-tp-hover", "id" to anchor.id))))
                player.sendMessage(legacy.deserialize(msg).append(tp))
            }
        }
    }

    fun debugSpawnNear(player: Player, attempts: Int): String {
        if (!enabled) return Lang.get("cmd.gen-director-disabled")
        val world = player.world
        var ok = 0
        var dDensity = 0
        var dPick = 0
        var dPlace = 0
        repeat(attempts) {
            val angle = random.nextDouble() * Math.PI * 2
            val dist = minRadius + random.nextDouble() * (maxRadius - minRadius)
            val x = player.location.blockX + (Math.cos(angle) * dist).toInt()
            val z = player.location.blockZ + (Math.sin(angle) * dist).toInt()
            val cx = x shr 4
            val cz = z shr 4
            if (!world.isChunkLoaded(cx, cz)) world.getChunkAt(cx, cz)
            if (!densityOk(world, x, z)) {
                dDensity++
                return@repeat
            }
            val tpl = pickTemplate(world) ?: run {
                dPick++
                return@repeat
            }
            if (AnchorPlacer.tryPlace(world, cx, cz, tpl, random)) ok++ else dPlace++
        }
        return Lang.get(
            "cmd.gen-debug-result",
            "attempts" to attempts, "ok" to ok,
            "density" to dDensity, "pick" to dPick, "place" to dPlace,
        )
    }

    fun scatter(plugin: JavaPlugin, player: Player, radius: Int, count: Int): Boolean {
        if (!enabled) {
            player.sendMessage(Lang.get("cmd.scatter-disabled"))
            return false
        }
        val world = player.world
        val baseX = player.location.blockX
        val baseZ = player.location.blockZ
        val maxTries = (count * 15).coerceAtLeast(30)
        suppressAnnounce = true
        object : BukkitRunnable() {
            var placed = 0
            var tries = 0
            override fun run() {
                var n = 0
                while (n < 2 && placed < count && tries < maxTries) {
                    n++
                    tries++
                    val angle = random.nextDouble() * Math.PI * 2
                    val dist = Math.sqrt(random.nextDouble()) * radius
                    val x = baseX + (Math.cos(angle) * dist).toInt()
                    val z = baseZ + (Math.sin(angle) * dist).toInt()
                    val cx = x shr 4
                    val cz = z shr 4
                    if (!world.isChunkLoaded(cx, cz)) world.getChunkAt(cx, cz)
                    if (!densityOk(world, x, z)) continue
                    val tpl = pickTemplate(world) ?: continue
                    if (AnchorPlacer.tryPlace(world, cx, cz, tpl, random)) placed++
                }
                if (placed >= count || tries >= maxTries) {
                    cancel()
                    suppressAnnounce = false
                    player.sendMessage(Lang.get("cmd.scatter-done", "placed" to placed, "count" to count, "tries" to tries, "radius" to radius))
                }
            }
        }.runTaskTimer(plugin, 1L, 2L)
        return true
    }
}
