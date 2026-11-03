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

**动了 `:baselineprofile` 就再跑一条**：`./gradlew :baselineprofile:compileNonMinifiedReleaseKotlin`。它不在上面任何一条链里 —— 那个模块只在生成 profile 时才编译，所以连语法错都能一路溜到 CI，代价是一次起模拟器的六十分钟。（真栽过：KDoc 里写了 `androidx/navigation/` 加通配星号，那个 `/*` 序列在 Kotlin 里会开一层**嵌套**块注释，KDoc 结尾的 `*/` 只关掉内层，外层一路吃到文件末尾。）

---

## UI 统一层（硬规则）

所有 UI 尺寸、动效、颜色、圆角、排版**一律走令牌 / 封装组件**，不写裸值。加新令牌前先问一句：「现有的哪个不够用？」

### 尺寸 → `app/.../ui/components/Dimens.kt`

全部落在 **4dp 栅格**。常用：`gapXS`(4)/`gapS`(8)/`gapM`(12)/`gapL`(16)/`gapXL`(24)/`gapXXL`(32)；页面 `screenPadding`(20)；设置行 `rowHorizontal`(20)/`rowVertical`(10)；内容列表行 `listHorizontal`(16)/`listVertical`(4)；`sectionHeaderTop`(16)、`chipSpacing`(4)、`buttonMinHeight`(40)；图标 `iconSm`(18)/`iconMd`(24)/`iconLg`(32)/`iconXL`(48)；`touchTarget`(48)；`buttonSpinner`(18)；封面 `coverThumbWidth/Height`、详情 `coverDetailWidth/Height`、书架网格 `bookshelfGridMin`；输入高 `searchFieldHeight`/`compactFieldHeight`（均为 40）。**禁止**在页面里写 `16.dp`、`10.dp` 之类；同类元素跨页面尺寸必须一致。

**Expressive 管形态与动效，Compact 管密度：** 继续用 `MaterialExpressiveTheme`、带 `shapes()` 的按钮/Chip/图标按钮重载、ListItem 交互重载；默认行内边距用 `ContentListDefaults.CompactPadding`，带封面/大图标预览的行才显式 `ComfortablePadding`。别为了「收矮」退回普通 `MaterialTheme` 或外层另搓 `Surface` 冒充列表行。

### 动效 → `app/.../ui/components/Motion.kt`

**组件与浮层都以 `MaterialTheme.motionScheme` 为唯一来源**（整页转场与阅读器开合是下面列明的例外）。优先用 `topBarEnter/Exit`、`bottomBarEnter/Exit`、`scrimEnter/Exit`、`expandEnter/Exit`；整页转场由 `ui/nav/PageSlide.kt` 统一做，页面别自己加进退动画；需要裸 spec（`animateItem`、`AnimatedContent`）时读令牌，**别裸写 `tween(300)`**。
- **spatial / effects 分工**（M3 约定，混了会难看）：位移与尺寸用 `defaultSpatialSpec()`，纯视觉属性（alpha、颜色）用 `defaultEffectsSpec()`。
- **「退场比入场快」**：帮手里入场 `default*`、退场 `fast*`。
- **系统「移除动画」**：`InkwellTheme` 把 `motionScheme` 换成 `InstantMotionScheme`；帮手里不必再判 `animationsEnabled()`。`animateItem` 仍可传 `null` 省插值；reader 翻页在主题外。
- **页面进退**：共享轴 X —— 新页整屏从右滑入、旧页左让约 1/4（返回镜像）；**不要加 fade**（半透明叠字会退化成渐隐渐显）。用**不回弹的 spring**（`PAGE_ENTER_STIFFNESS` / `PAGE_EXIT_STIFFNESS`，dampingRatio = 1），位移由 `PageSlide.kt` 的页面装饰器自己做。两条都实测过、别改回去：① 令牌 spring 带回弹，整页上就是终点冲过头再爬回来的「顿一下」；② 内置 `slideIn/OutHorizontally` 或 tween 在转场被打断（快速进退、页面掉头）时会被 Compose 换成它自己的默认 spring，掉头那页起步慢、尾巴一像素一像素地爬 —— spring 是被打断时唯一会保留的 spec。
- **阅读器开合仍硬编码 tween**（`READER_ENTER_MS` / `READER_EXIT_MS`）：与 splash 窗口咬合，别改成令牌 spring。
- **侧滑返回用线性 tween**（`PageSlideMode.Predictive` / `PREDICTIVE_BACK_MS`）：侧滑时转场按手势进度 seek，spring 曲线前段太陡，页面在进度三成就冲到头、之后不跟手。别改成令牌 spring。
- 以上三处（页面进退、阅读器开合、侧滑返回）是动效层仅有的刻意例外。

