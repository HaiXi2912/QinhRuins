# 更新日志

本文件记录本分支相对上游 [JIULIVE/QinhRuins](https://github.com/JIULIVE/QinhRuins) 的改动。

## [未发布] - 2026-11-12

### 修复

- **遗迹名显示字面量 `&6`**
  `template.yml` 的 `display` 常用 `&6` / `&b` 这类传统颜色代码，而播报、标题等消息使用 `§` 转义。名字被原样拼进消息后，前缀就显示成了文字 `&6`，而消息本身的颜色正常——所以看起来像"颜色对但 &6 没转换"。
  现在 `TemplateRegistry` 载入模板时统一把 `&` 转成 `§`，所有模板名在任何消息里都能正确上色。

### 新增

- **生成播报改为可点击传送**
  遗迹自然生成时发给管理员（`qinhruins.admin`）的播报，去掉末尾的 `(/qr tp <锚点id>)` 纯文本，改为可点击的 `[点击传送]` 按钮：
  - `ClickEvent.runCommand("/qr tp <锚点id>")`，点一下直接传送；
  - `HoverEvent.showText` 悬停显示目标锚点；
  - 仍复用 `/qr tp` 命令与 `qinhruins.admin` 权限，没有新增权限面。

- **语言键**
  - `cmd.gen-announce-tp`：按钮文字（`§b[点击传送]` / `§b[Click to teleport]`）
  - `cmd.gen-announce-tp-hover`：悬停文字（`§7点击传送到 §f{id}`）

### 变更文件

- `src/main/kotlin/com/qinhuai/ruins/template/TemplateRegistry.kt`
- `src/main/kotlin/com/qinhuai/ruins/generation/GenerationDirector.kt`
- `src/main/resources/lang/zh_cn/commands.yml`
- `src/main/resources/lang/en_US/commands.yml`
- `CHANGELOG.md`（新增）

### 兼容性

- 仅改消息渲染与模板名载入，不影响存档、模板格式、锚点数据。
- 其它语言（es/fr/ru/vi/zh_tw）未改，缺键时自动回退到 `en_US`。
