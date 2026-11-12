package com.qinhuai.ruins

import com.qinhuai.corelib.api.item.ItemManagerAPI
import com.qinhuai.corelib.command.cloud.QinhCloud
import com.qinhuai.corelib.placeholder.PapiBridge
import com.qinhuai.corelib.util.ServerCompat
import com.qinhuai.corelib.util.TextUtil
import com.qinhuai.ruins.affix.AffixRegistry
import com.qinhuai.ruins.affix.Keystone
import com.qinhuai.ruins.affix.RerollService
import com.qinhuai.ruins.affix.TierConfig
import com.qinhuai.ruins.combat.AnchorActivation
import com.qinhuai.ruins.combat.KillListener
import com.qinhuai.ruins.combat.RuinProtectionListener
import com.qinhuai.ruins.command.QinhRuinsCommands
import com.qinhuai.ruins.data.AnchorStore
import com.qinhuai.ruins.data.DiscoveryStore
import com.qinhuai.ruins.data.LootClaimStore
import com.qinhuai.ruins.data.MechFiredStore
import com.qinhuai.ruins.data.SpinStore
import com.qinhuai.ruins.data.VesselStore
import com.qinhuai.ruins.data.SnapshotStore
import com.qinhuai.ruins.editor.EditorListener
import com.qinhuai.ruins.generation.AnchorManager
import com.qinhuai.ruins.generation.ChunkHook
import com.qinhuai.ruins.generation.NaturalGenerator
import com.qinhuai.ruins.generation.StagingService
import com.qinhuai.ruins.guide.GuideService
import com.qinhuai.ruins.integration.KeystoneItemSource
import com.qinhuai.ruins.mechanism.MechanismListener
import com.qinhuai.ruins.mechanism.MechanismService
import com.qinhuai.ruins.loot.ChestListener
import com.qinhuai.ruins.loot.ChestRegistry
import com.qinhuai.ruins.loot.LootTableRegistry
import com.qinhuai.ruins.loot.RealmLoot
import com.qinhuai.ruins.loot.SlotListener
import com.qinhuai.ruins.loot.SlotMachine
import com.qinhuai.ruins.loot.VanillaChestListener
import com.qinhuai.ruins.loot.VesselListener
import com.qinhuai.ruins.loot.VesselService
import com.qinhuai.ruins.loot.WeeklyLimitService
import com.qinhuai.ruins.placeholder.RuinPlaceholders
import com.qinhuai.ruins.realm.KeystoneListener
import com.qinhuai.ruins.realm.RealmAffixListener
import com.qinhuai.ruins.realm.RealmManager
import com.qinhuai.ruins.session.SessionListener
import com.qinhuai.ruins.session.SessionManager
import com.qinhuai.ruins.structure.PasteEngines
import com.qinhuai.ruins.structure.StructureSaver
import com.qinhuai.ruins.template.BlueprintRegistry
import com.qinhuai.ruins.template.TemplateRegistry
import com.qinhuai.ruins.template.TilePaletteRegistry
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.incendo.cloud.paper.LegacyPaperCommandManager
import java.io.File

class QinhRuins : JavaPlugin() {

    companion object {
        lateinit var instance: QinhRuins
            private set

        lateinit var commandManager: LegacyPaperCommandManager<CommandSender>
            private set

        const val VERSION = "1.1.0"

        private val LOGO = listOf(
            "",
            "§6   ___   ___   ___        _",
            "§6  / _ \\ | _ \\ |   \\  ___ (_) _ _   ___",
            "§6 | (_) ||   / | |) |/ -_)| || ' \\ (_-<",
            "§6  \\__\\_\\|_|_\\ |___/ \\___||_||_||_|/__/",
            "§e        QinhRuins §7— 秦淮遗迹探索 §e$VERSION",
            "",
        )
    }

    override fun onLoad() {
        instance = this
    }

