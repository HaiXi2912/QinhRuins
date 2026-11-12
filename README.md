<p align="center"><img src="minecraft_title.png" alt="QinhRuins" width="640"></p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25+-orange" alt="Java 25+">
  <img src="https://img.shields.io/badge/Paper%20%7C%20Purpur%20%7C%20Spigot-1.21.11+-blue" alt="MC 1.21.11+">
  <img src="https://img.shields.io/badge/Branch-Enhanced-green" alt="Enhanced branch">
</p>

<p align="center"><b>QinhRuins 增强分支：地形适配 · 每周开箱限额 · 遗迹保护 · 编辑器增量保存</b></p>

> 本仓库是 [JIULIVE/QinhRuins](https://github.com/JIULIVE/QinhRuins) `v1.1.0` 的改造分支。
> 上游负责"遗迹怎么长出来"，本分支补的是"长出来之后怎么管"：地形合不合格、每周能开几次箱、方块能不能挖、编辑器改的东西怎么落盘。

---

## 目录

- [这个分支改了什么](#这个分支改了什么)
- [1. 地形适配 fit](#1-地形适配-fit)
- [2. 绝对 Y 区间](#2-绝对-y-区间)
- [3. 每周开箱限额](#3-每周开箱限额)
- [4. 遗迹保护](#4-遗迹保护)
- [5. 编辑器增量保存](#5-编辑器增量保存)
- [6. 生成播报](#6-生成播报)
- [7. 其他修复](#7-其他修复)
- [快速开始](#快速开始)
- [构建](#构建)
- [兼容性与已知限制](#兼容性与已知限制)

## 这个分支改了什么

| 改动 | 一句话效果 | 主要文件 |
|---|---|---|
| 地形适配 | 生成前采样世界方块，地形不合格就不放，地下结构不再悬空/埋死 | `generation/TerrainFit.kt`、`AnchorPlacer.kt` |
| 绝对 Y 区间 | `y: "[-53;-10]"` 直接按世界坐标放，不受地表高度影响 | `AnchorPlacer.resolveY` |
| 每周开箱限额 | 四个作用域独立计数，支持权限加成、节假日临时加成、超额开补偿箱 | `loot/WeeklyLimitService.kt`、`data/WeeklyLimitStore.kt` |
| 原版箱子计数 | 按 `lootTable != null` 识别原版遗迹箱，可开关 | `loot/VanillaChestListener.kt` |
| 遗迹保护 | 箱子不可破坏；矿物例外；其他方块可挖但 10 秒恢复；放置方块 10 秒消失 | `combat/RuinProtectionListener.kt` |
| 编辑器增量保存 | 编辑器里手动挖/放的方块，保存时增量写回 `structure.nbt`，不整块重截 | `structure/StructurePatch.kt`、`editor/*` |
| 生成播报 | 去掉坐标和"[点击传送]"文字，整条消息可点击传送 | `generation/GenerationDirector.kt` |
| 颜色代码兼容 | 模板 `display: '&6...'` 正常转 `§6`，不再显示字面量 `&6` | `template/TemplateRegistry.kt` |

## 1. 地形适配 fit

QR 原生只按 y / 群系放，不看地形：地下结构可能悬空，也可能整座埋进石头里。本分支在 `AnchorPlacer.tryPlace` 里加了一道采样检查，不合格就换点重试。

`template.yml`：

```yaml
generation:
  y: "[-53;-10]"
  fit:
    mode: shell          # shell=只采外壳六面；volume=整体；signature=只查特征方块
    min-score: 0.85      # 合格方块占比低于这个值就放弃
    scan-radius: 16      # signature 模式的球形扫描半径
```

三种模式：

| 模式 | 判什么 | 适用 |
|---|---|---|
| `shell` | 结构外壳所在位置，世界里是不是固体 | 地下遗迹（默认） |
| `volume` | 结构整体（含内部空气）是否都在固体里 | 实心结构，洞穴结构不要用 |
| `signature` | 附近有没有指定方块 | 滴水石洞、繁茂洞穴等 |

滴水石洞的例子（不依赖群系，资源世界周刷没这个群系也能放）：

```yaml
fit:
  mode: signature
  min-score: 0.8
  scan-radius: 16
  require-blocks: [DRIPSTONE_BLOCK, POINTED_DRIPSTONE]
  min-block-count: 20
```

## 2. 绝对 Y 区间

原来的 `y` 只支持 `surface` / `underground` / `+[3;12]`（相对地表）这类写法。周刷资源世界地表高低不一，相对深度会把"深层遗迹"放到 y=100。

现在支持绝对区间：

```yaml
generation:
  y: "[-53;-10]"   # 结构底部随机落在 y=-53 ~ -10
```

## 3. 每周开箱限额

四个作用域，各自独立计数、独立额度：

| 作用域 | 默认额度 | 说明 |
|---|---|---|
| `ruin` | 16 | 普通遗迹容器箱 |
| `boss` | 3 | BOSS 击杀后的奖励箱 |
| `slot` | 3 | 净化老虎机 |
| `vanilla` | 5 | 原版遗迹箱（`lootTable != null`），默认不计数 |

`config.yml`：

```yaml
weekly-limit:
  enabled: true
  reset-day: MONDAY
  reset-hour: 4
  count-vanilla: false
  scopes:
    ruin:
      per-week: 16
      permission-bonus:
        qinhruins.limit.ruin.vip: 4
        qinhruins.limit.ruin.mvp: 8
      temporary-bonus:
        - start: "2026-12-24"
          end: "2026-12-26"
          amount: 8
          reason: "圣诞节"
  over-limit:
    mode: chest          # chest=开补偿箱；reward=直接给；deny=只提示
    title: "§6本周开箱次数已用完"
    rows: 3
    table: compensation
    once-per-chest: true
```

- 权限加成：玩家有对应权限就加次数，可叠加。
- 临时加成：按日期区间生效，适合节假日。
- 超额补偿：`mode: chest` 会打开一个只读的补偿箱（`loottables/compensation.yml`），每格每周只能领一次，避免刷。
- 变量（需 PlaceholderAPI）：`%qinhruins_weekly_used%`、`%qinhruins_weekly_limit%`、`%qinhruins_weekly_remaining%`、`%qinhruins_weekly_reset%`，加 `boss_` / `slot_` / `vanilla_` 前缀取对应作用域。

## 4. 遗迹保护

| 对象 | 行为 |
|---|---|
| 战利品箱 | 不可破坏，提示"战利品箱无法被摧毁" |
| 矿物 / 矿物块 / 粗矿块 | 正常挖、正常掉落、不恢复 |
| 其他结构方块 | 可挖，不掉落，`restore-delay-ticks`（默认 200 = 10 秒）后原地恢复 |
| 玩家放置的方块 | 允许放（垫脚），`place-restore-ticks`（默认 200）后自动消失 |
| 爆炸 | 遗迹内非矿物方块从爆炸破坏列表里剔除 |

```yaml
protection:
  chests: true
  chest-message: "§c战利品箱无法被摧毁"
  no-drop: true
  restore-delay-ticks: 200
  place-restore-ticks: 200
  ore-tags: ["#minecraft:ores", "#minecraft:beacon_base_blocks"]
  ore-blocks: [RAW_IRON_BLOCK, RAW_COPPER_BLOCK, RAW_GOLD_BLOCK]
```

恢复只在该位置仍是空气时执行，不会覆盖玩家后来放的东西。

## 5. 编辑器增量保存

上游 `/qr editor save` 只写 `blueprint.yml`，不动结构文件。本分支改成**增量写回**：

- 编辑器会话里只记录玩家的 `BlockPlaceEvent` / `BlockBreakEvent`
- 水流、沙落、爆炸、活塞等环境变化**不记录**
- 保存时把这些格子写进 `structure.nbt`，其他方块原样保留
- 写临时文件再替换；第一次修改留 `structure.nbt.bak`

```
/qr editor start <模板id>
   （挖 / 放，随便改）
/qr editor save
   → 写 blueprint.yml + 写回结构，提示"已写回 N 个方块"
```

> 改的是模板文件。已经刷出来的那座遗迹不会变，要 `/qr remove <锚点>` 后重刷才会用新结构。

## 6. 生成播报

```
§e[遗迹] §6<名字> §7已生成
```

没有坐标、没有"[点击传送]"字样；整条消息可点击，悬停提示"点击传送到 <id>"。

## 7. 其他修复

- 模板 `display` 支持 `&` 颜色代码（`&6` → `§6`）。
- 新增 `loottables/compensation.yml`（超额补偿箱战利品表），首次启动自动释放。
- 中文/英文语言文件补全新增提示。

## 快速开始

1. 安装 **QinhCoreLib 1.3.0+**（硬依赖），再把 QinhRuins 放进 `plugins/`。
2. 服务端 **Paper / Purpur / Spigot 1.21.11+**，**Java 25+**。
3. 用 `/qr editor` 或 `_example` 模板做第一座遗迹。
4. `/qr scatter` 预生成，或直接让玩家探索。

## 构建

```bash
mvn -B -DskipTests package
```

- JDK 25+、Maven 3.9+
- 内存小的机器建议：`MAVEN_OPTS="-Xmx900m -XX:ActiveProcessorCount=2"`（默认堆可能 `os::commit_memory failed`）
- 产物：`target/QinhRuins-1.1.0.jar`

## 兼容性与已知限制

- **26.2**：可加载运行，但上游未官方声明测试；启动会有 `compatibility.strict-version-check` 警告。
- **地表贴合**：QR 按固定 y 放置，不会像 BetterStructures 那样随地形起伏贴合；地表结构可能切山或悬空。
- **已生成锚点**：改模板、改结构后都需要 `/qr remove` 再重刷，旧锚点不会自动更新。
- **地形适配**只在自然生成时生效，`/qr spawn` 手动放置会绕过 fit。

## 链接

- 上游：[JIULIVE/QinhRuins](https://github.com/JIULIVE/QinhRuins)
- 文档：[QinhRuins Docs](https://jiulive.github.io/Qinh-docs/zh-Hans/QR/)
- 本分支改动提交记录：[commits](https://github.com/HaiXi2912/QinhRuins/commits/main)

---

*Part of the Qinhuai plugin series.*
