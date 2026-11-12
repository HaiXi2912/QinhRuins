package com.qinhuai.ruins.generation

import com.qinhuai.ruins.core.RuinTemplate
import com.qinhuai.ruins.lang.Lang
import org.bukkit.HeightMap
import org.bukkit.Keyed
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import java.util.Random

object AnchorPlacer {

    fun tryPlace(world: World, chunkX: Int, chunkZ: Int, template: RuinTemplate, random: Random): Boolean {
        val gen = template.generation
        if (gen.spawnChance < 1.0 && random.nextDouble() >= gen.spawnChance) return false
        val (bx, bz) = pickCandidate(world, chunkX, chunkZ, gen, random)

        gen.spawnRegion?.let { if (!it.allows(bx, bz)) return false }

        val y = resolveY(world, bx, bz, template, gen, random) ?: return false

        val height = structureHeight(template)
        if (height > 0 && (y + height > world.maxHeight || y < world.minHeight)) return false

        gen.fit?.let { fit -> if (!TerrainFit.check(world, bx, y, bz, template, fit)) return false }

        val groundBlock = world.getBlockAt(bx, y - 1, bz)
        if (gen.whitelistGround.isNotEmpty() || gen.blacklistGround.isNotEmpty()) {
            val ground = groundBlock.type.name
            if (gen.whitelistGround.isNotEmpty() && gen.whitelistGround.none { it.equals(ground, ignoreCase = true) }) return false
            if (gen.blacklistGround.any { it.equals(ground, ignoreCase = true) }) return false
        }
        if (!gen.spawnInWater && groundBlock.type == Material.WATER) return false
        if (!gen.spawnInLava && groundBlock.type == Material.LAVA) return false
        if (!gen.spawnInVoid && groundBlock.type.isAir) return false

        if (!biomeMatches(world, bx, y, bz, gen.biomes)) return false

        if (gen.flatnessMaxVariance > 0 && !isFlat(world, bx, bz, gen.flatnessRadius, gen.flatnessMaxVariance, gen.flatnessMaxErrors)) return false

        val origin = Location(world, bx.toDouble(), y.toDouble(), bz.toDouble())

        val spacing = if (gen.minDistanceOthers > 0) gen.minDistanceOthers else GenerationDirector.globalMinSpacing()
        if (spacing > 0 && AnchorManager.nearby(origin, spacing.toDouble()).isNotEmpty()) return false
        if (gen.minDistanceSame > 0 && AnchorManager.nearby(origin, gen.minDistanceSame.toDouble()).any { it.templateId == template.id }) return false

        if (!GenerationDirector.densityOk(world, bx, bz)) return false

        if (!com.qinhuai.ruins.api.RuinPreSpawnEvent.fire(template.id, origin)) return false

        val outcome = AnchorManager.spawn(template, origin)
        val anchor = outcome.anchor ?: return false
        GenerationDirector.announceSpawn(template, anchor)
        return true
    }

    private fun pickCandidate(world: World, chunkX: Int, chunkZ: Int, gen: com.qinhuai.ruins.core.GenerationRule, random: Random): Pair<Int, Int> {
        val attempts = gen.placementAttempts.coerceAtLeast(1)
        if (attempts <= 1) {
            return (chunkX * 16 + random.nextInt(16)) to (chunkZ * 16 + random.nextInt(16))
        }
        val radius = if (gen.flatnessRadius > 0) gen.flatnessRadius else 3
        var bestX = chunkX * 16 + random.nextInt(16)
        var bestZ = chunkZ * 16 + random.nextInt(16)
        var bestScore = roughness(world, bestX, bestZ, radius)
        repeat(attempts - 1) {
            val cx = chunkX * 16 + random.nextInt(16)
            val cz = chunkZ * 16 + random.nextInt(16)
            val score = roughness(world, cx, cz, radius)
            if (score < bestScore) {
                bestScore = score
                bestX = cx
                bestZ = cz
            }
        }
        return bestX to bestZ
    }