### 主题入口 → `MaterialExpressiveTheme`

App 走 **M3 Expressive**。主题入口有两个 —— 全局 `InkwellTheme`（`ui/theme/Theme.kt`）与阅读页浮层的 `ReaderThemeScope` —— **两个都必须**是 `MaterialExpressiveTheme`。任一处退回普通 `MaterialTheme` 都会把 `LocalUsingExpressiveTheme` 关掉，那一片区域的组件就悄悄变回非 Expressive 形态（编译不报错，只是长得不一样）。另有一处**局部覆盖**：书籍详情的封面取色 `CoverTintedTheme`（`ui/detail/CoverTint.kt`），只换 primary 一族、surface 一族照抄外层，同样是 `MaterialExpressiveTheme`，`motionScheme` 沿用外层的。

「跟随壁纸」是一个预设 id（`AppThemes.DYNAMIC`），日/夜两槽都能选；解析统一走 `rememberResolvedScheme`（MainActivity 设窗体底色、`InkwellTheme` 铺整树共用这一个结果），别在别处直接调 `AppThemes.resolve` 而漏掉壁纸配色。

`motionScheme` 这个参数**必须显式传**，别省：它默认 `null`，而 `null` 的含义是「沿用外层主题的方案」而非「填 expressive」—— 根节点没有外层，省掉就退成 `MotionScheme.standard()`，结果是组件形态换了、动效没换，编译与单测都看不出来。

代价记清楚：Expressive 只在 material3 **1.5.0-alpha** 通道上（1.4.0-beta01 把这批 API 整批摘出了稳定线），所以 compose 依赖用的是 **`compose-bom-alpha`**，整个 Compose 栈都在预发布线。升级 BOM 前先读 release notes 的 breaking change 段，别盲升。

### 圆角 → `MaterialTheme.shapes`

