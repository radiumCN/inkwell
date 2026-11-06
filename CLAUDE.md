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

全部落在 **4dp 栅格**（Miuix 自己的尺寸也都在栅格上）。常用：`gapXS`(4)/`gapS`(8)/`gapM`(12)/`gapL`(16)/`gapXL`(24)/`gapXXL`(32)；页面 `screenPadding`(20)；内容列表行 `listHorizontal`(16)、行最小高 `listItemMinHeight`(56，对齐 Miuix `BasicComponent`)、紧凑状态行 `listVertical`(4，列表行本身不用)；`sectionHeaderTop`(16)、`chipSpacing`(4)/`chipHeight`(32)、`buttonMinHeight`(40)；窄顶栏 `toolbarHeight`(52，对齐 Miuix 收起后的顶栏)；图标 `iconSm`(18)/`iconMd`(24)/`iconXL`(48)；`touchTarget`(48)；`buttonSpinner`(18)/`loadingIndicator`(32)；封面 `coverThumbWidth/Height`、详情 `coverDetailWidth/Height`、书架网格 `bookshelfGridMin`；输入高 `searchFieldHeight`/`compactFieldHeight`（均为 40）。**禁止**在页面里写 `16.dp`、`10.dp` 之类；同类元素跨页面尺寸必须一致。

默认行内边距用 `ContentListDefaults.DefaultPadding`（四周 16dp，就是 Miuix `BasicComponent` 的内边距，同一张卡里和设置行一个密度），行里有比文字高的东西（书封、大图标、48dp 图标按钮）才显式 `MediaPadding`（上下 8dp）。别在外层另搓 `Surface` 冒充列表行。

### 动效 → `app/.../ui/components/Motion.kt` + `AppTheme.motionScheme`

**我们自己写的组件动画与浮层帮手都以 `AppTheme.motionScheme`（`ui/theme/AppTheme.kt`）为唯一来源**（整页转场与阅读器开合是下面列明的例外）。Miuix 没有主题级的动效方案，`AppMotionScheme` 是照 M3 `MotionScheme` 的接口自己定义的（spatial 取几乎不回弹的 standard 那组，贴近 HyperOS 的「快、稳」）；Miuix 组件内部的动画（开关、弹窗滑入、按压缩放）是它自己的 spring，不读这里。优先用 `topBarEnter/Exit`、`bottomBarEnter/Exit`、`scrimEnter/Exit`、`expandEnter/Exit`；整页转场由 `ui/nav/PageSlide.kt` 统一做，页面别自己加进退动画；需要裸 spec（`animateItem`、`AnimatedContent`）时读令牌，**别裸写 `tween(300)`**。
- **spatial / effects 分工**（沿用 M3 约定，混了会难看）：位移与尺寸用 `defaultSpatialSpec()`，纯视觉属性（alpha、颜色）用 `defaultEffectsSpec()`。
- **「退场比入场快」**：帮手里入场 `default*`、退场 `fast*`。
- **系统「移除动画」**：`InkwellTheme` 把方案换成 `AppMotionScheme.Instant`；帮手里不必再判 `animationsEnabled()`。`animateItem` 仍可传 `null` 省插值；reader 翻页在主题外。
- **页面进退**：共享轴 X —— 新页整屏从右滑入、旧页左让约 1/4（返回镜像）；**不要加 fade**（半透明叠字会退化成渐隐渐显）。用**不回弹的 spring**（`PAGE_ENTER_STIFFNESS` / `PAGE_EXIT_STIFFNESS`，dampingRatio = 1），位移由 `PageSlide.kt` 的页面装饰器自己做。两条都实测过、别改回去：① 带回弹的 spring 在整页上就是终点冲过头再爬回来的「顿一下」；② 内置 `slideIn/OutHorizontally` 或 tween 在转场被打断（快速进退、页面掉头）时会被 Compose 换成它自己的默认 spring，掉头那页起步慢、尾巴一像素一像素地爬 —— spring 是被打断时唯一会保留的 spec。
- **阅读器开合仍硬编码 tween**（`READER_ENTER_MS` / `READER_EXIT_MS`）：与 splash 窗口咬合，别改成令牌 spring。
- **侧滑返回用线性 tween**（`PageSlideMode.Predictive` / `PREDICTIVE_BACK_MS`）：侧滑时转场按手势进度 seek，spring 曲线前段太陡，页面在进度三成就冲到头、之后不跟手。别改成令牌 spring。
- 以上三处（页面进退、阅读器开合、侧滑返回）是动效层仅有的刻意例外。底部面板里的一级 / 二级页（`SheetPages`）算「页面进退」：同一组 spring、侧滑同样线性跟手。

### 主题入口 → `MiuixTheme(controller = …)`

**以 Miuix 官方文档为准**（https://compose-miuix-ui.github.io/miuix/zh_CN/guide/getting-started 及侧栏的主题系统、颜色、平滑圆角、模糊、工具函数、图标各页）。新写法、新组件先对一遍文档，文档有推荐写法的照推荐写。

全局 `InkwellTheme`（`ui/theme/Theme.kt`）走文档推荐的 `MiuixTheme(controller = ThemeController(…))`：配色由 `rememberResolvedScheme` 解析好，按当前日夜放进 controller 的 `lightColors` / `darkColors` 那一侧（文档「Controller 中的自定义配色」）。另有两处**局部覆盖**用文档里「直接使用」的 `MiuixTheme(colors = …)`：阅读页浮层的 `ReaderThemeScope`，书籍详情的封面取色 `CoverTintedTheme`（`ui/detail/CoverTint.kt`，只换 primary / tertiaryContainer 一族）。它们从外层配色 `copy` 出来改相关槽位，不是另起一套日夜模式。

