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

App 的 UI 库是 **[Miuix](https://github.com/compose-miuix-ui/miuix)**（HyperOS 设计语言，`top.yukonga.miuix.kmp`），**不是** Material 3 —— `androidx.compose.material3` 已从依赖里删掉，别再加回来（`androidx.compose.material3.adaptive.*` 是独立的平板双栏布局库，照用；`material-icons-extended` 照用）。

所有 UI 尺寸、动效、颜色、圆角、排版**一律走令牌 / 封装组件**，不写裸值。加新令牌前先问一句：「现有的哪个不够用？」

### 尺寸 → `app/.../ui/components/Dimens.kt`

全部落在 **4dp 栅格**（Miuix 自己的尺寸也都在栅格上）。常用：`gapXS`(4)/`gapS`(8)/`gapM`(12)/`gapL`(16)/`gapXL`(24)/`gapXXL`(32)；页面 `screenPadding`(20)；内容列表行 `listHorizontal`(16)/`listVertical`(4)、行最小高 `listItemMinHeight`(56，对齐 Miuix `BasicComponent`)；`sectionHeaderTop`(16)、`chipSpacing`(4)/`chipHeight`(32)、`buttonMinHeight`(40)；窄顶栏 `toolbarHeight`(52，对齐 Miuix 收起后的顶栏)；图标 `iconSm`(18)/`iconMd`(24)/`iconXL`(48)；`touchTarget`(48)；`buttonSpinner`(18)/`loadingIndicator`(32)；封面 `coverThumbWidth/Height`、详情 `coverDetailWidth/Height`、书架网格 `bookshelfGridMin`；输入高 `searchFieldHeight`/`compactFieldHeight`（均为 40）。**禁止**在页面里写 `16.dp`、`10.dp` 之类；同类元素跨页面尺寸必须一致。

默认行内边距用 `ContentListDefaults.CompactPadding`，带封面/大图标预览的行才显式 `ComfortablePadding`。别在外层另搓 `Surface` 冒充列表行。

### 动效 → `app/.../ui/components/Motion.kt` + `AppTheme.motionScheme`

**我们自己写的组件动画与浮层帮手都以 `AppTheme.motionScheme`（`ui/theme/AppTheme.kt`）为唯一来源**（整页转场与阅读器开合是下面列明的例外）。Miuix 没有主题级的动效方案，`AppMotionScheme` 是照 M3 `MotionScheme` 的接口自己定义的（spatial 取几乎不回弹的 standard 那组，贴近 HyperOS 的「快、稳」）；Miuix 组件内部的动画（开关、弹窗滑入、按压缩放）是它自己的 spring，不读这里。优先用 `topBarEnter/Exit`、`bottomBarEnter/Exit`、`scrimEnter/Exit`、`expandEnter/Exit`；整页转场由 `ui/nav/PageSlide.kt` 统一做，页面别自己加进退动画；需要裸 spec（`animateItem`、`AnimatedContent`）时读令牌，**别裸写 `tween(300)`**。
- **spatial / effects 分工**（沿用 M3 约定，混了会难看）：位移与尺寸用 `defaultSpatialSpec()`，纯视觉属性（alpha、颜色）用 `defaultEffectsSpec()`。
- **「退场比入场快」**：帮手里入场 `default*`、退场 `fast*`。
- **系统「移除动画」**：`InkwellTheme` 把方案换成 `AppMotionScheme.Instant`；帮手里不必再判 `animationsEnabled()`。`animateItem` 仍可传 `null` 省插值；reader 翻页在主题外。
- **页面进退**：共享轴 X —— 新页整屏从右滑入、旧页左让约 1/4（返回镜像）；**不要加 fade**（半透明叠字会退化成渐隐渐显）。用**不回弹的 spring**（`PAGE_ENTER_STIFFNESS` / `PAGE_EXIT_STIFFNESS`，dampingRatio = 1），位移由 `PageSlide.kt` 的页面装饰器自己做。两条都实测过、别改回去：① 带回弹的 spring 在整页上就是终点冲过头再爬回来的「顿一下」；② 内置 `slideIn/OutHorizontally` 或 tween 在转场被打断（快速进退、页面掉头）时会被 Compose 换成它自己的默认 spring，掉头那页起步慢、尾巴一像素一像素地爬 —— spring 是被打断时唯一会保留的 spec。
- **阅读器开合仍硬编码 tween**（`READER_ENTER_MS` / `READER_EXIT_MS`）：与 splash 窗口咬合，别改成令牌 spring。
- **侧滑返回用线性 tween**（`PageSlideMode.Predictive` / `PREDICTIVE_BACK_MS`）：侧滑时转场按手势进度 seek，spring 曲线前段太陡，页面在进度三成就冲到头、之后不跟手。别改成令牌 spring。
- 以上三处（页面进退、阅读器开合、侧滑返回）是动效层仅有的刻意例外。

### 主题入口 → `MiuixTheme(colors = …)`

主题入口有两个 —— 全局 `InkwellTheme`（`ui/theme/Theme.kt`）与阅读页浮层的 `ReaderThemeScope` —— 另有一处**局部覆盖**：书籍详情的封面取色 `CoverTintedTheme`（`ui/detail/CoverTint.kt`），只换 primary / tertiaryContainer 一族、其余照抄外层。三处都是 `MiuixTheme(colors = …)`，从外层配色 `copy` 出来改相关槽位。

两条坑，编译和单测都看不出来：
- **colors 版 `MiuixTheme` 不提供 `LocalContentColor`**（controller 版才提供）。所以 `InkwellTheme` 根上铺了一层 Miuix `Surface`（`contentColor = onSurface`），`ReaderThemeScope` 也补了一层。新加主题入口时照做，否则没写颜色的 Text / Icon 一律是黑的，深色下黑字压黑底。
- **`MiuixTheme` 只持有一个 `Colors` 实例、换主题时原地改值**。别拿 `MiuixTheme.colorScheme` 对象当 `remember` 的 key —— 它永远不变，切了日夜，派生出来的颜色还停在上一套。key 用具体槽位的值（`base.primary`、`base.surfaceContainer`）。

「跟随壁纸」是一个预设 id（`AppThemes.DYNAMIC`），日/夜两槽都能选；颜色由 Miuix `ThemeController`（Monet 模式、不给 keyColor）读系统调色板生成。解析统一走 `rememberResolvedScheme`（MainActivity 设窗体底色、`InkwellTheme` 铺整树共用这一个结果），别在别处直接调 `AppThemes.resolve` 而漏掉壁纸配色。

代价记清楚：Miuix 是 **0.x 实验线**，作者明说 API 可能不打招呼就改；它对着 Compose 1.12 构建，跑在我们的 `compose-bom-alpha`（1.13 beta）上。升 Miuix 或 BOM 前先读 release notes 的 breaking change 段，别盲升。（BOM 当初为 M3 Expressive 才走 alpha 线，这条理由已经没了，回稳定 BOM 是可以单独评估的事。）

### 圆角 → `AppShapes`（`ui/theme/AppTheme.kt`）

Miuix 没有主题级 Shapes，组件按 Dp 收圆角。刻度：`extraSmall`(4)/`small`(8)/`medium`(12)/`large`(16，对齐 Miuix 卡片)/`extraLarge`(28，对齐 Miuix 底部面板)，每档同时给 Shape（`AppShapes.medium`，用于 `clip`/`background`/`border`）和 Dp 半径（`AppShapes.radiusMedium`，用于 Miuix 组件的 `cornerRadius` 参数与 `Modifier.squircleClip/squircleBackground`）。卡片、按钮这类「面」优先 squircle（HyperOS 的连续曲率圆角）。**不要**裸写 `RoundedCornerShape(12.dp)`。

### 颜色 → `MiuixTheme.colorScheme` 语义槽位

页面颜色一律走语义槽位，**不写十六进制、不写 `Color.Gray`**。配色由 `AppThemes.schemeFrom` 从「强调色 + 背景色」推导整套 Miuix `Colors`（五十来个槽位一个不漏）。
- **分层照 HyperOS，不照 M3**：页面底是 `surface`、卡片是 `surfaceContainer`、弹窗与底部面板是 `background`。浅色「灰底白卡」（卡就是预设纸色），深色「黑底亮卡」。M3 习惯里「页面 = background」在这里是错的。
- **槽位含义与 M3 不同**：`secondary` 是关着的开关轨道、`secondaryVariant` 是默认按钮底、`secondaryContainer` 是输入框底（都是中性灰）；淡强调底 + 强调色字用 `tertiaryContainer` / `onTertiaryContainer`（下拉菜单选中项、选中 chip、设置图标底）；`primaryContainer` / `onPrimaryContainer` Miuix 组件不读，保留 M3 含义。
- 正文性小字用 `onSurfaceVariantSummary`（对比度在 `AppThemesTest` 里钉住 ≥ 4.5:1，页面底和卡片上都测），**不要**用 `outline` / `dividerLine`（那是给边框/分隔线的）。
- **唯一例外**：阅读器**内容区**颜色（纸色/字色）走 `ReaderTheme` / `ReaderThemeScope`（`app/.../ui/reader/ReaderThemeScope.kt`），这是有意的独立主题；但阅读器 UI 的尺寸/动效仍走上面的令牌。阅读器里的浮层用 `ReaderThemeScope` 包裹，让开关/滑块/分隔线自动协调，别挨个传色。浮层底用 `readerOverlayColor()`、浮层里的卡用 `readerCardColor()`。

### 排版 → `MiuixTheme.textStyles`

用角色（`title1~4` / `headline1~2` / `subtitle` / `body1~2` / `footnote1~2` / `main` / `button`），别裸写 `fontSize = 14.sp`；要加粗用 `Text(fontWeight = …)`。正文 ≥ `footnote1`(13sp)；`footnote2`(11sp) 只给角标、标签这种非正文。注意 Miuix `Text` 的默认样式是 `main`(17sp)，比 M3 默认大一号 —— 字号要紧的地方显式给 style。
对照（从 M3 迁来时）：bodySmall→footnote1、bodyMedium→body2、bodyLarge→body1、labelSmall→footnote2、titleSmall→subtitle、titleMedium→headline2 + Medium、titleLarge→title3、headlineSmall→title2。

### 复用封装组件（别手搓）

有封装就用封装，不要直接铺 Miuix 原始组件（它们的默认值有几处不达标：图标按钮 40dp 低于触控下限、`SwitchPreference` 是双焦点、弹窗被调用方一关就掐断退场动画……封装层把这些都兜住了）：

| 需求 | 用这个（`app/.../ui/components/`） |
|---|---|
| 按钮（带 loading，不撑大） | `PrimaryButton`（强调色实心）/ `SecondaryButton`（默认灰实心）（`AppButtons.kt`）；卡片里的小号浅底按钮用 `TonalButton`（和分段控件的槽同一档）。HyperOS 没有描边按钮，别再引入 |
| 文字按钮（展开/收起、撤销、行尾「加入」） | `AppTextButton("文字", onClick)`：强调色文字（body2 + Medium）、无底色，可点高度保底 48dp、带按钮读屏角色；危险操作传 `contentColor = error`。字号要不一样的才用带 content 的重载，别再手写 `Text(…, body2, Medium)`。Miuix 的 `TextButton` 是**有底色**的按钮，不是这个 |
| 图标按钮 | `AppIconButton`（`AppButtons.kt`）；顶栏返回键一律 `BackButton`（Miuix 的细线箭头）。**别裸写 Miuix `IconButton`** —— 它默认 40dp，低于 48dp 触控下限，禁用时图标也不变灰 |
| 有标题的内容页顶栏 | `AppTopBar` + `rememberAppTopBarScroll()` + `Modifier.topBarScroll(...)`（`AppTopBar.kt`，Miuix 大标题 `TopAppBar`）。**三样必须配齐**：只换组件不接滚动＝白送一条永久变高的顶栏 |
| 窄顶栏（标题位放控件 / 多选态 / 书架首页） | `AppToolbar { … }` + 纯文字标题 `ToolbarTitle`（同文件）：52dp、自己让开状态栏。搜索页/发现页的标题位是交互控件，书架与书源管理有多选态，书架首页压着隐藏区与下拉刷新 —— 理由写在 `AppTopBar` 的 KDoc 里 |
| 下拉菜单（⋮、导入方式） | `AppDropdownMenu` + `AppMenuItem`（`AppMenu.kt`，Miuix `WindowDropdownPopup`）：和锚点放进同一个 `Box`。从 N 项选一个别用它，走 `OptionPickerSheet` |
| 顶栏/工具条搜索框 | `SearchField`；对话框/表单行内 `CompactTextField`（`AppTextField.kt`）。两者是手搓 `BasicTextField`（Miuix `TextField` 带浮动 label、最矮五十多 dp 塞不下），但底色/占位色/聚焦描边取 Miuix 输入框读的槽位，别自己发明 |
| 多行文本输入 | `CompactTextArea`（同文件）：同一套底色，高度 `textAreaMinHeight`，圆角 `radiusLarge`。**不要**用胶囊圆角 —— 160dp 高的框会变成两头大圆 |
| 带 label 的整页表单输入 | `AppFormField`（同文件，Miuix `TextField` 的浮动 label；报错时描边换 error 色、`supportingText` 在框外）。意见反馈这类无 label 的整页输入走 `CompactTextArea` / `CompactTextField` |
| 滑块 | `AppSlider`（`AppSlider.kt`，Miuix `Slider` 胶囊粗轨）：给 `label`（读屏名字）；夹在两行字之间的传 `height = Dimens.sliderCompact`。`activeColor` 只给阅读器浮层（纸色背景）用 |
| 单选横滚 chip 条 | `ChipRow`（内部固定横滚，`contentPadding` 给首尾边距；额外动作 chip 走 `trailing`，别再手搓 `Row` + chip）；单个独立 chip 用 `AppFilterChip`。Miuix 没有 chip，这两个是自画的 HyperOS 胶囊（选中 `tertiaryContainer`） |
| 几档里选一档（固定 2~5 档） | `SegmentedChoice`（`SegmentedChoice.kt`）：灰槽里浮起一块，选中段实心填强调色（不是白块 —— 白块在白卡上分不清）。选项多或会变的（字体、方案、七档间隔）仍用 `ChipRow` |
| 列表行密度 | 默认 `CompactPadding`；带封面/大图标的行（`BookListRow`、书架列表、`AppIconSheet`）用 `ComfortablePadding` |
| 分段 Tab + 内容切换 | `AppTabRow` + `AppTabContent`（`AppTabs.kt`）。Tab 是 Miuix `TabRow`（胶囊页签，按内容定宽）；放在卡片/面板上时 `containerColor` 传面板底色。切换过渡横移只取 1/8 宽且**关掉 SizeTransform** —— 所以内容区**必须定高**，否则又变成「切 Tab 把面板顶高」 |
| 设置行 / 开关行 / 分组小标题 | `SettingRow` / `SwitchRow` / `SectionHeader`（`SettingRow.kt`）。`SettingRow` 可点时行尾自动带 ›（Miuix `ArrowPreference` 的长相）；`SwitchRow` 是整行一个开关语义、行内 `Switch` 纯展示 —— **别用 Miuix `SwitchPreference`**，它是「行 + 开关」双焦点。设置主页那一列入口行带 `icon`（淡强调底的圆角方块），二级页保持纯文字 |
| 设置分组卡 | `SettingGroup`（Miuix `Card`）+ `SettingRow(..., position = First/Middle/Last/Alone)` / `SwitchRow(..., position = …)`；底栏/选择面板等「一条一卡」保持 `position = null` |
| 设置类页面骨架 | **只有一种**：灰底（`surface`）+ `SectionHeader` + `SettingGroup`（左右 16dp），控件全部装进卡里。卡里不是「行」的内容（表单、分段按钮、说明）包 `SettingBlock`；卡下的说明小字用 `SettingFootnote`。小标题、卡里的字、脚注左边在同一条竖线上 |
| 内容列表行（可点/单选/多选/纯展示） | `ContentListItem` / `ChapterListItem`（`ContentListItem.kt`）；书籍行再用 `BookListRow`。槽位只替你设颜色，**字号要显式给**：`ContentListDefaults.titleStyle` / `supportingStyle` / `overlineStyle`（Miuix 没有给子树设字号的公开入口，裸 `Text` 会是 17sp）。长按、单选、多选语义都在这里，别为了长按另搓 Row |
| 底部面板 | `AppBottomSheet`（`AppSheet.kt`，Miuix `WindowBottomSheet`）：调用方照旧 `if (show) AppBottomSheet(onDismissRequest = …)`；点了选项要「收起后再办事」用内容里的 `dismiss { … }`，别自己管 sheetState。会滚动的面板传 `padNavigationBar = false`，列表末尾垫 `sheetBottomInset()`。有未保存改动的编辑面板：`allowDismiss = false` 关掉下滑/蒙层，标题行 `startAction` 放 ×，返回键在内容里另挂 `NavigationBackHandler` 先问一句（见净化规则编辑）。`enableWindowDim = false` 只给阅读设置面板（要看着正文调排版）。别再直接铺 `WindowBottomSheet` |
| 从 N 项选一个（底部面板） | `OptionPickerSheet`（`OptionPicker.kt`） |
| 空态 / 错误态 | `EmptyState`（`Common.kt`）/ `ErrorState`（`ErrorState.kt`），错误态**必须**带重试出口 |
| 整页加载态 | `LoadingState`（`Common.kt`） |
| 不确定进度 | `AppLoadingIndicator`（`Common.kt`，Miuix `InfiniteProgressIndicator`）：从按钮里的 18dp 到整页的 32dp 同一个组件，调用点统一走封装 |
| 确定进度条 | `DeterminateProgressBar`（`Common.kt`，Miuix `LinearProgressIndicator`）；搜索/书源校验/更新下载/离线缓存走它 |
| 阅读进度（静止的读数） | `ReadingProgressMeter`（`Common.kt`）：书封底边、「继续阅读」卡片，4dp 细线。任务进度条表示「有活在跑」，静止的进度别用 `DeterminateProgressBar` |
| 相对时间（「3 小时前」「昨天」） | `formatRelativeTime`（`RelativeTime.kt`），按日历天而不是 24 小时算 |
| 一次性提示（Snackbar） | `MessageBus` + `CollectMessages` + `AppSnackbarHost`（`Messages.kt`，`SnackbarHostState` 是 Miuix 的）。**居中、随文案收窄的胶囊**（短提示是药丸，长提示才横向展开）。页面别自己写 `snackbarHost = { SnackbarHost(...) }` |
| 对话框 | `AppAlertDialog`（`AppDialog.kt`，Miuix `WindowDialog`）：居中标题 + 正文/内容 + 等宽 `SecondaryButton` / `PrimaryButton`。调用方照旧条件组合；取消/点外面先播退场动画再回调，收起中的点击一律吞掉。`confirmLoading` 期间次按钮是「取消这次操作」（立刻回调、弹窗留着），要禁掉就传 `dismissEnabled = false`。别手搓居中 `Surface` 冒充弹层 |

弹层一律用 Miuix 的 **`Window*`** 系列（`WindowDialog` / `WindowBottomSheet` / `WindowDropdownPopup`），**不用 `Overlay*`**：Overlay 要挂在 Miuix `Scaffold` 的弹层宿主上，而我们是 Nav3 多个 entry 各一个 Scaffold、阅读器浮层又不在 Scaffold 里；Window 系列开独立窗口，放在哪都能弹。

图标：顶栏、菜单、操作按钮上的常用图标优先用 `MiuixIcons`（`miuix-icons`：Back / More / Search / Delete / Add / Close / Settings / Share / Refresh / Edit / Copy …），它没有的再用 `material-icons-extended`。

出现「多个页面重复相似 UI」时，抽成组件而不是复制。

---

## 无障碍（提交前自查）

- **语义角色**：可点击的 `Row/Box` 加 `Modifier.clickable(role = Role.Button)`；开关行用 `toggleable(role = Switch)` 且把行内 `Switch` 的 `onCheckedChange = null`（纯展示）；单选行用 `selectable(role = RadioButton)`。别让读屏出现「行 + 控件」两个焦点。
- **可访问名称**：`AppIconButton` 必须有 `contentDescription`（装饰性的显式 `null`）。纯图形的可点元素（如色板）用 `Modifier.semantics { contentDescription = ... }` 补名字 —— 名字 Text 在可点区之外的，读屏念不到。
- **触控目标 ≥ 48dp**（`Dimens.touchTarget`）。图标可小，可点区不能小；Miuix `IconButton` 默认只有 40dp，一律走 `AppIconButton`；别用 `Modifier.size(32.dp)` 把可点元素钉到下限以下。
- **对比度**：正文 ≥ 4.5:1（见「颜色」）。阅读纸张主题在 `ReaderThemeContrastTest` 里钉死 ≥ 7:1，新增纸色配色不合格测试直接挂。
- **系统返回键**：有暂态浮层（菜单/面板/选区/多选模式）时用 `BackHandler` 先收起它们，再退页面。
- **edge-to-edge / 键盘**：可滚动的表单/列表在 `enableEdgeToEdge`（`MainActivity`）下键盘会遮挡，给它们加 `Modifier.imePadding()`。
- **内容滚到手势条下面**：可滚动的页面外层用 `padding(padding.exceptBottom())`（`EdgeToEdge.kt`），导航栏高度 `padding.calculateBottomPadding()` 垫在**滚动内容末尾**（Lazy 列表进 `contentPadding`，滚动 Column 加在 `verticalScroll` 之后）。别再 `Modifier.padding(padding)` 整块抬上去 —— 列表会在手势条上方截断、下面空一条底色。不滚动的加载 / 空态 / 错误态照旧整块 `padding(padding)`。会滚动的底部弹层同理：`AppBottomSheet(padNavigationBar = false)`，列表末尾垫 `sheetBottomInset()`（目录、阅读设置、`OptionPickerSheet`、换源）；只有几行的弹层用默认值（面板自己在内容末尾垫导航栏）

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
