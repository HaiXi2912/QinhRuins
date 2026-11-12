package com.qinhuai.ruins.generation

import com.qinhuai.ruins.core.FitRule
import com.qinhuai.ruins.core.RuinTemplate
import com.qinhuai.ruins.structure.PasteEngines
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.util.BlockVector

/**
 * 地形适配检查（思路移植自 BetterStructures 的 TerrainAdequacy）。
 * 只在候选点确定后、真正粘贴前调用，不修改世界。
 */
object TerrainFit {

    private const val MAX_PREVIEW_BLOCKS = 120_000
    private const val MAX_SAMPLES = 800

    private val sampleCache = HashMap<String, List<BlockVector>>()

    fun clearCache() = sampleCache.clear()

    fun check(world: World, x: Int, y: Int, z: Int, template: RuinTemplate, fit: FitRule): Boolean {
        if (fit.minScore <= 0.0 && fit.requireBlocks.isEmpty() && fit.forbidBlocks.isEmpty()) return true

        if (fit.minScore > 0.0) {
            val samples = samples(template, fit)
            if (samples.isNotEmpty()) {
                var good = 0
                for (v in samples) {
                    val type = world.getBlockAt(x + v.blockX, y + v.blockY, z + v.blockZ).type
                    if (fit.forbidBlocks.any { it.equals(type.name, ignoreCase = true) }) return false
                    if (type.isSolid && type != Material.WATER && type != Material.LAVA) good++
                }
                if (good.toDouble() / samples.size < fit.minScore) return false
            }
        }

        if (fit.requireBlocks.isNotEmpty() && !hasSignature(world, x, y, z, fit)) return false
        return true
    }

    /** 在原点周围球形范围内统计特征方块（如滴水石）。 */
    private fun hasSignature(world: World, x: Int, y: Int, z: Int, fit: FitRule): Boolean {
        val radius = fit.scanRadius.coerceIn(2, 48)
        val step = (radius / 8).coerceAtLeast(1)
        var found = 0
        var dx = -radius
        while (dx <= radius && found < fit.minBlockCount) {
            var dy = -radius
            while (dy <= radius && found < fit.minBlockCount) {
                var dz = -radius
                while (dz <= radius) {
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        val type = world.getBlockAt(x + dx, y + dy, z + dz).type
                        if (fit.requireBlocks.any { it.equals(type.name, ignoreCase = true) }) {
                            found++
                            break
                        }
                    }
                    dz += step
                }
                dy += step
            }
            dx += step
        }
        return found >= fit.minBlockCount
    }

    private fun samples(template: RuinTemplate, fit: FitRule): List<BlockVector> {
        val key = "${template.id}|${fit.mode}|${fit.minScore}|${fit.scanRadius}"
        sampleCache[key]?.let { return it }
        val out = buildSamples(template, fit)
        sampleCache[key] = out
        return out
    }

    private fun buildSamples(template: RuinTemplate, fit: FitRule): List<BlockVector> {
        val blocks = PasteEngines.active().previewBlocks(template, MAX_PREVIEW_BLOCKS)
        if (blocks.isNullOrEmpty()) return aabbSamples(template)

        val pool: List<BlockVector> = if (fit.mode == "volume") {
            blocks.map { it.first }
        } else {
            val positions = HashSet<Long>(blocks.size * 2)
            for ((v, _) in blocks) positions.add(pack(v.blockX, v.blockY, v.blockZ))
            val shell = ArrayList<BlockVector>()
            for ((v, _) in blocks) {
                val bx = v.blockX
                val by = v.blockY
                val bz = v.blockZ
                val boundary = !positions.contains(pack(bx + 1, by, bz)) ||
                    !positions.contains(pack(bx - 1, by, bz)) ||
                    !positions.contains(pack(bx, by + 1, bz)) ||
                    !positions.contains(pack(bx, by - 1, bz)) ||
                    !positions.contains(pack(bx, by, bz + 1)) ||
                    !positions.contains(pack(bx, by, bz - 1))
                if (boundary) shell.add(v)
            }
            shell
        }
        if (pool.isEmpty()) return emptyList()
        val stride = (pool.size / MAX_SAMPLES).coerceAtLeast(1)
        return if (stride <= 1) pool else pool.filterIndexed { i, _ -> i % stride == 0 }
    }

    /** 结构太大、previewBlocks 返回 null 时，退化为包围盒六面采样。 */
    private fun aabbSamples(template: RuinTemplate): List<BlockVector> {
        val size = PasteEngines.active().structureSize(template) ?: return emptyList()
        val w = size.blockX
        val h = size.blockY
        val d = size.blockZ
        if (w <= 0 || h <= 0 || d <= 0) return emptyList()
        val step = (maxOf(w, h, d) / 24).coerceAtLeast(1)
        val out = ArrayList<BlockVector>()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                out.add(BlockVector(x, y, 0))
                out.add(BlockVector(x, y, d - 1))
                x += step
            }
            var z = 0
            while (z < d) {
                out.add(BlockVector(0, y, z))
                out.add(BlockVector(w - 1, y, z))
                z += step
            }
            y += step
        }
        var x = 0
        while (x < w) {
            var z = 0
            while (z < d) {
                out.add(BlockVector(x, 0, z))
                out.add(BlockVector(x, h - 1, z))
                z += step
            }
            x += step
        }
        return out
    }

    private fun pack(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0x3FFFFFF) or
            ((z.toLong() and 0x3FFFFFF) shl 26) or
            ((y.toLong() and 0xFFF) shl 52)
}