两条坑，编译和单测都看不出来：
- **colors 版 `MiuixTheme` 不提供 `LocalContentColor`**（controller 版才提供）。`ReaderThemeScope` 因此自己补了一层；新加局部主题时照做，否则没写颜色的 Text / Icon 一律是黑的，深色下黑字压黑底。`InkwellTheme` 根上那层 Miuix `Surface` 另有用处（转场缝隙里的页面底），别删。
- **`MiuixTheme` 只持有一个 `Colors` 实例、换主题时原地改值**。别拿 `MiuixTheme.colorScheme` 对象当 `remember` 的 key —— 它永远不变，切了日夜，派生出来的颜色还停在上一套。key 用具体槽位的值（`base.primary`、`base.surfaceContainer`）。

**默认配色是 `AppThemes.HYPEROS`**：Miuix 自带的 `lightColorScheme()` / `darkColorScheme()` 原样用（浅色 #F7F7F7 灰底白卡、#3482FF 蓝；深色纯黑底），只把 `primaryContainer` 一对换成 M3 含义的淡强调底。暖纸、青竹等品牌预设照旧可选，走 `schemeFrom`。

「跟随壁纸」是一个预设 id（`AppThemes.DYNAMIC`），日/夜两槽都能选；颜色由 Miuix `ThemeController`（Monet 模式、不给 keyColor）读系统调色板生成。解析统一走 `rememberResolvedScheme`（MainActivity 设窗体底色、`InkwellTheme` 铺整树共用这一个结果），别在别处直接调 `AppThemes.resolve` 而漏掉壁纸配色。

代价记清楚：Miuix 是 **0.x 实验线**，作者明说 API 可能不打招呼就改。Compose 用**稳定 BOM**，和 Miuix 构建时对的那一版 Compose（0.9.4 → 1.12）保持一致（从前为 M3 Expressive 走 alpha BOM、跑在 1.13 beta 上，那是 Miuix 没测过的组合）。升 Miuix 或 BOM 前先读 release notes 的 breaking change 段，再对一下两边的 Compose 版本，别盲升。

### 圆角 → `AppShapes`（`ui/theme/AppTheme.kt`）

Miuix 没有主题级 Shapes，组件按 Dp 收圆角。刻度：`extraSmall`(4)/`small`(8)/`medium`(12)/`large`(16，对齐 Miuix 卡片)/`extraLarge`(28，对齐 Miuix 底部面板)，用 Dp 半径（`AppShapes.radiusMedium`）。

**12dp 及以上的「面」一律 squircle**（HyperOS 的连续曲率圆角，`miuix-squircle`；Miuix 的卡片、按钮、面板、页签全是它）：卡片用 Miuix `Card`，其余的面用 `AppSurface`，修饰符级别用 `Modifier.squircleSurface` / `squircleClip`，描边 `squircleBorder`，分组卡里按角切的行用 `SquircleCorners`（后三样都在 `ui/components/AppSurface.kt`）。毛玻璃要圆角时，外面套 `squircleClip`、玻璃本身给 `RectangleShape`（见 `SelectionToolbar`）；玻璃带边缘高光的，shape 给同一条曲线的 `squircleShape(radius)`（同文件），否则高光是直角、四个角被裁掉；**别自己另写 Shape 给高光** —— miuix-blur 的高光只从 `CornerBasedShape` 读圆角半径，认不出的一律按短边一半画，卡片中间会多出一个胶囊形的椭圆（`squircleShape` 因此继承了它）。**别**再 `clip(AppShapes.large)` / Miuix `Surface(shape = …)` —— 那是普通圆弧，夹在 Miuix 组件中间弯法差一截，被指出过「圆角不统一」。Shape（`AppShapes.small` 等）只留给 4~8dp 的标签、封面、热力图格子：连续曲率在小半径上看不出来，而 squircle 每个都开一个离屏层，书架一屏几十本封面不值。挨在一起的控件同一档：分段槽、页签槽、卡里的按钮都是 48dp 高 + `radiusMedium`，槽里的浮块 `radiusSmall`。**不要**裸写 `RoundedCornerShape(12.dp)`。

### 颜色 → `MiuixTheme.colorScheme` 语义槽位

页面颜色一律走语义槽位，**不写十六进制、不写 `Color.Gray`**。配色由 `AppThemes.schemeFrom` 从「强调色 + 背景色」推导整套 Miuix `Colors`（五十来个槽位一个不漏）。
- **分层照 HyperOS，不照 M3**：页面底是 `surface`、卡片是 `surfaceContainer`、弹窗与底部面板是 `background`。浅色「灰底白卡」（卡就是预设纸色），深色「黑底亮卡」。M3 习惯里「页面 = background」在这里是错的。
- **槽位含义与 M3 不同**：`secondary` 是关着的开关轨道、`secondaryVariant` 是默认按钮底、`secondaryContainer` 是输入框底（都是中性灰）；淡强调底 + 强调色字用 `tertiaryContainer` / `onTertiaryContainer`（下拉菜单选中项、选中 chip、设置图标底）；`primaryContainer` / `onPrimaryContainer` Miuix 组件不读，保留 M3 含义。
- 正文性小字用 `onSurfaceVariantSummary`（对比度在 `AppThemesTest` 里钉住 ≥ 4.5:1，页面底和卡片上都测），**不要**用 `outline` / `dividerLine`（那是给边框/分隔线的）。
- **唯一例外**：阅读器**内容区**颜色（纸色/字色）走 `ReaderTheme` / `ReaderThemeScope`（`app/.../ui/reader/ReaderThemeScope.kt`），这是有意的独立主题；但阅读器 UI 的尺寸/动效仍走上面的令牌。阅读器里的浮层用 `ReaderThemeScope` 包裹，让开关/滑块/分隔线自动协调，别挨个传色。浮层底用 `readerOverlayColor()`、浮层里的卡用 `readerCardColor()`。浮层的强调色**跟纸走**（`paperAccent`）：有色的纸取纸的色相（米色一族统一是褐），无色的纸才用 App 主色 —— HyperOS 蓝压在米色纸上不协调。

