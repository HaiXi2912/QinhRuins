package com.qinhuai.ruins.template

import com.qinhuai.ruins.core.FitRule
import com.qinhuai.ruins.core.GenerationRule
import com.qinhuai.ruins.core.SpawnRegion
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger

object GenerationProfiles {

    val DEFAULT = GenerationRule(
        enabled = false,
        worlds = emptyList(),
        environments = emptyList(),
        biomes = emptyList(),
        probabilityNumerator = 1,
        probabilityDenominator = 1000,
        priority = 0,
        spawnChance = 1.0,
        minDistanceOthers = 0,
        minDistanceSame = 0,
        yMode = "surface",
        yBandMin = 0,
        yBandMax = 0,
        yOffset = 0,
        heightmap = null,
        whitelistGround = emptyList(),
        blacklistGround = emptyList(),
        flatnessRadius = 0,
        flatnessMaxVariance = 0,
        flatnessMaxErrors = 0,
        placementAttempts = 1,
        spawnInWater = true,
        spawnInLava = false,
        spawnInVoid = true,
        spawnRegion = null,
        fit = null,
    )

    private val profiles = LinkedHashMap<String, GenerationRule>()

    fun load(dir: File, logger: Logger): Int {
        profiles.clear()
        if (!dir.exists()) {
            dir.mkdirs()
            return 0
        }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".yml") } ?: return 0
        for (file in files) {
            val name = file.nameWithoutExtension
            runCatching { read(YamlConfiguration.loadConfiguration(file), DEFAULT) }
                .onSuccess { profiles[name] = it }
                .onFailure { logger.warning("加载放置档案失败 ${file.name}: ${it.message}") }
        }
        return profiles.size
    }

    fun get(name: String): GenerationRule? = profiles[name]

    fun ids(): List<String> = profiles.keys.toList()

    fun read(section: ConfigurationSection?, base: GenerationRule): GenerationRule {
        if (section == null) return base
        val yMode = when {
            section.contains("layer") -> section.getString("layer") ?: base.yMode
            section.contains("y") -> section.getString("y") ?: base.yMode
            else -> base.yMode
        }
        return GenerationRule(
            enabled = if (section.contains("enabled")) section.getBoolean("enabled") else base.enabled,
            worlds = if (section.contains("worlds")) section.getStringList("worlds") else base.worlds,
            environments = if (section.contains("environments")) section.getStringList("environments") else base.environments,
            biomes = if (section.contains("biomes")) section.getStringList("biomes") else base.biomes,
            probabilityNumerator = if (section.contains("probability.numerator")) section.getInt("probability.numerator") else base.probabilityNumerator,
            probabilityDenominator = if (section.contains("probability.denominator")) section.getInt("probability.denominator") else base.probabilityDenominator,
            priority = if (section.contains("priority")) section.getInt("priority") else base.priority,
            spawnChance = if (section.contains("spawn-chance")) section.getDouble("spawn-chance") else base.spawnChance,
            minDistanceOthers = if (section.contains("min-distance-others")) section.getInt("min-distance-others") else base.minDistanceOthers,
            minDistanceSame = if (section.contains("min-distance-same")) section.getInt("min-distance-same") else base.minDistanceSame,
            yMode = yMode,
            yBandMin = if (section.contains("y-band.min")) section.getInt("y-band.min") else base.yBandMin,
            yBandMax = if (section.contains("y-band.max")) section.getInt("y-band.max") else base.yBandMax,
            yOffset = if (section.contains("y-offset")) section.getInt("y-offset") else base.yOffset,
            heightmap = if (section.contains("heightmap")) section.getString("heightmap") else base.heightmap,
            whitelistGround = if (section.contains("whitelist-ground")) section.getStringList("whitelist-ground") else base.whitelistGround,
            blacklistGround = if (section.contains("blacklist-ground")) section.getStringList("blacklist-ground") else base.blacklistGround,
            flatnessRadius = if (section.contains("flatness.radius")) section.getInt("flatness.radius") else base.flatnessRadius,
            flatnessMaxVariance = if (section.contains("flatness.max-variance")) section.getInt("flatness.max-variance") else base.flatnessMaxVariance,
            flatnessMaxErrors = if (section.contains("flatness.max-errors")) section.getInt("flatness.max-errors") else base.flatnessMaxErrors,
            placementAttempts = if (section.contains("placement-attempts")) section.getInt("placement-attempts").coerceIn(1, 16) else base.placementAttempts,
            spawnInWater = if (section.contains("spawn-in-water")) section.getBoolean("spawn-in-water") else base.spawnInWater,
            spawnInLava = if (section.contains("spawn-in-lava")) section.getBoolean("spawn-in-lava") else base.spawnInLava,
            spawnInVoid = if (section.contains("spawn-in-void")) section.getBoolean("spawn-in-void") else base.spawnInVoid,
            spawnRegion = readRegion(section) ?: base.spawnRegion,
            fit = readFit(section) ?: base.fit,
        )
    }

    private fun readFit(section: ConfigurationSection): FitRule? {
        val s = section.getConfigurationSection("fit") ?: return null
        return FitRule(
            mode = (s.getString("mode") ?: "shell").trim().lowercase(),
            minScore = s.getDouble("min-score", 0.0).coerceIn(0.0, 1.0),
            scanRadius = s.getInt("scan-radius", 16).coerceIn(2, 48),
            requireBlocks = s.getStringList("require-blocks"),
            minBlockCount = s.getInt("min-block-count", 1).coerceAtLeast(1),
            forbidBlocks = s.getStringList("forbid-blocks"),
        )
    }

    private fun readRegion(section: ConfigurationSection): SpawnRegion? {
        val s = section.getConfigurationSection("spawn-region") ?: return null
        return SpawnRegion(s.getInt("min-x"), s.getInt("min-z"), s.getInt("max-x"), s.getInt("max-z"), s.getBoolean("exclude", false))
    }

    fun eligibleWorld(gen: GenerationRule, world: World): Boolean {
        if (gen.worlds.isNotEmpty() && gen.worlds.none { it.equals(world.name, ignoreCase = true) }) return false
        if (gen.environments.isNotEmpty()) {
            val env = world.environment.name
            if (gen.environments.none { normalizeEnv(it) == env }) return false
        }
        return true
    }

    private fun normalizeEnv(raw: String): String = when (raw.trim().uppercase()) {
        "OVERWORLD", "NORMAL" -> "NORMAL"
        "NETHER", "THE_NETHER" -> "NETHER"
        "END", "THE_END" -> "THE_END"
        else -> raw.trim().uppercase()
    }
}