刻度在 `Theme.kt`：`extraSmall`(4)/`small`(8)/`medium`(12)/`large`(16)/`extraLarge`(28，对齐 M3 Dialog)。用 `MaterialTheme.shapes.medium` 等，**不要**裸写 `RoundedCornerShape(12.dp)`。

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
| 按钮（带 loading，不撑大） | `PrimaryButton` / `SecondaryButton`（`AppButtons.kt`）；浅底实心的次级按钮用 `TonalButton`（和分段按钮的未选中段同一类，别在一排实心按钮里夹描边按钮） |
| 图标按钮 | `AppIconButton`（`AppButtons.kt`）；顶栏返回键一律 `BackButton`。**别裸写 `IconButton`** —— 裸写的落到不带 `shapes` 的旧重载，按下去没有 Expressive 的圆角形变，同一条顶栏上就分成两派手感 |
| 有标题的内容页顶栏 | `AppTopBar` + `rememberAppTopBarScroll()` + `Modifier.topBarScroll(...)`（`AppTopBar.kt`，Expressive `MediumFlexibleTopAppBar`）。**三样必须配齐**：只换组件不接滚动＝白送一条永久变高的顶栏。四类例外仍用经典窄栏，理由写在 `AppTopBar` 的 KDoc 与各页注释里（标题位是交互控件的搜索页/发现页、带多选态的书架与书源管理） |
| 顶栏/工具条搜索框 | `SearchField`；对话框/表单行内 `CompactTextField`（`AppTextField.kt`）。两者是手搓 `BasicTextField`（M3 `TextField` 最小 56dp 塞不下），但配色形状取 `TextFieldDefaults.roundedShape` / `tonalColors()`，别自己发明底色 |
| 多行文本输入 | `CompactTextArea`（同文件）：同一套 tonal 色，高度 `textAreaMinHeight`，圆角走 `shapes.large`。**不要**用 `roundedShape` —— 那是按高度一半算的胶囊，160dp 高的框会变成两头大圆 |
| 带 label 的整页表单输入 | WebDAV / 净化规则等仍用 M3 `OutlinedTextField`（封装层暂无带 label 变体）。意见反馈这类无 label 的整页输入走 `CompactTextArea` / `CompactTextField`，别再塞描边框 |
| 滑块 | `AppSlider`（`AppSlider.kt`）：挂官方 `Slider`，自绘细轨 + 圆点（槽高对齐 M3 `TrackHeight`）。`activeColor` 只给阅读器浮层（纸色背景）用 |
| 单选横滚 chip 条 | `ChipRow`（内部固定横滚，`contentPadding` 给首尾边距；额外动作 chip 走 `trailing`，别再手搓 `Row`+`FilterChip`）；单个独立 chip 用 `AppFilterChip`（同样为了按压形变的那个重载） |
| 几档里选一档（固定 2~5 档） | `SegmentedChoice`（`SegmentedChoice.kt`，Expressive 连体按钮组）：选中段实心填强调色，比 chip 的淡底分得清。选项多或会变的（字体、方案、七档间隔）仍用 `ChipRow` |
| 列表行密度 | 默认 `CompactPadding`；带封面/大图标的行（`BookListRow`、书架列表、`AppIconSheet`）用 `ComfortablePadding` |
| 分段 Tab + 内容切换 | `AppTabRow` + `AppTabContent`（`AppTabs.kt`）。内部是 `PrimaryTabRow`（短粗圆角指示条，Secondary 那根细线看不出切换）；切换过渡横移只取 1/8 宽且**关掉 SizeTransform** —— 所以内容区**必须定高**，否则又变成「切 Tab 把面板顶高」 |
| 设置行 / 开关行 / 分组小标题 | `SettingRow` / `SwitchRow` / `SectionHeader`（`SettingRow.kt`；内部走 `ContentListItem`）。设置主页那一列入口行带 `icon`（圆底图标），二级页保持纯文字 |
| 设置分组卡 | `SettingGroup` + `SettingRow(..., position = First/Middle/Last/Alone)` / `SwitchRow(..., position = …)`；底栏/选择面板等「一条一卡」保持 `position = null` |
| 设置类页面骨架 | **只有一种**：灰底 + `SectionHeader` + `SettingGroup`（左右 16dp），控件全部装进卡里。卡里不是「行」的内容（表单、分段按钮、说明）包 `SettingBlock`；卡下的说明小字用 `SettingFootnote`。别再整页内缩 20dp、把输入框和色板直接铺在灰底上（WebDAV、主题外观从前就是这样，卡片比别页窄一截） |
| 内容列表行（可点/单选/多选） | `ContentListItem` / `ChapterListItem`（`ContentListItem.kt`）；书籍行再用 `BookListRow` |
| 从 N 项选一个（底部面板） | `OptionPickerSheet`（`OptionPicker.kt`） |
| 空态 / 错误态 | `EmptyState`（`Common.kt`）/ `ErrorState`（`ErrorState.kt`），错误态**必须**带重试出口 |
| 整页加载态 | `LoadingState`（`Common.kt`） |
| 不确定进度 | `AppLoadingIndicator`（`Common.kt`）：默认尺寸用 Expressive `LoadingIndicator`；**小于容器下限时自动回退** `CircularProgressIndicator`（强缩会炸约束）。调用点统一走封装，别裸写两种指示器 |
| 确定进度条 | `DeterminateProgressBar`（`Common.kt`，Expressive `LinearWavyProgressIndicator`）；搜索/书源校验/更新下载/离线缓存走它，别裸写 `LinearProgressIndicator` |
| 阅读进度（静止的读数） | `ReadingProgressMeter`（`Common.kt`）：书封底边、「继续阅读」卡片。波浪线表示「有活在跑」，静止的进度别用 `DeterminateProgressBar` |
| 相对时间（「3 小时前」「昨天」） | `formatRelativeTime`（`RelativeTime.kt`），按日历天而不是 24 小时算 |
| 一次性提示（Snackbar） | `MessageBus` + `CollectMessages` + `AppSnackbarHost`（`Messages.kt`）。**居中、随文案收窄的胶囊**（短提示是药丸，长提示才横向展开）；色/圆角仍取主题 `inverseSurface` / `extraLarge`。页面别自己写 `snackbarHost = { SnackbarHost(...) }` |
| 对话框 | `AppAlertDialog`（`AppDialog.kt`）：标题 + 正文/内容 + 等宽 `SecondaryButton` / `PrimaryButton`。别裸写 `AlertDialog` + `TextButton`，也别手搓居中 `Surface` 冒充弹层 |

出现「多个页面重复相似 UI」时，抽成组件而不是复制。

---

## 无障碍（提交前自查）