### 排版 → `MiuixTheme.textStyles`

用角色（`title1~4` / `headline1~2` / `subtitle` / `body1~2` / `footnote1~2` / `main` / `button`），别裸写 `fontSize = 14.sp`；要加粗用 `Text(fontWeight = …)`。正文 ≥ `footnote1`(13sp)；`footnote2`(11sp) 只给角标、标签这种非正文。注意 Miuix `Text` 的默认样式是 `main`(17sp)，比 M3 默认大一号 —— 字号要紧的地方显式给 style。
对照（从 M3 迁来时）：bodySmall→footnote1、bodyMedium→body2、bodyLarge→body1、labelSmall→footnote2、titleSmall→subtitle、titleMedium→headline2 + Medium、titleLarge→title3、headlineSmall→title2。

### 复用封装组件（别手搓）

有封装就用封装，不要直接铺 Miuix 原始组件（它们的默认值有几处不达标：图标按钮 40dp 低于触控下限、`SwitchPreference` 是双焦点、弹窗被调用方一关就掐断退场动画……封装层把这些都兜住了）：

| 需求 | 用这个（`app/.../ui/components/`） |
|---|---|
| 按钮（带 loading，不撑大） | `PrimaryButton`（强调色实心）/ `SecondaryButton`（默认灰实心）（`AppButtons.kt`）。HyperOS 没有描边按钮，别再引入 |
| 文字按钮（展开/收起、撤销、行尾「加入」） | `AppTextButton("文字", onClick)`：强调色文字（body2 + Medium）、无底色，可点高度保底 48dp、带按钮读屏角色；危险操作传 `contentColor = error`。字号要不一样的才用带 content 的重载，别再手写 `Text(…, body2, Medium)`。Miuix 的 `TextButton` 是**有底色**的按钮，不是这个 |
| 图标按钮 | `AppIconButton`（`AppButtons.kt`）；顶栏返回键一律 `BackButton`（Miuix 的细线箭头）。**别裸写 Miuix `IconButton`** —— 它默认 40dp，低于 48dp 触控下限，禁用时图标也不变灰 |
| 页面骨架 | `AppScaffold`（`AppScaffold.kt`），**别再直接铺 Miuix `Scaffold`**。它把内容录进 miuix-blur 的 `LayerBackdrop`，`AppTopBar` / `AppToolbar` 自动变成毛玻璃（透明 + 渐进模糊：状态栏那头最糊、往下渐渐变清，叠三成栏底色，照 Miuix 示例 App 的「Progressive」顶栏；渐变铺满整条栏，别再改回「满强度 + 底边一窄条淡出」—— 八成底色加 16dp 淡出在手机上就是一条实色栏）。前提是内容**真的滚到顶栏下面**：外层只 `padding(padding.horizontalOnly())`，顶栏高度垫进滚动内容开头（Lazy 进 `contentPadding.top`、滚动 Column 加在 `verticalScroll` 之后）；贴在标题下不随列表滚的东西（chip 条、页签）放进顶栏的 `bottomContent`。内容是 WebView 的页面传 `blurBars = false`（录不进图层）。主滚动区挂 `Modifier.pageScroll()`（HyperOS 回弹 + 到底轻震，内容不满一屏也弹）并给滚动组件传 `overscrollEffect = null`，免得和主题自带的回弹叠两层 |
| 有标题的内容页顶栏 | `AppTopBar` + `rememberAppTopBarScroll()` + `Modifier.topBarScroll(...)`（`AppTopBar.kt`，Miuix 大标题 `TopAppBar`）。**三样必须配齐**：只换组件不接滚动＝白送一条永久变高的顶栏 |
| 窄顶栏（标题位放控件 / 多选态 / 书架首页 / 标题就是内容的页） | `AppToolbar { … }` + 纯文字标题 `ToolbarTitle`（同文件）：52dp、自己让开状态栏。搜索页的标题位是交互控件，书架与书源管理有多选态（多选时栏里只留 × · 已选 N 项 · 全选，批量动作在底部的 `BatchActionBar`），书架首页压着隐藏区与下拉刷新，关于页的流动头部本身就是标题（栏停在顶部时透明、头部滚上去才叠色出标题：`containerColor` 的透明度会乘进玻璃的叠色）；文章、书籍详情、书籍预览的标题就是内容本身（文章标题、书名），**别再给它们大标题「文章」「书籍详情」**（两行标题、大的那行没信息量），栏里的标题用 `rememberToolbarTitleReveal(listState)` 等页面上的真标题滚到栏底下再淡出 —— 理由写在 `AppTopBar` 的 KDoc 里 |
| 弹出列表（⋮、导入方式、长按菜单、按钮触发的单选） | `AppDropdownMenu` + `AppMenuItem`（`AppMenu.kt`；弹层是 `GlassDropdownPopup`，Miuix `WindowListPopup` 的毛玻璃版：背后整页糊开、卡片与各行半透明、边上一道细高光，系统关了跨窗口模糊时退回实色）：和锚点放进同一个 `Box`（长按一本书就把菜单放进那本书的格子）。从几项里选一个（排序方式）给当前项标 `selected`，弹出来带勾。**不要**再为单选或长按弹底部面板（书源数量无上限的除外 —— 发现页切书源走底部「书源胶囊」+ `AppBottomSheet` 的书源面板） |
| 多选态的批量动作 | `BatchActionBar`（`BatchActionBar.kt`，Miuix `FloatingToolbar`）放进 `AppScaffold(floatingToolbar = …)`，在里面是毛玻璃胶囊（对页面图层取样、糊 25 + 边缘高光 + 卡片色薄底，不混色 —— 照 Miuix 示例 App 的悬浮导航栏；从前糊 100 + Colored Thick，56dp 的胶囊底下被平均成一片浅灰，被指出「没有透明的效果」。不投影、不描实心边 —— 那两样会从半透明的玻璃里透出来）：HyperOS 编辑模式，顶栏只留 × · 已选 N 项 · 全选，动作沉到底部一条悬浮胶囊里、图标在上字在下（`IconLabelButton`，阅读菜单的操作条也是它），低频的给 `more`。Miuix Scaffold **不**把它算进内容 padding：`onHeightChange` 报的高度在多选态垫到列表末尾。网格里的书多选时不缩、不描边，勾选框在封面右上角。底部别的悬浮状态（书架隐藏区展开后的「🔓 隐藏区 ｜ 展开需验证 ✕」胶囊、发现页的书源胶囊「⟳ 书源名 ｜ 切换书源 ⇅」—— 整条可点开书源面板，切源入口别再挂回顶栏标题）用同一个玻璃壳 `FloatingGlassBar`（同文件），和操作条放进同一个 `floatingToolbar` 槽、同一时刻只亮一个；胶囊里不是 onSurface 的字拿它给的 `floor` 现推对比度。**别**再往顶栏 `bottomContent` 里塞实心卡：顶栏是渐进模糊的玻璃，嵌一块实心卡，书滚过去被切成「糊 / 实心 / 清楚」三层（被指出「滑动书架割裂」）|
| 顶栏/工具条搜索框 | `SearchField`；对话框/表单行内 `CompactTextField`（`AppTextField.kt`）。两者是手搓 `BasicTextField`（Miuix `TextField` 带浮动 label、最矮五十多 dp 塞不下），但底色/占位色/聚焦描边取 Miuix 输入框读的槽位，别自己发明 |
| 多行文本输入 | `CompactTextArea`（同文件）：同一套底色，高度 `textAreaMinHeight`，圆角 `radiusLarge`。**不要**用胶囊圆角 —— 160dp 高的框会变成两头大圆 |
| 带 label 的整页表单输入 | `AppFormField`（同文件，Miuix `TextField` 的浮动 label；报错时描边换 error 色、`supportingText` 在框外）。意见反馈这类无 label 的整页输入走 `CompactTextArea` / `CompactTextField` |
| 滑块 | `AppSlider`（`AppSlider.kt`，Miuix `Slider` 胶囊粗轨）：给 `label`（读屏名字）；夹在两行字之间的传 `height = Dimens.sliderCompact`。`activeColor` 只给阅读器浮层（纸色背景）用 |
| 单选横滚 chip 条 | `ChipRow`（内部固定横滚，`contentPadding` 给首尾边距；额外动作 chip 走 `trailing`，别再手搓 `Row` + chip）；单个独立 chip 用 `AppFilterChip`。条末尾的「添加」（导入字体）用 `AddChip`（虚线圆 + ＋）——**动作别长成选项的样子**，从前「导入字体」是一颗普通 chip，看着像一款叫「导入字体」的字；条里的项要长按删除传 `longClickActions`。Miuix 没有 chip，这两个是自画的 HyperOS 胶囊（选中 `tertiaryContainer`）。选中项跟着滚动变、不是手点出来的（协议页的节 chip）传 `bringSelectedIntoView = true`，亮到条外面会自己横滚过来。和 `SegmentedChoice` 同处一张面板时（阅读设置）传 `solidSelection = true`，选中态与分段一样实心填强调色，别让一张面板里出现两种「选中」 |
| 几档里选一档（固定 2~5 档） | `SegmentedChoice`（`SegmentedChoice.kt`）：灰槽里浮起一块，选中段实心填强调色（不是白块 —— 白块在白卡上分不清）。选项多或会变的（字体、方案、七档间隔）仍用 `ChipRow` |
| 从一排色块 / 图标里挑一个（主题色板、应用图标的图形与纸色） | `SwatchChoice`（`SwatchChoice.kt`）：52dp 的块 + 下面一行名字，选中描主色圈 + 右下角一枚勾 + 名字换主色加粗，没选中的描细分隔线（浅色块放白卡上才看得出边）；`cornerRadius` 给了是 squircle（图标预览），不给是圆形色板。整块一个单选目标、带名字（名字的 Text 在可点区外）。描边叠在内容**上面**一层画 —— Miuix 的 `squircleBorder` 画在内容底下（onDrawBehind），块里铺一张不透明的图就被整个盖住 |
| 列表行密度 | 默认 `DefaultPadding`（= Miuix `BasicComponent`）；带封面/大图标/行尾 48dp 图标按钮的行（`BookListRow`、书架列表、`AppIconSheet`、搜索历史）用 `MediaPadding`。从前默认是上下 4dp，两三行字的行（书源、净化规则、订阅文章）挤成一片，被指出不合规范 |
| 分段 Tab + 内容切换 | `AppTabRow` + `AppTabContent`（`AppTabs.kt`）。Tab 是 Miuix `TabRowWithContour`（灰槽里浮起一块卡片色，格子均分槽宽），高度与圆角对齐 `SegmentedChoice`；`containerColor` 是槽色，要和浮块（`surfaceContainer`）差一档。别换回 Miuix `TabRow`（一格一个描边框，像一排按钮）。调用方给左右边距，和下面的内容对齐。切换过渡横移只取 1/8 宽且**关掉 SizeTransform** —— 所以内容区**必须定高**，否则又变成「切 Tab 把面板顶高」 |
| 设置行 / 开关行 / 选择行 / 分组小标题 | `SettingRow` / `SwitchRow` / `SpinnerSettingRow` / `SectionHeader`（`SettingRow.kt`）。`SettingRow` 就是 Miuix `ArrowPreference`（可点，行尾 ›）/ `BasicComponent`（纯展示）；`SwitchRow` 是 `BasicComponent` + 纯展示的 `Switch`，整行一个开关语义 —— **别用 Miuix `SwitchPreference`**，它是「行 + 开关」双焦点。退出登录这类操作传 `destructive = true`（红字、不带 ›、点了先确认），单独放一张卡，别和普通入口混在一组。「标题 + 当前值、点开选一个」用 `SpinnerSettingRow`（照 Miuix `WindowSpinnerPreference` 拼的那一行，选项从这一行上弹出；弹层换成和 `AppDropdownMenu` 同一个毛玻璃 `GlassDropdownPopup`，别再直接用 `WindowSpinnerPreference` —— 它的弹层写死是实色卡片）。设置主页那一列入口行带 `icon`（光秃秃的线条图标、`onSurface` 色，**不垫底板** —— 垫过淡强调色方块，被指出「图标外面套了个容器」），二级页保持纯文字 |
| 设置分组卡 | `SettingGroup`（Miuix `Card`）+ `SettingRow(..., position = First/Middle/Last/Alone)` / `SwitchRow(..., position = …)`；底部面板里的开关行也装进 `SettingGroup`（不再「一条一卡」，见底部面板） |
| 设置类页面骨架 | **只有一种**：灰底（`surface`）+ `SectionHeader` + `SettingGroup`（左右 16dp），控件全部装进卡里。卡里不是「行」的内容（表单、分段按钮、说明）包 `SettingBlock`；卡下的说明小字用 `SettingFootnote`。小标题、卡里的字、脚注左边在同一条竖线上 |
| 内容列表行（可点/单选/多选/纯展示） | `ContentListItem` / `ChapterListItem`（`ContentListItem.kt`）；书籍行再用 `BookListRow`。列表**一律装进卡片**，页面上的、底部面板里的都一样（书架列表、搜索结果、书源列表、目录、换源候选、图标选择）：每行 `Modifier.groupedCardItem(index, count)`，行间不加 `spacedBy`（HyperOS 是灰底白卡，不是通栏的行，也不是一条一张小卡）。行默认透明、直角，选中是卡里浮起的一块淡强调底（目录的当前章；四边缩 4dp、圆角和卡同心，`ListRow` 自己画）—— 别再铺从左到右的通栏色带，卡中间一条直角带就是「全是直边直角」；开关行、多选行用 `plainColors()`（状态只看行尾的开关 / 勾）。**行尾只放一个控件**（开关、勾选框、›、一个文字按钮）：删除收进长按菜单（`AppDropdownMenu`）或多选，别在开关旁边再摆一个 48dp 的垃圾桶。多选的勾选框在**行尾**。列表上面的标题用 `SectionHeader`（行尾要放文字按钮传 `trailing`），别用大一号的字加分隔线。槽位只替你设颜色，**字号要显式给**：`ContentListDefaults.titleStyle` / `supportingStyle` / `overlineStyle`（Miuix 没有给子树设字号的公开入口，裸 `Text` 会是 17sp）。长按、单选、多选语义都在这里，别为了长按另搓 Row |
| 面板里定高的滚动列表（目录、换源候选、发现页的书源面板） | `CardViewport`（`CardViewport.kt`）包住 LazyColumn：视口裁成卡的圆上角（同一个 `radiusLarge`、同一条 squircle），滚起来卡顶仍是圆的 —— 只靠 `groupedCardItem`，一滚视口顶上切过的是中间的直角行，卡顶一刀直线（被指出「和应用风格不搭」）。里面的 `cardMargin()` 是 0，行和小标题照旧按它排。和上面那行字（概况行、搜索框）之间的缝留在视口**外面**，别放进 `contentPadding.top`：那一截跟着内容滚走，一滚行就顶到字底下。快速滚动条、「定位当前章」放在视口外、同一个 Box 里，别被裁掉。页面上的列表滚到毛玻璃顶栏底下，用不着 |
| 封面毛玻璃卡（一页上唯一带颜色的面：书架「继续阅读」、设置概览、阅读记录「今天」） | `CoverGlassCard`（`CoverGlass.kt`）：这本书的封面铺满，盖整片模糊（示例 Texture Blur 的 100，**不渐变** —— 渐变只给顶栏和详情页头部）+ Colored Thick 混色 + 边缘高光 + 一层卡片色薄底。卡里的字读 `LocalContentColor`、统一一种颜色，**别用 `onSurfaceVariantSummary`**（薄底是半透明的，对比度只按 onSurface 在 `CoverGlassTest` 里钉过）。封面来自 `BookDao.observeLatestRead()` 的地方**不含隐藏的书**；查完了没书传 null，退回实色卡；**还在查**时传 `pending = true`（先画无封面的玻璃、封面淡进来），别拿「还没查回来」当「没书」，否则进页面先闪一下实色卡（`LatestRead` 包一层就是为了分开这两种 null）。详情页头部的封面底是同文件的 `CoverHeaderBackground`。`tiltHighlight = true` 时边缘高光跟着陀螺仪动 —— 只给书架「继续阅读」开 |
| HyperOS 流动彩色底（「关于手机」那种） | `FlowBackground`（`FlowBackground.kt`，移植自 Miuix 示例 App，Apache-2.0）：固定的 HyperOS 配色，不跟主题色；系统关了动画时定格。目前只有关于页头部用，配玻璃字（`textureBlur` + `contentBlendMode = DstIn`） |
| 按压反馈（一整块可点的面：书架上的书、继续阅读卡、色板） | `Modifier.sinkOnPress(source)`（按下整块微缩）/ `tiltOnPress(source)`（大卡片，按哪个角哪个角沉），`PressFeedback.kt`。挂在**外层**，里面的 clickable / selectable 传**同一个** `interactionSource`、indication 照旧 `LocalIndication.current`（Miuix `Card(pressFeedbackType)` 的接法）。贴在卡片里的设置行、列表行不加（HyperOS 是按下变灰），Miuix 按钮自带，别叠 |
| 底部面板 | `AppBottomSheet`（`AppSheet.kt`，Miuix `WindowBottomSheet`）：调用方照旧 `if (show) AppBottomSheet(onDismissRequest = …)`；点了选项要「收起后再办事」用内容里的 `dismiss { … }`，别自己管 sheetState。会滚动的面板传 `padNavigationBar = false`，列表末尾垫 `sheetBottomInset()`。有未保存改动的编辑面板：`allowDismiss = false` 关掉下滑/蒙层，标题行 `startAction` 放 ×，返回键在内容里另挂 `NavigationBackHandler` 先问一句（见净化规则编辑）。`enableWindowDim = false` 只给阅读设置面板（要看着正文调排版，也不做背后模糊）。阅读设置**全在这一个面板里**、不另开设置页（调了要当场在正文上看到）：一级页一屏放下最常调的（亮度、字号、行距、纸张、字体），排版 / 翻页 / 更多是面板里的二级页，走 `SheetPages`（`SheetPages.kt`）：整页从右推进来、一级页左让 1/4，侧滑返回跟手；二级页的标题行（‹ + 页名）是页面自己的第一行 `SheetPageHeader`、跟着页面一起滑，面板定高不跳。**别**拿面板的 `title` / `startAction` 当二级页标题 —— 它不在过渡里，第一帧就整个冒出来、把内容顶下去一行（被指出「动画割裂」）；也别用 `AppTabContent`，那是同级页签；拖字号、亮度滑轨时面板让开（底色、拖动条、别的项淡掉）。**面板里照 Miuix 示例 App 排**：内容离面板两边 24dp（`cardMargin()`，面板经 `CardLayer` 给到内容），列表、开关行装进卡（`SettingGroup` / `groupedCardItem`），卡上的标题用 `SectionHeader`，输入框直接放在面板上；编辑面板左 ×（`startAction`）右 ✓（`endAction` = 保存）。卡片色由面板换好（`sheetCardColor`：Miuix 默认配色里面板底和卡片同色，卡放进去就看不见），别自己传色、算边距。别再直接铺 `WindowBottomSheet` |
| 空态 / 错误态 | `EmptyState`（`Common.kt`）/ `ErrorState`（`ErrorState.kt`），错误态**必须**带重试出口 |
| 整页加载态 | `LoadingState`（`Common.kt`） |
| 不确定进度 | `AppLoadingIndicator`（`Common.kt`，Miuix `InfiniteProgressIndicator`）：从按钮里的 18dp 到整页的 32dp 同一个组件，调用点统一走封装 |
| 确定进度条 | `DeterminateProgressBar`（`Common.kt`，Miuix `LinearProgressIndicator`）；搜索/书源校验/更新下载/离线缓存走它 |
| 目录（章节列表） | `ChapterListItem`（`ContentListItem.kt`）：详情、预览、阅读页的目录面板共用。单行 48dp（`ContentListDefaults.CompactPadding` + `minHeight`，几千章的单行列表用不着 56dp）；在读那章淡强调底 + 行尾「在读」，读过的传 `read` 字换说明色 —— 从前每行一个样，看不出读到哪；搜索时传 `highlight` 标出命中的字。阅读页的目录面板另有标题行的正序 / 倒序（`AppPrefs.tocReversedState`，只记本机）、「共 N 章 · ● 已缓存 N 章」概况行、在读那章滚出视野时的「定位当前章」 |
| 换源面板 | `ChangeSourceSheet`（`ui/reader/`）：当前源单独一张卡钉在最上（淡强调底 + 行尾「当前」+ 它的最新章），下面每个候选拿它比。候选行**书源名做主行、最新章做副行**，书名 / 作者和这本一样就不重复写，对不上才多一行「书名：… · 作者：…」（从前每行最大的字是同一个书名，被指出要调整）；行尾「多 N 章 / 少 N 章」走 `ChapterOrdinal.gap`（core，只认「第X章」，认不出或编号对不上返回 null，就不标）。候选区**定高**，别改回 `heightIn(max)`：结果边搜边出，面板会一格一格往上长。「只看同作者」是概况行上的 chip，它筛掉的数目（`authorMismatched`）要说出来、给「显示」出口；点了一个源，那一行就地转圈、其他行变灰 |
| 书源校验状态（书源管理） | `CheckStatusLine`（`SourceManageScreen.kt`）+ `CheckMessages`（`ui/sourcemanage/`）：行上只有两种颜色 —— 失效红（✕ + 原因）、正在跑的蓝（就地转圈「正在校验…」）；可用的是灰字「可用 · 4.8 秒」，不带勾（从前每行一条蓝 ✓，几百个源满屏强调色，红字反倒不显眼）。没有搜索规则的源不写状态，标题后挂「仅发现」/「无搜索」标签。失败原因归类说人话、带上坏在哪一步（「详情页被拒（HTTP 403）」「正文超时（60 秒）」），存进库的和显示的文案**都走 `CheckMessages`**（库里的旧文案显示时也经它重写），别再把异常原文直接写给用户。校验中排队没轮到的行，上一次的结果变淡；概况行「正在校验 119 / 886 · 失效 23」+「停止」，下面一条通栏进度条 |
| 长列表快速滚动（上百行：目录） | `FastScroller`（`FastScroller.kt`）：和 LazyColumn 放进同一个 Box、`matchParentSize()` 叠上去，`contentPadding` 给列表同样的上下留白；不够 `FAST_SCROLL_MIN_ITEMS` 行不出现，滚动时露出、停下一会儿隐去，拖动时气泡写那一行。Miuix 没有滚动条，别另搓 |
| 阅读进度（静止的读数） | `ReadingProgressMeter`（`Common.kt`）：书封底边、「继续阅读」卡片，4dp 细线。任务进度条表示「有活在跑」，静止的进度别用 `DeterminateProgressBar` |
| 相对时间（「3 小时前」「昨天」） | `formatRelativeTime`（`RelativeTime.kt`），按日历天而不是 24 小时算 |
| 一次性提示（Snackbar） | `MessageBus` + `CollectMessages` + `AppSnackbarHost`（`Messages.kt`，`SnackbarHostState` 是 Miuix 的）。**居中、随文案收窄的胶囊**（短提示是药丸，长提示才横向展开）；在 `AppScaffold` 里是毛玻璃 + 边缘高光，叠色厚度与操作键颜色按最坏的页面内容算（`CoverGlassTest`）。页面别自己写 `snackbarHost = { SnackbarHost(...) }` |
| 不是卡片、也不是按钮的圆角面（提示条、阅读页小提示、预览框） | `AppSurface`（`AppSurface.kt`）：Miuix `Surface` 的 squircle 版，`borderColor` 给描边。卡片用 Miuix `Card`。别用 Miuix `Surface(shape = …)`（普通圆弧） |
| 打开外部链接（官网、开源地址、订阅原文、网页管理） | `rememberExternalLinkOpener`（`ExternalLinks.kt`）：先弹「打开外部链接」把完整地址亮出来，点「打开」才拉浏览器；拉不起时调用方发 `NO_BROWSER_MESSAGE`。**别直接调 `openExternalLink`** —— 点一行就被甩进浏览器，人还没看清要去哪儿（被指出过）。只有已经在确认弹窗里的那一步（更新弹窗的「查看 Release」「下载 APK」）才直接调 |
| 对话框 | `AppAlertDialog`（`AppDialog.kt`，Miuix `WindowDialog`）：居中标题 + 正文/内容 + 等宽 `SecondaryButton` / `PrimaryButton`。调用方照旧条件组合；取消/点外面先播退场动画再回调，收起中的点击一律吞掉。`confirmLoading` 期间次按钮是「取消这次操作」（立刻回调、弹窗留着），要禁掉就传 `dismissEnabled = false`。要自己画头部的（更新弹窗：图标 + 版本）传 `title = null`；内容里自己有滚动区的传 `scrollable = false`，别让外层再套一层滚动。别手搓居中 `Surface` 冒充弹层 |