    override fun onEnable() {
        if (server.pluginManager.getPlugin("QinhCoreLib")?.isEnabled != true) {
            logger.severe("QinhCoreLib 未成功加载，QinhRuins 无法启动")
            server.pluginManager.disablePlugin(this)
            return
        }

        ServerCompat.validateServer(logger)?.let { reason ->
            logger.severe("§c[QinhRuins] $reason")
            server.pluginManager.disablePlugin(this)
            return
        }

        LOGO.forEach { TextUtil.logColored(this, it) }

        saveDefaultConfig()
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }
        com.qinhuai.ruins.config.ConfigMigrator.migrate(this)
        reloadConfig()
        com.qinhuai.ruins.lang.Lang.load()
        if (!File(dataFolder, "templates/_example/template.yml").exists()) saveResource("templates/_example/template.yml", false)
        if (!File(dataFolder, "templates/_example/blueprint.yml").exists()) saveResource("templates/_example/blueprint.yml", false)
        if (!File(dataFolder, "scripts/affix_example.js").exists()) saveResource("scripts/affix_example.js", false)
        com.qinhuai.ruins.affix.RuinScriptBridge.register(this)

        val templatesDir = File(dataFolder, "templates")
        File(dataFolder, "schematics").mkdirs()
        PasteEngines.init(templatesDir, config.getInt("generation.spread-place-threshold", 30000), config.getBoolean("generation.marker-blocks", true), config.getLong("generation.paste-millis-per-tick", 8L))
        StructureSaver.init(templatesDir)
        val generatorsDir = File(dataFolder, "generators")
        listOf(
            "surface_overworld", "surface_grassland", "surface_desert", "surface_snowy", "surface_barren",
            "underground_overworld", "underground_lush", "underground_dripstone",
            "sky_overworld", "ocean_overworld", "seabed_overworld",
            "surface_nether", "underground_nether", "sky_nether",
            "surface_end", "underground_end", "sky_end",
        ).forEach { name ->
            if (!File(generatorsDir, "$name.yml").exists()) saveResource("generators/$name.yml", false)
        }
        val profileCount = com.qinhuai.ruins.template.GenerationProfiles.load(generatorsDir, logger)
        val templateCount = TemplateRegistry.load(templatesDir, logger)
        BlueprintRegistry.load(templatesDir)
        TilePaletteRegistry.load(File(dataFolder, "palettes"), logger)
        if (!File(dataFolder, "loottables/realm.yml").exists()) saveResource("loottables/realm.yml", false)
        if (!File(dataFolder, "loottables/vault.yml").exists()) saveResource("loottables/vault.yml", false)
        if (!File(dataFolder, "loottables/default.yml").exists()) saveResource("loottables/default.yml", false)
        if (!File(dataFolder, "loottables/compensation.yml").exists()) saveResource("loottables/compensation.yml", false)
        LootTableRegistry.load(File(dataFolder, "loottables"))
        if (!File(dataFolder, "affixes.yml").exists()) saveResource("affixes.yml", false)
        val affixCount = AffixRegistry.load(File(dataFolder, "affixes.yml"))
        TierConfig.load(config.getConfigurationSection("realm.tiers"))
        RealmLoot.load(config.getConfigurationSection("realm.reward"))
        Keystone.load(config.getConfigurationSection("realm.keystone"))
        RerollService.load(config.getConfigurationSection("realm.reroll"))
        ItemManagerAPI.instance.register(KeystoneItemSource, "qinhruins", "qr-keystone")
        AnchorManager.init(config.getInt("generation.grid-bucket-size", 512))
        AnchorStore.init(File(dataFolder, "anchors.yml"))
        AnchorManager.loadAll(AnchorStore.loadAll())
        com.qinhuai.ruins.data.RuinStorage.init(this, config)
        DiscoveryStore.init(File(dataFolder, "discoveries.yml"))
        LootClaimStore.init(File(dataFolder, "loot_claims.yml"))
        MechFiredStore.init(File(dataFolder, "mech_fired.yml"))
        VesselStore.init(File(dataFolder, "vessels.yml"))
        com.qinhuai.ruins.data.SharedVesselStore.init(File(dataFolder, "shared_vessels.yml"))
        VesselService.configure(config)
        WeeklyLimitService.configure(config, dataFolder)
        SpinStore.init(File(dataFolder, "spins.yml"))
        SnapshotStore.init(File(dataFolder, "snapshots"), config.getBoolean("cleanup.snapshot-restore", true), config.getLong("cleanup.max-snapshot-volume", 500_000L))
        ChestRegistry.rebuild(AnchorManager.all())
        MechanismService.rebuild(AnchorManager.all())
        com.qinhuai.ruins.realm.CoreRegistry.rebuild(AnchorManager.all())
        StagingService.init(this, config.getInt("staging.preview-cap", 12000))
        com.qinhuai.ruins.integration.ProviderBridges.register()?.let { logger.info("成长度来源已接入：$it") }