- **语义角色**：可点击的 `Row/Box` 加 `Modifier.clickable(role = Role.Button)`；开关行用 `toggleable(role = Switch)` 且把行内 `Switch` 的 `onCheckedChange = null`（纯展示）；单选行用 `selectable(role = RadioButton)`。别让读屏出现「行 + 控件」两个焦点。
- **可访问名称**：`AppIconButton` 必须有 `contentDescription`（装饰性的显式 `null`）。纯图形的可点元素（如色板）用 `Modifier.semantics { contentDescription = ... }` 补名字 —— 名字 Text 在可点区之外的，读屏念不到。
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
- **多步写库入事务**：删目录+写目录+更 book 行这类，用 `db.withWriteTransaction { }`（Room 3 把 2.x 的 `withTransaction` 按读写拆成了两个）包住，中途被杀不留半套数据。事务内**别**放大量文件 IO（如逐章 `cache.has`），先在事务外算好。
- **一次性提示用 `MessageBus`（SharedFlow），不用 `StateFlow`**：StateFlow 会对相同值去重，连续两次相同提示第二次会被静默吞掉。
- **保存进度用 `NonCancellable`**：`viewModelScope.launch(NonCancellable) { saveProgress() }`，别让页面销毁把最后一次进度写丢。
- **文件缓存原子写**：写临时文件再 `renameTo`（`File.createTempFile` 前缀 ≥ 3 字符），避免进程被杀留半截文件被当有效缓存。IO 一律切 `Dispatchers.IO`。
- **引擎公开入口自我确权**：`core` 的 `BookSourceEngine` 公开 suspend 入口内部 `withContext(Dispatchers.IO)`，调用方在不在主线程都安全。
- **Room 迁移**：只加列 / 新增表，带默认值，保留用户数据；破坏性迁移只在**降级**时兜底（`fallbackToDestructiveMigrationOnDowngrade`）。别删迁移链。用的是 **Room 3**（`androidx.room3`，KMP 重构后继线）：迁移体是 `override suspend fun migrate(connection: SQLiteConnection)`，SQL 走 `connection.execSQL`——照 2.x 的 `SupportSQLiteDatabase` 写法抄会编不过。
- **导航**：Jetpack **Navigation 3**（`NavBackStack` + `NavDisplay`）+ Material 3 Adaptive（`adaptive-navigation3` list-detail）+ **`lifecycle-viewmodel-navigation3`**。ViewModel **只**在 `entry { }` 内用 `org.koin.compose.viewmodel.koinViewModel` 创建（经 `rememberViewModelStoreNavEntryDecorator` 绑到 NavEntry；`koin-androidx-compose` 依赖已移除 —— 它的同名 `koinViewModel` 绑 Activity 的 store，退栈不清，别再加回来）。Screen 的 `viewModel:` 参数**不给默认值**，谁在 entry 里创建谁传。前进经 `InkwellNavigator.go`（栈顶同类替换）。**大 payload 不塞路由参数**（会随返回栈进 `onSaveInstanceState`，撑爆 Binder → `TransactionTooLargeException`）—— 用进程内 holder 按 key 暂存，路由只带短字段。阅读器始终全屏，不进双栏。

---

## 书源规则

自研 JSON Schema + Legado（阅读）书源兼容，详见 `README.md` §书源规则 / §Legado 书源兼容。当前正从自研 DSL 迁移到运行时原生 Legado 规则（文本源），引擎在 `core/.../source/`。改规则解析时先看 `core/src/test` 的既有测试了解预期，再动。

---

## 发布流程

- 版本号**唯一来源**：`gradle/libs.versions.toml` 的 `inkwell = "x.y.z"`。CI 校验 tag 必须等于 `v$版本`。
- 发布 = 打**附注 tag** `vx.y.z` 并推送 → 触发 `.github/workflows/release.yml`。
- **tag 注解正文 = GitHub Release 正文 = 应用内更新弹窗内容**。CI 会自动剔掉 `Co-Authored-By`/`Signed-off-by` trailer 和 `Full Changelog`。
- 带 `-` 后缀的 tag（如 `v0.1.4-beta.1`）自动标记为**预发布**，只推测试渠道。

### 发行说明格式

面向用户、**纯文本**（应用内按纯文本渲染，别用 `###` / `*` 等 Markdown）。结构对齐常见阅读类 App 的 Release 习惯：

```
更新内容

feat：一句话说明用户能感知到的新能力
fix：一句话说明修了什么问题
优化：一句话说明体验/性能上的改进
```

硬约束：
- **第一行固定写** `更新内容`，下面空一行再列条目。
- 每条一行，以类型前缀开头：`feat：` / `fix：` / `优化：`（必要时可加 `文档：`）。用全角冒号。
- 写**用户能感知的结果**，别写实现细节（别提类名、模块名、依赖坐标、误诊过程）。
- 条目按 feat → fix → 优化 排；同类型里用户感知更强的靠前。
- 别写成长段散文，也别把多个改动揉进一条。

## 提交信息

- 讲清「改了什么、为什么」，与仓库既有 commit 风格一致（中文、分段）。
- 结尾加：`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`（AI 参与时）。