**叠在可点内容上面的面一律挂 `blockTouchesBelow()`**（`TouchBlock.kt`，一个空的 pointerInput）：Miuix 的栏、胶囊、`Surface` 都不拦触摸，Compose 会把落在空白处的点击交给下面被盖住的东西 —— 书源管理顶栏里「正在校验」那行字上一点，点到的是被玻璃糊住的那一行书源的开关（被指出过）。`AppToolbar` / `AppTopBar` / `FloatingGlassBar` / `AppSnackbar` 和阅读器的浮层都挂好了，自己另写浮层（提示条、悬浮工具条）时照做。

对话框、底部面板、弹出列表背后的页面会模糊（`DialogWindowBlur`，`WindowBlur.kt`，系统跨窗口模糊 `blurBehindRadius`）：封装里已经调了，自己另写弹层时也放进弹层内容里调。系统关了跨窗口模糊时自动退回只压暗。玻璃上的字按**最坏的底**算对比度（`CoverGlassTest`：浅色主题底下一片纯黑、深色一片纯白），叠色厚度保证正文，强调色、红字、说明小字在那块底上用 `AppThemes.readableOn` 现推 —— 新加玻璃面时照这个办法加一条测试。

弹层一律用 Miuix 的 **`Window*`** 系列（`WindowDialog` / `WindowBottomSheet` / `WindowDropdownPopup`），**不用 `Overlay*`**：Overlay 要挂在 Miuix `Scaffold` 的弹层宿主上，而我们是 Nav3 多个 entry 各一个 Scaffold、阅读器浮层又不在 Scaffold 里；Window 系列开独立窗口，放在哪都能弹。

