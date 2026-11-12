package com.qinhuai.ruins.core

data class SpawnRegion(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int, val exclude: Boolean = false) {
    fun contains(x: Int, z: Int): Boolean = x in minX..maxX && z in minZ..maxZ
    fun allows(x: Int, z: Int): Boolean = contains(x, z) != exclude
}

data class FitRule(
    val mode: String = "shell",
    val minScore: Double = 0.0,
    val scanRadius: Int = 16,
    val requireBlocks: List<String> = emptyList(),
    val minBlockCount: Int = 1,
    val forbidBlocks: List<String> = emptyList(),
)

data class GenerationRule(
    val enabled: Boolean,
    val worlds: List<String>,
    val environments: List<String> = emptyList(),
    val biomes: List<String>,
    val probabilityNumerator: Int,
    val probabilityDenominator: Int,
    val priority: Int = 0,
    val spawnChance: Double = 1.0,
    val minDistanceOthers: Int,
    val minDistanceSame: Int,
    val yMode: String,
    val yBandMin: Int = 0,
    val yBandMax: Int = 0,
    val yOffset: Int = 0,
    val heightmap: String? = null,
    val whitelistGround: List<String>,
    val blacklistGround: List<String> = emptyList(),
    val flatnessRadius: Int,
    val flatnessMaxVariance: Int,
    val flatnessMaxErrors: Int = 0,
    val placementAttempts: Int = 1,
    val spawnInWater: Boolean = true,
    val spawnInLava: Boolean = false,
    val spawnInVoid: Boolean = true,
    val spawnRegion: SpawnRegion? = null,
    val fit: FitRule? = null,
)

data class GuideItemConfig(
    val ref: String?,
    val material: String,
    val name: String?,
    val lore: List<String>,
    val modelData: Int?,
    val consumable: Boolean,
)

data class EntryRule(
    val minPlayers: Int,
    val maxPlayers: Int,
    val requiredClasses: List<String>,
    val minGrowth: Double,
    val cost: String,
    val cooldownSeconds: Long,
)

data class SessionRule(
    val mode: String,
    val timeLimitSeconds: Long,
    val cleanupOnEmptySeconds: Long,
)

data class RuinTitles(
    val enterNew: String,
    val clear: String,
    val enterExplored: String,
)

data class FoundationConfig(
    val enabled: Boolean,
    val maxDepth: Int,
    val ignoreWater: Boolean,
    val defaultMaterial: String?,
    val biomeMaterials: Map<String, String>,
    val blendRadius: Int = 0,
    val matchTerrain: Boolean = false,
)

data class RuinTemplate(
    val id: String,
    val display: String,
    val icon: String = "FILLED_MAP",
    val structureFile: String,
    val rotationMode: String,
    val targetMask: List<String> = emptyList(),
    val sourceSkip: List<String> = emptyList(),
    val sourceMask: List<String> = emptyList(),
    val replaceBlocks: Map<String, String> = emptyMap(),
    val generation: GenerationRule,
    val generationWeight: Int = 0,
    val guideItem: GuideItemConfig,
    val entry: EntryRule,
    val session: SessionRule,
    val respawnSeconds: Long,
    val foundation: FoundationConfig,
    val palette: String?,
    val containerTable: String?,
    val containerTables: Map<String, String> = emptyMap(),
    val vesselMode: String = "per-player",
    val clearRewardTable: String?,
    val titles: RuinTitles,
)
