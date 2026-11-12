package com.qinhuai.ruins.placeholder

import com.qinhuai.corelib.placeholder.QinhPlaceholderProvider
import com.qinhuai.ruins.combat.AnchorProgress
import com.qinhuai.ruins.combat.ObjectiveTracker
import com.qinhuai.ruins.realm.ActiveRealm
import com.qinhuai.ruins.lang.Lang
import com.qinhuai.ruins.realm.RealmManager
import com.qinhuai.ruins.template.TemplateRegistry
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

object RuinPlaceholders : QinhPlaceholderProvider {

    override val identifier: String = "qinhruins"

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val online = player as? Player ?: return ""

        if (params.equals("keystone_tier", true)) return keystoneTier(online)
        if (params.startsWith("realm_", true)) return realmValue(online, params)
        if (params.startsWith("guide_", true)) return guideValue(online, params)
        if (params.startsWith("boss_", true)) return bossValue(online, params)
        if (params.startsWith("weekly", true)) return com.qinhuai.ruins.loot.WeeklyLimitService.placeholder(online, params)

        val progress = ObjectiveTracker.nearestProgress(online.location)
            ?: return if (params.equals("stage", true) || params.startsWith("kills_", true)) "0" else ""

        return when {
            params.equals("ruin", true) ->
                TemplateRegistry.get(progress.templateId)?.display ?: progress.templateId
            params.equals("stage", true) -> progress.currentStage.toString()
            params.equals("max_stage", true) -> progress.objectives.maxStage.toString()
            params.equals("stage_name", true) ->
                progress.objectives.stage(progress.currentStage)?.name ?: ""
            params.equals("completed", true) -> if (progress.completed) "true" else "false"
            params.equals("objective", true) -> objectiveText(progress)
            params.equals("progress", true) -> progressText(progress)
            params.startsWith("kills_", true) -> {
                val key = params.substring(6)
                (progress.playerKills[online.uniqueId]?.get(key) ?: 0).toString()
            }
            else -> null
        }
    }

    private fun keystoneTier(player: Player): String {
        com.qinhuai.ruins.affix.Keystone.currentBuildTier()?.let { return it.toString() }
        val inv = player.inventory
        val tier = com.qinhuai.ruins.affix.Keystone.tierOf(inv.itemInMainHand)
            ?: com.qinhuai.ruins.affix.Keystone.tierOf(inv.itemInOffHand)
        return tier?.toString() ?: ""
    }

    private fun guideValue(player: Player, params: String): String {
        val info = com.qinhuai.ruins.guide.GuideService.guideInfo(player.uniqueId)
        if (info == null) {
            return when (params.lowercase()) {
                "guide_active" -> "false"
                "guide_distance" -> "0"
                else -> ""
            }
        }
        return when (params.lowercase()) {
            "guide_active" -> "true"
            "guide_ruin" -> info.ruin
            "guide_distance" -> info.distance.toString()
            "guide_direction" -> info.direction
            "guide_world" -> info.world
            else -> ""
        }
    }

    private fun bossValue(player: Player, params: String): String {
        val g = com.qinhuai.ruins.combat.BossGateTracker.nearestProgress(player.location)
        if (g == null) {
            return when (params.lowercase()) {
                "boss_active", "boss_unlocked" -> "false"
                "boss_kills", "boss_required", "boss_remaining" -> "0"
                else -> ""
            }
        }
        return when (params.lowercase()) {
            "boss_active" -> "true"
            "boss_unlocked" -> if (g.unlocked) "true" else "false"
            "boss_kills" -> g.kills.toString()
            "boss_required" -> g.gate.requiredKills.toString()
            "boss_remaining" -> g.remaining.toString()
            else -> ""
        }
    }

    private fun realmValue(player: Player, params: String): String {
        val realm = RealmManager.realmNear(player.location)
        if (realm == null) {
            return when (params.lowercase()) {
                "realm_active" -> "false"
                "realm_tier", "realm_mobs", "realm_total" -> "0"
                "realm_time" -> "0"
                else -> ""
            }
        }
        return when (params.lowercase()) {
            "realm_active" -> "true"
            "realm_tier" -> realm.tier.toString()
            "realm_name" -> TemplateRegistry.get(realm.templateId)?.display ?: realm.templateId
            "realm_affixes" -> realm.affixes.joinToString(" ") { it.name }
            "realm_affix_count" -> realm.affixes.size.toString()
            "realm_mobs" -> RealmManager.aliveMobs(realm).toString()
            "realm_total" -> realm.totalMobs.toString()
            "realm_time" -> timeText(realm)
            else -> ""
        }
    }

    private fun timeText(realm: ActiveRealm): String {
        val remaining = RealmManager.remainingSeconds(realm)
        if (remaining < 0) return "∞"
        val m = remaining / 60
        val s = remaining % 60
        return "%d:%02d".format(m, s)
    }

    private fun objectiveText(progress: AnchorProgress): String {
        if (progress.completed) return Lang.get("core.objective-completed")
        val stage = progress.objectives.stage(progress.currentStage) ?: return ""
        return stage.kills.joinToString(" · ") {
            "${it.mobKey} ${(progress.stageKills[it.mobKey] ?: 0).coerceAtMost(it.amount)}/${it.amount}"
        }
    }

    private fun progressText(progress: AnchorProgress): String {
        if (progress.completed) return "100%"
        val stage = progress.objectives.stage(progress.currentStage) ?: return ""
        val first = stage.kills.firstOrNull() ?: return ""
        val done = (progress.stageKills[first.mobKey] ?: 0).coerceAtMost(first.amount)
        return "$done/${first.amount}"
    }
}