图标：一律先找 `MiuixIcons`（`miuix-icons`，HyperOS 线条图标：Back / More / Search / Delete / Add / Close / Settings / Share / Refresh / Edit / Copy / Hide / Replace / Tune / Clear / Recent / Sort / SelectAll / Import / Update / Info …；`MiuixIcons.Basic.*` 是 Check / ArrowRight / ArrowUpDown / SearchCleanup 这几个小件）。对应关系：发现 → `SearchDevice`（指南针形）、警告 / 错误 → `Report`、成功 → `Ok`、书 / 空书架 → `Notes`、云同步 → `UploadCloud`（未配置）/ `CloudFill`（已同步）。只有 Miuix 真没有的（目前只剩 RSS）才用 `material-icons-extended` 的 **`Icons.Outlined.*`** —— 线条版才和 Miuix 一个画风，别用 `Icons.Default`（实心）。新加图标前先翻一遍 0.9.x 的图标表（文档「图标」页，或 `miuix-icons` 包里 `icon/extended` 下的类名）。多选角标用 Miuix `Checkbox`，不用一对图标拼。

出现「多个页面重复相似 UI」时，抽成组件而不是复制。

---

## 无障碍（提交前自查）

- **语义角色**：可点击的 `Row/Box` 加 `Modifier.clickable(role = Role.Button)`；开关行用 `toggleable(role = Switch)` 且把行内 `Switch` 的 `onCheckedChange = null`（纯展示）；单选行用 `selectable(role = RadioButton)`。别让读屏出现「行 + 控件」两个焦点。
- **可访问名称**：`AppIconButton` 必须有 `contentDescription`（装饰性的显式 `null`）。纯图形的可点元素（如色板）用 `Modifier.semantics { contentDescription = ... }` 补名字 —— 名字 Text 在可点区之外的，读屏念不到。
- **触控目标 ≥ 48dp**（`Dimens.touchTarget`）。图标可小，可点区不能小；Miuix `IconButton` 默认只有 40dp，一律走 `AppIconButton`；别用 `Modifier.size(32.dp)` 把可点元素钉到下限以下。
- **对比度**：正文 ≥ 4.5:1（见「颜色」）。阅读纸张主题在 `ReaderThemeContrastTest` 里钉死 ≥ 7:1，新增纸色配色不合格测试直接挂。
- **系统返回键**：有暂态浮层（菜单/面板/选区/多选模式）时用 `BackHandler` 先收起它们，再退页面。
- **edge-to-edge / 键盘**：可滚动的表单/列表在 `enableEdgeToEdge`（`MainActivity`）下键盘会遮挡，给它们加 `Modifier.imePadding()`。
- **内容滚到顶栏与手势条下面**：可滚动的页面外层只用 `padding(padding.horizontalOnly())`（`AppScaffold.kt`），顶栏高度 `padding.calculateTopPadding()` 垫在**滚动内容开头**、导航栏高度 `padding.calculateBottomPadding()` 垫在**滚动内容末尾**（Lazy 列表进 `contentPadding`，滚动 Column 加在 `verticalScroll` 之后）—— 顶栏是毛玻璃，内容不滚到它下面就白做了。别再 `Modifier.padding(padding)` 整块抬上去 —— 列表会在手势条上方截断、下面空一条底色。不滚动的加载 / 空态 / 错误态照旧整块 `padding(padding)`。会滚动的底部弹层同理：`AppBottomSheet(padNavigationBar = false)`，列表末尾垫 `sheetBottomInset()`（目录、阅读设置、换源）；只有几行的弹层用默认值（面板自己在内容末尾垫导航栏）

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
- **Room 迁移**：只加列 / 新增表，带默认值，保留用户数据；破坏性迁移只在**降级**时兜底（`fallbackToDestructiveMigrationOnDowngrade`）。别删迁移链。要删列别直接写 `ALTER TABLE … DROP COLUMN`：minSdk 33，Android 13 系统的 SQLite 是 3.32，没有它（3.35 起），用 `dropColumns`（`data/db/SqliteCompat.kt`，老 SQLite 上重建表），迁移 SQL 能在 `DropColumnsTest` 那样的真 SQLite 单测里跑。用的是 **Room 3**（`androidx.room3`，KMP 重构后继线）：迁移体是 `override suspend fun migrate(connection: SQLiteConnection)`，SQL 走 `connection.execSQL`——照 2.x 的 `SupportSQLiteDatabase` 写法抄会编不过。
- **导航**：Jetpack **Navigation 3**（`NavBackStack` + `NavDisplay`）+ Material 3 Adaptive（`adaptive-navigation3` list-detail）+ **`lifecycle-viewmodel-navigation3`**。ViewModel **只**在 `entry { }` 内用 `org.koin.compose.viewmodel.koinViewModel` 创建（经 `rememberViewModelStoreNavEntryDecorator` 绑到 NavEntry；`koin-androidx-compose` 依赖已移除 —— 它的同名 `koinViewModel` 绑 Activity 的 store，退栈不清，别再加回来）。Screen 的 `viewModel:` 参数**不给默认值**，谁在 entry 里创建谁传。前进经 `InkwellNavigator.go`（栈顶同类替换）。**大 payload 不塞路由参数**（会随返回栈进 `onSaveInstanceState`，撑爆 Binder → `TransactionTooLargeException`）—— 用进程内 holder 按 key 暂存，路由只带短字段。阅读器始终全屏，不进双栏。