    private fun roughness(world: World, x: Int, z: Int, radius: Int): Int {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        val step = if (radius <= 4) 2 else 3
        var dx = -radius
        while (dx <= radius) {
            var dz = -radius
            while (dz <= radius) {
                val h = surfaceY(world, x + dx, z + dz)
                if (h < min) min = h
                if (h > max) max = h
                dz += step
            }
            dx += step
        }
        return if (min == Int.MAX_VALUE) 0 else max - min
    }

    data class DiagLine(val label: String, val ok: Boolean, val detail: String)

    fun diagnose(world: World, x: Int, z: Int, template: RuinTemplate): List<DiagLine> {
        val gen = template.generation
        val out = ArrayList<DiagLine>()

        out.add(DiagLine(Lang.get("diag.enabled-label"), gen.enabled,
            if (gen.enabled) Lang.get("diag.enabled-ok")
            else Lang.get("diag.enabled-fail")))

        val worldOk = gen.worlds.isEmpty() || gen.worlds.any { it.equals(world.name, ignoreCase = true) }
        out.add(DiagLine(Lang.get("diag.world-label"), worldOk,
            if (gen.worlds.isEmpty()) Lang.get("diag.world-any")
            else if (worldOk) Lang.get("diag.world-ok", "world" to world.name)
            else Lang.get("diag.world-fail", "world" to world.name, "list" to gen.worlds)))

        val envName = world.environment.name
        val envOk = gen.environments.isEmpty() || gen.environments.any { normalizeEnv(it) == envName }
        out.add(DiagLine(Lang.get("diag.env-label"), envOk,
            if (gen.environments.isEmpty()) Lang.get("diag.env-any")
            else if (envOk) Lang.get("diag.env-ok", "env" to envName)
            else Lang.get("diag.env-fail", "env" to envName, "list" to gen.environments)))

        gen.spawnRegion?.let {
            val ok = it.allows(x, z)
            val box = "[${it.minX},${it.minZ}~${it.maxX},${it.maxZ}]"
            val mode = if (it.exclude) Lang.get("diag.region-outside") else Lang.get("diag.region-inside")
            out.add(DiagLine(Lang.get("diag.region-label"), ok,
                if (ok) Lang.get("diag.region-ok", "x" to x, "z" to z, "mode" to mode, "box" to box)
                else Lang.get("diag.region-fail", "x" to x, "z" to z, "mode" to mode, "box" to box)))
        }

        val y = resolveY(world, x, z, template, gen, Random())
        out.add(DiagLine(Lang.get("diag.height-label"), y != null,
            if (y != null) Lang.get("diag.height-ok", "mode" to gen.yMode, "y" to y)
            else Lang.get("diag.height-fail", "mode" to gen.yMode)))
        val effY = y ?: surfaceY(world, x, z)

        if (gen.whitelistGround.isNotEmpty() || gen.blacklistGround.isNotEmpty()) {
            val ground = world.getBlockAt(x, effY - 1, z).type.name
            val wOk = gen.whitelistGround.isEmpty() || gen.whitelistGround.any { it.equals(ground, ignoreCase = true) }
            val bOk = gen.blacklistGround.none { it.equals(ground, ignoreCase = true) }
            out.add(DiagLine(Lang.get("diag.ground-label"), wOk && bOk, when {
                !wOk -> Lang.get("diag.ground-not-white", "ground" to ground, "list" to gen.whitelistGround)
                !bOk -> Lang.get("diag.ground-black", "ground" to ground, "list" to gen.blacklistGround)
                else -> Lang.get("diag.ground-ok", "ground" to ground)
            }))
        }

        val groundType = world.getBlockAt(x, effY - 1, z).type
        if (!gen.spawnInWater) {
            out.add(DiagLine(Lang.get("diag.water-label"), groundType != Material.WATER,
                if (groundType != Material.WATER) Lang.get("diag.water-ok") else Lang.get("diag.water-fail")))
        }
        if (!gen.spawnInLava) {
            out.add(DiagLine(Lang.get("diag.lava-label"), groundType != Material.LAVA,
                if (groundType != Material.LAVA) Lang.get("diag.lava-ok") else Lang.get("diag.lava-fail")))
        }
        if (!gen.spawnInVoid) {
            out.add(DiagLine(Lang.get("diag.void-label"), !groundType.isAir,
                if (!groundType.isAir) Lang.get("diag.void-ok") else Lang.get("diag.void-fail")))
        }

        val biomeOk = biomeMatches(world, x, effY, z, gen.biomes)
        out.add(DiagLine(Lang.get("diag.biome-label"), biomeOk,
            if (gen.biomes.isEmpty()) Lang.get("diag.biome-any")
            else if (biomeOk) Lang.get("diag.biome-ok", "biome" to world.getBiome(x, effY, z))
            else Lang.get("diag.biome-fail", "biome" to world.getBiome(x, effY, z), "list" to gen.biomes)))

        if (gen.flatnessMaxVariance > 0) {
            val ok = isFlat(world, x, z, gen.flatnessRadius, gen.flatnessMaxVariance, gen.flatnessMaxErrors)
            out.add(DiagLine(Lang.get("diag.flat-label"), ok,
                if (ok) Lang.get("diag.flat-ok", "radius" to gen.flatnessRadius, "variance" to gen.flatnessMaxVariance)
                else Lang.get("diag.flat-fail", "radius" to gen.flatnessRadius, "variance" to gen.flatnessMaxVariance)))
        }

        val origin = Location(world, x.toDouble(), effY.toDouble(), z.toDouble())
        if (gen.minDistanceOthers > 0) {
            val ok = AnchorManager.nearby(origin, gen.minDistanceOthers.toDouble()).isEmpty()
            out.add(DiagLine(Lang.get("diag.dist-others-label"), ok,
                if (ok) Lang.get("diag.dist-others-ok", "dist" to gen.minDistanceOthers) else Lang.get("diag.dist-others-fail", "dist" to gen.minDistanceOthers)))
        }
        if (gen.minDistanceSame > 0) {
            val ok = AnchorManager.nearby(origin, gen.minDistanceSame.toDouble()).none { it.templateId == template.id }
            out.add(DiagLine(Lang.get("diag.dist-same-label"), ok,
                if (ok) Lang.get("diag.dist-same-ok", "dist" to gen.minDistanceSame) else Lang.get("diag.dist-same-fail", "dist" to gen.minDistanceSame)))
        }

        val densityOk = GenerationDirector.densityOk(world, x, z)
        out.add(DiagLine(Lang.get("diag.density-label"), densityOk,
            if (densityOk) Lang.get("diag.density-ok") else Lang.get("diag.density-fail")))

        return out
    }

