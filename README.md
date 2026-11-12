# QinhRuins 1.1.0-mod.1 更新日志

> 本仓库是基于上游 [JIULIVE/QinhRuins](https://github.com/JIULIVE/QinhRuins) `bfd96e5`（v1.1.0）的修改版。
> 只保留构建所需内容（`pom.xml`、`src/`、`.gitignore`），其余文档与图片已移除。

- 版本：`1.1.0-mod.1`
- 日期：2026-11-12
- 基线：`bfd96e51253e7bc9b2aab452757bdaad45104817`

---

## 新增

### 1. 地形适配检查（`generation.fit`）

放置遗迹前先对候选位置采样打分，不达标就重试或放弃，避免遗迹悬空、埋死在石头里。

```yaml
generation:
  fit:
    enabled: true
    mode: shell          # shell=外壳采样 | volume=整体采样 | signature=附近特征方块
    min-score: 0.8       # 采样命中率低于此值判定不合格
    scan-radius: 16      # signature 模式的球形扫描半径
    require-blocks:      # 附近必须出现的方块（用于识别洞穴类型）
      - minecraft:dripstone_block
      - minecraft:pointed_dripstone
    forbid-blocks: []
```

- 文件：`generation/TerrainFit.kt`（新增）、`core/RuinTemplate.kt`、`template/GenerationProfiles.kt`、`generation/AnchorPlacer.kt`
- `signature` 模式用于“滴水石洞/繁茂洞穴”这类无法靠群系稳定判断的地形：扫描半径内的特征方块数量达标即视为合适。

### 2. 绝对 Y 区间

`y` 现在支持绝对区间写法：

```yaml
generation:
  y: "[0;40]"     # 结构底部在 y=0 到 y=40 之间随机
```

原有 `surface`、`underground`、`+8`、`-[3;12]` 等写法保持不变。

### 3. 每周开箱限额

四个独立作用域：`ruin`（遗迹箱子）、`boss`（BOSS 奖励箱）、`slot`（老虎机）、`vanilla`（原版结构箱子）。

```yaml
weekly-limit:
  enabled: true
  count-vanilla: false        # 原版结构箱子是否计入
  reset-day: MONDAY
  reset-hour: 4
  scopes:
    ruin:
      per-week: 16
      tables: []              # 留空 = 未列出的表都算遗迹箱
      permission-bonus:
        "qinhruins.limit.ruin.vip": 4
        "qinhruins.limit.ruin.mvp": 8
      temporary-bonus:
        - start: "2026-12-24"
          end: "2026-12-26"
          amount: 5
          reason: "圣诞活动"
    boss:
      per-week: 3
      tables: [boss, boss_reward, boss_vault]
      permission-bonus:
        "qinhruins.limit.boss.vip": 2
    slot:
      per-week: 3
      tables: []
      permission-bonus:
        "qinhruins.limit.slot.vip": 2
    vanilla:
      per-week: 5
  over-limit:
    mode: chest               # chest=开补偿箱 | reward=给物品 | deny=只提示
    table: compensation
    title: "&6本周次数已用完 · 安慰奖"
    rows: 3
    once-per-chest: true
    message: "&e本周次数已用完，这是安慰奖"
    commands: []
    items: []
```

- 文件：`data/WeeklyLimitStore.kt`、`loot/WeeklyLimitService.kt`（新增）、`loot/ChestListener.kt`、`loot/VesselService.kt`、`loot/SlotMachine.kt`
- 原版结构箱子通过 `lootTable != null` 判定，玩家自己放的箱子不计入；`count-vanilla: false` 默认关闭。
- 占位符（需 PAPI）：`%qinhruins_weekly_used%`、`%qinhruins_weekly_limit%`、`%qinhruins_weekly_remaining%`、`%qinhruins_weekly_reset%`，以及 `boss_`、`slot_`、`vanilla_` 前缀变体。

### 4. 遗迹保护系统重做

| 方块 | 通关前 | 通关后 |
|---|---|---|
| 战利品箱 | 不可破坏，提示 | 不可破坏 |
| 矿物 / 矿物块 / 粗矿块 | 可挖、正常掉落、不恢复 | 同左 |
| 其他结构方块 | 不可破坏 | 按 `after-clear` |
| 放置方块 | 禁止 | 允许 |
| 爆炸 | 遗迹内非矿物方块不被破坏 | 同左 |

```yaml
protection:
  enabled: true
  chests: true
  chest-message: true
  after-clear: restore        # deny | restore | allow
  restore-delay-ticks: 100
  no-drop: true
  ore-tags:
    - "#minecraft:ores"
    - "#minecraft:beacon_base_blocks"
  ore-blocks:
    - minecraft:raw_iron_block
    - minecraft:raw_copper_block
    - minecraft:raw_gold_block
```

- 文件：`combat/RuinProtectionListener.kt`
- `restore` 模式只在该位置仍为空气时恢复，不覆盖玩家新放的方块。
- 管理员权限 `qinhruins.admin` 不受保护限制。

### 5. 生成播报可点击传送

生成播报中的遗迹名可点击，直接执行 `/qr tp <id>`；悬停显示传送目标。

- 文件：`generation/GenerationDirector.kt`
- 语言键：`cmd.gen-announce-tp`、`cmd.gen-announce-tp-hover`（`lang/zh_cn`、`lang/en_US`）

---

## 修复

- **模板 `display` 的 `&` 颜色代码不生效**：`&6遗迹名` 会原样显示 `&6`。现在解析时统一转换为 `§`。
  - 文件：`template/TemplateRegistry.kt`

## 资源

- 新增 `loottables/compensation.yml`（超额补偿箱默认内容），首次启动自动释放。

## 兼容性

- Paper / Purpur / Spigot 1.21.x+（26.x 可运行，启动会有“未测试版本”警告，可在 `config.yml` 关闭 `compatibility.strict-version-check`）
- Java 25、Kotlin 2.3.0、`paper-api 1.21.4`
- 依赖 QinhCoreLib 1.3.0

## 构建

```bash
mvn -B -DskipTests package
# 产物：target/QinhRuins-1.1.0.jar
```

## 相对上游的文件变更

新增：
- `src/main/kotlin/com/qinhuai/ruins/generation/TerrainFit.kt`
- `src/main/kotlin/com/qinhuai/ruins/data/WeeklyLimitStore.kt`
- `src/main/kotlin/com/qinhuai/ruins/loot/WeeklyLimitService.kt`
- `src/main/kotlin/com/qinhuai/ruins/loot/VanillaChestListener.kt`
- `src/main/resources/loottables/compensation.yml`

修改：
- `QinhRuins.kt`
- `combat/RuinProtectionListener.kt`
- `core/RuinTemplate.kt`
- `generation/AnchorPlacer.kt`
- `generation/GenerationDirector.kt`
- `loot/ChestListener.kt`
- `loot/SlotMachine.kt`
- `loot/VesselService.kt`
- `placeholder/RuinPlaceholders.kt`
- `template/GenerationProfiles.kt`
- `template/TemplateRegistry.kt`
- `resources/config.yml`
- `resources/lang/{zh_cn,en_US}/{commands,core}.yml`

移除：
- `INTEGRATIONS.md`、`catalog.yml`、`icon-96.png`、`minecraft_title.png`（精简仓库）