---

## 桌面图标

- 4 种图形（书签 / 翻页 / 叠页 / 段落）× 5 种纸色（取阅读器的纸张）= 20 个自适应图标，背景 / 前景 / 单色三层**全是矢量**，由 `tools/gen_launcher_icons.py` 生成（`--preview` 另出一张同几何的 SVG 预览）。改图形改脚本再跑，别手改生成的 XML。
- 桌面入口是一个组合一个 `activity-alias`（Android 运行时改不了 `android:icon`），对应表在 `util/AppIcon.kt`。**alias 名一旦发布就不能改、不能删**：它写在用户桌面的快捷方式里，删掉的话换过那一款的人升级后桌面图标凭空消失。0.5.2 之前那五款的名字（`.IconClassic` 等）接着分给五个新组合；`.IconClassic` 永远是默认。表和清单对不对得上由 `AppIconManifestTest` 钉着。
- 切换走 `setComponentEnabledSettings` 一次批量提交（minSdk 33），别改回逐个启用 / 禁用 —— 二十个组件逐个改，桌面要跟着刷十几回，顺序错了还会有一瞬间一个入口都没有。
- 应用里画图标一律 `AppIconImage`（`ui/settings/AppIconSheet.kt`，背景、前景两层叠起来只露中间 72）：Compose 读不了 adaptive-icon 的 XML，别再另备一套位图预览。