        commandManager = QinhCloud.create(this)
        QinhRuinsCommands.register(commandManager)

        server.pluginManager.registerEvents(ChunkHook, this)
        server.pluginManager.registerEvents(SessionListener, this)
        server.pluginManager.registerEvents(ChestListener, this)
        server.pluginManager.registerEvents(VanillaChestListener, this)
        server.pluginManager.registerEvents(VesselListener, this)
        server.pluginManager.registerEvents(SlotListener, this)
        server.pluginManager.registerEvents(KillListener, this)
        server.pluginManager.registerEvents(EditorListener, this)
        server.pluginManager.registerEvents(com.qinhuai.ruins.editor.EditorGuiListener, this)
        server.pluginManager.registerEvents(com.qinhuai.ruins.guide.CodexListener, this)
        server.pluginManager.registerEvents(com.qinhuai.ruins.guide.GuideListener, this)
        server.pluginManager.registerEvents(com.qinhuai.ruins.data.CodexSyncListener, this)
        server.pluginManager.registerEvents(com.qinhuai.ruins.editor.ChatInputService, this)
        server.pluginManager.registerEvents(RealmAffixListener, this)
        server.pluginManager.registerEvents(KeystoneListener, this)
        server.pluginManager.registerEvents(MechanismListener, this)
        RuinProtectionListener.configure(config)
        server.pluginManager.registerEvents(RuinProtectionListener, this)
        PapiBridge.register(this, RuinPlaceholders)
        NaturalGenerator.start(
            this,
            config.getBoolean("generation.enabled", true),
            config.getInt("generation.max-per-tick", 4),
            config.getLong("generation.period-ticks", 5L),
            config.getLong("generation.max-millis-per-tick", 3L),
        )
        com.qinhuai.ruins.generation.GenerationDirector.start(this, config.getConfigurationSection("generation.director"))
        GuideService.start(this, config)
        SessionManager.start(this, config)
        AnchorActivation.start(this, config)
        RealmManager.start(this, config)
        MechanismService.start(this, config)
        com.qinhuai.ruins.combat.RuinEntryService.start(this)
        server.scheduler.runTaskTimer(this, Runnable { com.qinhuai.ruins.data.SharedVesselStore.save() }, 600L, 600L)

        TextUtil.logColored(this, "§6[QinhRuins] §a已加载 §6$templateCount §a模板、§6$profileCount §a放置档案、§6${AnchorManager.all().size} §a锚点、§6$affixCount §a词缀（S1）")
        TextUtil.logColored(this, "§6[QinhRuins] §7钥石物品源已注册：其它插件可用 §eqinhruins:<层数> §7引用钥石（如掉落/商店/任务）")
    }

    override fun onDisable() {
        ItemManagerAPI.instance.unregister("qinhruins")
        com.qinhuai.ruins.data.RuinStorage.close()
        VesselStore.save()
        com.qinhuai.ruins.data.SharedVesselStore.save()
        com.qinhuai.ruins.combat.RuinEntryService.stop()
        MechanismService.stop()
        RealmManager.stop()
        AnchorActivation.stop()
        SessionManager.stop()
        GuideService.stop()
        com.qinhuai.ruins.generation.GenerationDirector.stop()
        NaturalGenerator.stop()
        TextUtil.logColored(this, "§6[QinhRuins] §c已卸载")
    }
}
