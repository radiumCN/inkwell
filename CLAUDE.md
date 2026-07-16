# Inkwell 开发规范

给后续开发（含 AI 辅助）的**统一约束**。写新功能、改 UI 前先读这一份；细节的「为什么」在对应文件的 KDoc 里，这里只列**必守的规则**与去哪找。

原则：**改动要读起来像周围的代码** —— 匹配既有的注释密度、命名与惯用法。这个项目习惯用注释讲清「为什么这样、不这样会怎样」，新代码请延续。

---

## 模块结构与边界

| 模块 | 内容 | 依赖约束 |
|---|---|---|
| `core/` | 书源引擎、规则解析、分页模型无关的解析、备份合并、Legado 兼容 | **纯 JVM**，不依赖 Android/Compose，必须能脱离 Android 单测 |
| `reader/` | 分页、渲染、翻页、测量、阅读器 API | Android 库，但排版核心尽量可测（见 `reader/src/test`） |
| `app/` | UI（Compose）、ViewModel、数据层（Room/DataStore/repo）、更新、DI | 依赖上面两个 |

- **可测优先**：核心逻辑（解析、分页、书源规则、备份合并）下沉到纯 JVM，脱离 Android 也能单测。新逻辑优先放能测的地方。
- `reader` 的公开 API 用 ARGB `Long` 表颜色，**不要**让 reader API 反向依赖 Compose 类型。

## 构建与测试

```bash
export JAVA_HOME=/opt/java/jdk-21.0.11+10   # 需 JDK 21
./gradlew :core:test :reader:test :app:testDebugUnitTest   # 单测
./gradlew assembleDebug                                     # 整包
```

提交前至少跑通 `assembleDebug` + 三个模块单测。

---

## UI 统一层（硬规则）

所有 UI 尺寸、动效、颜色、圆角、排版**一律走令牌 / 封装组件**，不写裸值。加新令牌前先问一句：「现有的哪个不够用？」

### 尺寸 → `app/.../ui/components/Dimens.kt`

全部落在 **4dp 栅格**。常用：`gapXS`(4)/`gapS`(8)/`gapM`(12)/`gapL`(16)/`gapXL`(24)/`gapXXL`(32)；页面 `screenPadding`(20)；设置行 `rowHorizontal`(20)/`rowVertical`(14)；内容列表行 `listHorizontal`(16)/`listVertical`(8)；图标 `iconSm`(18)/`iconMd`(24)/`iconLg`(32)/`iconXL`(48)；`touchTarget`(48)；`buttonSpinner`(18)；封面 `coverThumbWidth/Height`。**禁止**在页面里写 `16.dp`、`10.dp` 之类；同类元素跨页面尺寸必须一致。

### 动效 → `app/.../ui/components/Motion.kt`

时长/曲线定死在此：入场 `ENTER_MS`(220)、退场更快 `EXIT_MS`(140)、导航 `NAV_*`。用现成帮手 `topBarEnter/Exit`、`bottomBarEnter/Exit`、`scrimEnter/Exit`、`expandEnter/Exit`。
- **所有自定义动画必须尊重系统「移除动画」** —— 用 `animationsEnabled()`（ContentObserver 实时监听）包一层，关了就 `tween(0)`。别裸写 `tween(300)`、别让 `animate*AsState`/`AnimatedVisibility`/`Crossfade` 逃出这套。
- 框架内建动画（ModalBottomSheet/DropdownMenu/PullToRefresh）不受此约束（无公开定制口），不计。

### 圆角 → `MaterialTheme.shapes`

刻度在 `Theme.kt`：`extraSmall`(4)/`small`(8)/`medium`(12)/`large`(16)/`extraLarge`(24)。用 `MaterialTheme.shapes.medium` 等，**不要**裸写 `RoundedCornerShape(12.dp)`。

### 颜色 → `MaterialTheme.colorScheme` 语义令牌

页面颜色一律走语义令牌（`primary`/`surface`/`onSurface`/`surfaceContainer*`/`error`…），**不写十六进制、不写 `Color.Gray`**。配色由 `AppThemes.kt` 从「强调色 + 背景色」推导整套（含 `surfaceContainer*` 全槽位）。
- 正文性小字用 `onSurfaceVariant`（对比度达标），**不要**用 `outline`（浅色下仅约 3.9:1，达不到 WCAG 4.5:1）。`outline` 只作边框/分隔线。
- **唯一例外**：阅读器**内容区**颜色（纸色/字色）走 `ReaderTheme` / `ReaderThemeScope`（`app/.../ui/reader/ReaderThemeScope.kt`），这是有意的独立主题；但阅读器 UI 的尺寸/动效仍走上面的令牌。阅读器里的浮层用 `ReaderThemeScope` 包裹，让 Chip/Slider/分隔线自动协调，别挨个传色。

### 排版 → `MaterialTheme.typography`

用角色（`display/headline/title/body/label`），别裸写 `fontSize = 14.sp`。正文 ≥ `bodySmall`(12sp)。

### 复用封装组件（别手搓）

有封装就用封装，不要复制粘贴裸 M3 组件：