    private fun normalizeEnv(raw: String): String = when (raw.trim().uppercase()) {
        "OVERWORLD", "NORMAL" -> "NORMAL"
        "NETHER", "THE_NETHER" -> "NETHER"
        "END", "THE_END" -> "THE_END"
        else -> raw.trim().uppercase()
    }

    private fun resolveY(world: World, x: Int, z: Int, template: RuinTemplate, gen: com.qinhuai.ruins.core.GenerationRule, random: Random): Int? {
        val mode = gen.yMode.trim().lowercase()
        val hm = resolveHeightmap(gen.heightmap)
        val base = when {
            mode.startsWith("[") && mode.endsWith("]") -> parseAbsoluteY(mode, random) ?: return null
            mode.startsWith("+") || mode.startsWith("-") -> parseRelativeY(world, x, z, hm, mode, random) ?: return null
            mode.toIntOrNull() != null -> mode.toInt()
            else -> when (mode) {
                "surface", "ground", "top" -> surfaceY(world, x, z, hm)
                "underground", "cave", "buried" -> {
                    val lo = if (gen.yBandMin > 0) gen.yBandMin else 8
                    val hi = if (gen.yBandMax > lo) gen.yBandMax else maxOf(lo + 1, 40)
                    val depth = lo + random.nextInt(hi - lo + 1)
                    (surfaceY(world, x, z, hm) - depth).coerceAtLeast(world.minHeight + 1)
                }
                "sky", "floating", "air" -> {
                    val lo = if (gen.yBandMin > 0) gen.yBandMin else 30
                    val hi = if (gen.yBandMax > lo) gen.yBandMax else maxOf(lo + 1, 70)
                    val height = lo + random.nextInt(hi - lo + 1)
                    (surfaceY(world, x, z, hm) + height).coerceAtMost(world.maxHeight - 1)
                }
                "ocean-surface", "sea-surface", "water-surface" -> {
                    val sea = oceanSurface(world, x, z) ?: return null
                    val waterline = com.qinhuai.ruins.structure.PasteEngines.active().waterlineY(template)
                    if (waterline >= 0) sea - waterline else sea
                }
                "seabed", "ocean-floor", "sea-floor", "underwater" -> oceanFloor(world, x, z) ?: return null
                else -> surfaceY(world, x, z, hm)
            }
        }
        return base + gen.yOffset
    }