## 书源规则

自研 JSON Schema + Legado（阅读）书源兼容，详见 `README.md` §书源规则 / §Legado 书源兼容。当前正从自研 DSL 迁移到运行时原生 Legado 规则（文本源），引擎在 `core/.../source/`。改规则解析时先看 `core/src/test` 的既有测试了解预期，再动。

---

## 发布流程

- 版本号**唯一来源**：`gradle/libs.versions.toml` 的 `inkwell = "x.y.z"`。CI 校验 tag 必须等于 `v$版本`。
- 发布 = 打**附注 tag** `vx.y.z` 并推送 → 触发 `.github/workflows/release.yml`。
- **tag 注解正文 = GitHub Release 正文 = 应用内更新弹窗内容 = 关于页点「版本」看到的内容**。CI 会自动剔掉 `Co-Authored-By`/`Signed-off-by` trailer 和 `Full Changelog`。
- 关于页那份是打包前写进安装包的（release.yml 的「Extract tag message」把正文与 tag 日期写进 `app/src/main/assets/release/`，该目录不入库；读取见 `BundledReleaseNotes`）：所以那一步**必须在 `assembleRelease` 之前**。本地编的包没有它，弹窗里写「这个安装包没有附带更新说明」。
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
- 结尾**不要**加 `Co-Authored-By` / `Signed-off-by` 之类的 trailer —— AI 参与时也不加。
