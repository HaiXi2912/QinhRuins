package com.qinhuai.ruins.template

import com.qinhuai.ruins.core.Durations
import com.qinhuai.ruins.core.EntryRule
import com.qinhuai.ruins.core.FoundationConfig
import com.qinhuai.ruins.core.GenerationRule
import com.qinhuai.ruins.core.GuideItemConfig
import com.qinhuai.ruins.core.RuinTemplate
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.core.SessionRule
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger

object TemplateRegistry {

    private val templates = LinkedHashMap<String, RuinTemplate>()
    private val AUTO_MATERIAL_TOKENS = setOf("auto", "match", "match-terrain", "terrain", "*")

    fun load(templatesDir: File, logger: Logger): Int {
        templates.clear()
        if (!templatesDir.exists()) {
            templatesDir.mkdirs()
            return 0
        }
        val dirs = templatesDir.listFiles { f -> f.isDirectory } ?: return 0
        for (dir in dirs) {
            if (dir.name.startsWith("_") || dir.name.startsWith(".")) continue
            val file = File(dir, "template.yml")
            if (!file.exists()) continue
            runCatching { parse(dir.name, file, logger) }
                .onSuccess { templates[it.id] = it }
                .onFailure { logger.warning("加载遗迹模板失败 ${dir.name}: ${it.message}") }
        }
        return templates.size
    }

    private fun parse(folderName: String, file: File, logger: Logger): RuinTemplate {
        val yml = YamlConfiguration.loadConfiguration(file)
        val id = yml.getString("id") ?: folderName
        val genSection = yml.getConfigurationSection("generation")
        val profileName = genSection?.getString("profile")?.takeIf { it.isNotBlank() }
        val base = if (profileName != null) {
            GenerationProfiles.get(profileName) ?: run {
                logger.warning("遗迹模板 $id 引用了不存在的放置档案 '$profileName'，已回退默认")
                GenerationProfiles.DEFAULT
            }
        } else {
            GenerationProfiles.DEFAULT
        }
        val generation = GenerationProfiles.read(genSection, base)
        if (!com.qinhuai.ruins.generation.AnchorPlacer.isValidYMode(generation.yMode)) {
            logger.warning("遗迹模板 $id 的 y/layer 值 '${generation.yMode}' 无法识别，将回退 surface（可用: surface/underground/sky/ocean-surface/seabed/固定数字/+N/-N/+[n;m]）")
        }
        val generationWeight = genSection?.getInt("weight", 0) ?: 0
        val guideItem = GuideItemConfig(
            ref = (yml.getString("guide_item") ?: yml.getString("guide-item.ref"))?.takeIf { it.isNotBlank() },
            material = yml.getString("guide-item.material") ?: "COMPASS",
            name = yml.getString("guide-item.name"),
            lore = yml.getStringList("guide-item.lore"),
            modelData = if (yml.contains("guide-item.model-data")) yml.getInt("guide-item.model-data") else null,
            consumable = yml.getBoolean("guide-item.consumable", false),
        )
        val entry = EntryRule(
            minPlayers = yml.getInt("entry.min-players", 1),
            maxPlayers = yml.getInt("entry.max-players", 10),
            requiredClasses = yml.getStringList("entry.required-classes"),
            minGrowth = yml.getDouble("entry.min-growth", 0.0),
            cost = yml.getString("entry.cost") ?: "",
            cooldownSeconds = Durations.seconds(yml.getString("entry.cooldown")),
        )
        val session = SessionRule(
            mode = yml.getString("session.mode") ?: "shared-anchor",
            timeLimitSeconds = Durations.seconds(yml.getString("session.time-limit") ?: "30m"),
            cleanupOnEmptySeconds = Durations.seconds(yml.getString("session.cleanup-on-empty") ?: "60s"),
        )
        val foundation = parseFoundation(yml)
        return RuinTemplate(
            id = id,
            display = (yml.getString("display") ?: id).replace('&', '§'),
            icon = yml.getString("icon") ?: "FILLED_MAP",
            structureFile = yml.getString("structure.file") ?: "structure.nbt",
            rotationMode = yml.getString("structure.rotation") ?: "none",
            targetMask = yml.getStringList("structure.target-mask"),
            sourceSkip = yml.getStringList("structure.source-skip"),
            sourceMask = yml.getStringList("structure.source-mask"),
            replaceBlocks = parseReplaceBlocks(yml),
            generation = generation,
            generationWeight = generationWeight,
            guideItem = guideItem,
            entry = entry,
            session = session,
            respawnSeconds = Durations.seconds(yml.getString("respawn")),
            foundation = foundation,
            palette = yml.getString("structure.palette")?.takeIf { it.isNotBlank() },
            containerTable = yml.getString("loot.container-table")?.takeIf { it.isNotBlank() },
            containerTables = parseContainerTables(yml),
            vesselMode = (yml.getString("loot.mode") ?: "per-player").trim().lowercase(),
            clearRewardTable = yml.getString("reward.clear-table")?.takeIf { it.isNotBlank() },
            titles = com.qinhuai.ruins.core.RuinTitles(
                enterNew = yml.getString("titles.enter-new") ?: Lang.get("core.title-enter-new"),
                clear = yml.getString("titles.clear") ?: Lang.get("core.clear-title"),
                enterExplored = yml.getString("titles.enter-explored") ?: Lang.get("core.title-enter-explored"),
            ),
        )
    }

    private fun parseContainerTables(yml: YamlConfiguration): Map<String, String> {
        val section = yml.getConfigurationSection("loot.container-tables") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        section.getKeys(false).forEach { key ->
            section.getString(key)?.takeIf { it.isNotBlank() }?.let { map[key.uppercase()] = it }
        }
        return map
    }

    private fun parseReplaceBlocks(yml: YamlConfiguration): Map<String, String> {
        val section = yml.getConfigurationSection("structure.replace-blocks") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        section.getKeys(false).forEach { key -> section.getString(key)?.let { map[key] = it } }
        return map
    }

    private fun parseFoundation(yml: YamlConfiguration): FoundationConfig {
        var default: String? = null
        val biomeMap = HashMap<String, String>()
        val section = yml.getConfigurationSection("foundation.materials")
        var matchTerrain = yml.getBoolean("foundation.match-terrain", false)
        section?.getKeys(false)?.forEach { keyGroup ->
            val material = section.getString(keyGroup) ?: return@forEach
            keyGroup.split(",").forEach { key ->
                val trimmed = key.trim()
                if (trimmed.equals("default", ignoreCase = true)) {
                    if (material.trim().lowercase() in AUTO_MATERIAL_TOKENS) matchTerrain = true else default = material
                } else biomeMap[trimmed.uppercase()] = material
            }
        }
        return FoundationConfig(
            enabled = yml.getBoolean("foundation.enabled", false),
            maxDepth = yml.getInt("foundation.max-depth", 16).coerceIn(1, 256),
            ignoreWater = yml.getBoolean("foundation.ignore-water", true),
            defaultMaterial = default,
            biomeMaterials = biomeMap,
            blendRadius = yml.getInt("foundation.blend-radius", 0),
            matchTerrain = matchTerrain,
        )
    }

    fun get(id: String): RuinTemplate? = templates[id]

    fun all(): Collection<RuinTemplate> = templates.values

    fun ids(): List<String> = templates.keys.toList()
}