| 需求 | 用这个（`app/.../ui/components/`） |
|---|---|
| 按钮（带 loading，不撑大） | `PrimaryButton` / `SecondaryButton`（`AppButtons.kt`） |
| 顶栏/工具条搜索框 | `SearchField`；对话框/表单行内 `CompactTextField`（`AppTextField.kt`） |
| 带 label 的整页表单输入 | 直接用 M3 `OutlinedTextField`（封装层暂无带 label 变体；对话框里也统一用它） |
| 单选横滚 chip 条 | `ChipRow`（内部固定横滚，`contentPadding` 给首尾边距） |
| 设置行 / 开关行 / 分组小标题 | `SettingRow` / `SwitchRow` / `SectionHeader`（`SettingRow.kt`） |
| 从 N 项选一个（底部面板） | `OptionPickerSheet`（`OptionPicker.kt`） |
| 空态 / 错误态 | `EmptyState`（`Common.kt`）/ `ErrorState`（`ErrorState.kt`），错误态**必须**带重试出口 |
| 一次性提示（Snackbar） | `MessageBus` + `CollectMessages` + `AppSnackbarHost`（`Messages.kt`） |

出现「多个页面重复相似 UI」时，抽成组件而不是复制。

---

## 无障碍（提交前自查）

- **语义角色**：可点击的 `Row/Box` 加 `Modifier.clickable(role = Role.Button)`；开关行用 `toggleable(role = Switch)` 且把行内 `Switch` 的 `onCheckedChange = null`（纯展示）；单选行用 `selectable(role = RadioButton)`。别让读屏出现「行 + 控件」两个焦点。
- **可访问名称**：`IconButton` 必须有 `contentDescription`（装饰性的显式 `null`）。纯图形的可点元素（如色板）用 `Modifier.semantics { contentDescription = ... }` 补名字 —— 名字 Text 在可点区之外的，读屏念不到。
- **触控目标 ≥ 48dp**（`Dimens.touchTarget`）。图标可小，可点区不能小；别用 `Modifier.size(32.dp)` 把 `IconButton` 钉到下限以下。
- **对比度**：正文 ≥ 4.5:1（见「颜色」）。阅读纸张主题在 `ReaderThemeContrastTest` 里钉死 ≥ 7:1，新增纸色配色不合格测试直接挂。
- **系统返回键**：有暂态浮层（菜单/面板/选区/多选模式）时用 `BackHandler` 先收起它们，再退页面。
- **edge-to-edge / 键盘**：可滚动的表单/列表在 `enableEdgeToEdge`（`MainActivity`）下键盘会遮挡，给它们加 `Modifier.imePadding()`。

---

## 编码约定（数据 / 协程 / 状态）

这些是**踩过坑**的硬约束，违反会重新引入已修的 bug：

- **可取消操作用 `Job` 管理**：搜索、换源、加载更多、分页这类可被新操作打断的任务，持有 `Job` 并在启动前 `cancel()` 旧的；否则旧结果会串进新操作。
- **`catch (e: Exception)` 前先 rethrow 取消**：`if (e is CancellationException) throw e`。吞掉协程取消会把「翻页取消了上一次加载」误判成「章节读不出来」，进而误触发自动换源、清缓存。
- **写库前重读最新行**：慢网络往返（换源/追更）后，别用进入时的实体快照整行 `copy` 覆盖 —— 期间用户可能已改了进度/分组。先 `dao.getById()` 拿最新行再改。
- **多步写库入事务**：删目录+写目录+更 book 行这类，用 `db.withTransaction { }` 包住，中途被杀不留半套数据。事务内**别**放大量文件 IO（如逐章 `cache.has`），先在事务外算好。
- **一次性提示用 `MessageBus`（SharedFlow），不用 `StateFlow`**：StateFlow 会对相同值去重，连续两次相同提示第二次会被静默吞掉。
- **保存进度用 `NonCancellable`**：`viewModelScope.launch(NonCancellable) { saveProgress() }`，别让页面销毁把最后一次进度写丢。
- **文件缓存原子写**：写临时文件再 `renameTo`（`File.createTempFile` 前缀 ≥ 3 字符），避免进程被杀留半截文件被当有效缓存。IO 一律切 `Dispatchers.IO`。
- **引擎公开入口自我确权**：`core` 的 `BookSourceEngine` 公开 suspend 入口内部 `withContext(Dispatchers.IO)`，调用方在不在主线程都安全。
- **Room 迁移**：只加列 / 新增表，带默认值，保留用户数据；破坏性迁移只在**降级**时兜底（`fallbackToDestructiveMigrationOnDowngrade`）。别删迁移链。
- **导航**：`navigate` 统一带 `launchSingleTop`（防双击重复入栈）。**大 payload 不塞路由参数**（会随返回栈进 `onSaveInstanceState`，撑爆 Binder → `TransactionTooLargeException`）—— 用进程内 holder 按 key 暂存，路由只带短字段。

---

## 书源规则

自研 JSON Schema + Legado（阅读）书源兼容，详见 `README.md` §书源规则 / §Legado 书源兼容。当前正从自研 DSL 迁移到运行时原生 Legado 规则（文本源），引擎在 `core/.../source/`。改规则解析时先看 `core/src/test` 的既有测试了解预期，再动。

---

## 发布流程

- 版本号**唯一来源**：`gradle/libs.versions.toml` 的 `inkwell = "x.y.z"`。CI 校验 tag 必须等于 `v$版本`。
- 发布 = 打**附注 tag** `vx.y.z` 并推送 → 触发 `.github/workflows/release.yml`。
- **tag 注解正文 = GitHub Release 正文 = 应用内更新弹窗内容**，所以**写纯文本、别用 Markdown**，面向用户描述改了什么。CI 会自动剔掉 `Co-Authored-By`/`Signed-off-by` trailer 和 `Full Changelog`。
- 带 `-` 后缀的 tag（如 `v0.1.4-beta.1`）自动标记为**预发布**，只推测试渠道。

## 提交信息

- 讲清「改了什么、为什么」，与仓库既有 commit 风格一致（中文、分段）。
- 结尾加：`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`（AI 参与时）。