    private fun parseAbsoluteY(mode: String, random: Random): Int? {
        val parts = mode.substring(1, mode.length - 1).split(";").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size < 2) return null
        val lo = minOf(parts[0], parts[1])
        val hi = maxOf(parts[0], parts[1])
        return lo + random.nextInt(hi - lo + 1)
    }

    private fun parseRelativeY(world: World, x: Int, z: Int, hm: HeightMap?, mode: String, random: Random): Int? {
        val sign = if (mode.startsWith("-")) -1 else 1
        val rest = mode.substring(1).trim()
        val offset = if (rest.startsWith("[") && rest.endsWith("]")) {
            val parts = rest.substring(1, rest.length - 1).split(";").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size < 2) return null
            val lo = minOf(parts[0], parts[1])
            val hi = maxOf(parts[0], parts[1])
            lo + random.nextInt(hi - lo + 1)
        } else {
            rest.toIntOrNull() ?: return null
        }
        return surfaceY(world, x, z, hm) + sign * offset
    }

    fun isValidYMode(raw: String): Boolean {
        val mode = raw.trim().lowercase()
        if (mode.toIntOrNull() != null) return true
        if (mode in NAMED_Y_MODES) return true
        if (mode.startsWith("[") && mode.endsWith("]")) {
            return mode.substring(1, mode.length - 1).split(";").mapNotNull { it.trim().toIntOrNull() }.size >= 2
        }
        if (mode.startsWith("+") || mode.startsWith("-")) {
            val rest = mode.substring(1).trim()
            if (rest.toIntOrNull() != null) return true
            if (rest.startsWith("[") && rest.endsWith("]")) {
                return rest.substring(1, rest.length - 1).split(";").mapNotNull { it.trim().toIntOrNull() }.size >= 2
            }
            return false
        }
        return false
    }

    private val NAMED_Y_MODES = setOf(
        "surface", "ground", "top", "underground", "cave", "buried",
        "sky", "floating", "air", "ocean-surface", "sea-surface", "water-surface",
        "seabed", "ocean-floor", "sea-floor", "underwater",
    )

    private val heightCache = HashMap<String, Int>()

    private fun structureHeight(template: RuinTemplate): Int {
        heightCache[template.id]?.let { return it }
        val h = com.qinhuai.ruins.structure.PasteEngines.active().structureSize(template)?.blockY ?: 0
        heightCache[template.id] = h
        return h
    }

    fun clearCache() {
        heightCache.clear()
    }

    private fun surfaceY(world: World, x: Int, z: Int, heightmap: HeightMap? = null): Int {
        if (world.environment == World.Environment.NETHER) return netherFloor(world, x, z)
        if (heightmap != null) {
            runCatching { return world.getHighestBlockYAt(x, z, heightmap) + 1 }
        }
        var y = world.getHighestBlockYAt(x, z)
        while (y > world.minHeight && isFoliage(world.getBlockAt(x, y, z).type)) y--
        return y + 1
    }

    private fun resolveHeightmap(name: String?): HeightMap? {
        if (name.isNullOrBlank()) return null
        return when (name.trim().lowercase().replace('_', '-')) {
            "world-surface", "surface" -> HeightMap.WORLD_SURFACE
            "motion-blocking" -> HeightMap.MOTION_BLOCKING
            "motion-blocking-no-leaves", "no-leaves" -> HeightMap.MOTION_BLOCKING_NO_LEAVES
            "ocean-floor", "ocean" -> HeightMap.OCEAN_FLOOR
            else -> runCatching { HeightMap.valueOf(name.trim().uppercase().replace('-', '_')) }.getOrNull()
        }
    }

    private fun netherFloor(world: World, x: Int, z: Int): Int {
        var y = 120
        while (y > world.minHeight + 1) {
            val below = world.getBlockAt(x, y - 1, z).type
            val at = world.getBlockAt(x, y, z).type
            val above = world.getBlockAt(x, y + 1, z).type
            if (below.isSolid && below != Material.LAVA && at.isAir && above.isAir) return y
            y--
        }
        return 64
    }

    private fun oceanSurface(world: World, x: Int, z: Int): Int? {
        val sea = world.seaLevel
        if (world.getBlockAt(x, sea - 1, z).type != Material.WATER) return null
        return sea
    }

    private fun oceanFloor(world: World, x: Int, z: Int): Int? {
        val sea = world.seaLevel
        if (world.getBlockAt(x, sea - 1, z).type != Material.WATER) return null
        var y = sea - 1
        while (y > world.minHeight) {
            val type = world.getBlockAt(x, y, z).type
            if (type != Material.WATER && type.isSolid) return y + 1
            y--
        }
        return null
    }

    private fun isFoliage(material: Material): Boolean {
        if (material.isAir) return true
        val name = material.name
        if (name.endsWith("_LEAVES") || name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_SAPLING")) return true
        if (material == Material.SNOW || material == Material.BAMBOO || material == Material.VINE) return true
        return !material.isSolid && material != Material.WATER && material != Material.LAVA
    }

    fun groundY(world: World, x: Int, z: Int): Int = surfaceY(world, x, z)

    private fun isFlat(world: World, x: Int, z: Int, radius: Int, maxVariance: Int, maxErrors: Int): Boolean {
        if (radius <= 0) return true
        val heights = ArrayList<Int>()
        val step = if (radius <= 4) 1 else 2
        var dx = -radius
        while (dx <= radius) {
            var dz = -radius
            while (dz <= radius) {
                heights.add(surfaceY(world, x + dx, z + dz))
                dz += step
            }
            dx += step
        }
        if (heights.isEmpty()) return true
        val base = heights.min()
        val errors = heights.count { it - base > maxVariance }
        return errors <= maxErrors
    }

    private fun biomeMatches(world: World, x: Int, y: Int, z: Int, allowed: List<String>): Boolean {
        if (allowed.isEmpty()) return true
        val biome = world.getBiome(x, y, z)
        val keyName = runCatching { (biome as Keyed).key.value().uppercase() }.getOrNull()
        val rawName = biome.toString().substringAfterLast('.').substringAfterLast(':').uppercase()
        val normalized = allowed.map { it.substringAfter(':').uppercase() }.toSet()
        return (keyName != null && normalized.contains(keyName)) || normalized.contains(rawName)
    }
}
