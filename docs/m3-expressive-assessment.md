# M3 Expressive 评估清单

**当前状态：主题入口 + 动效来源 + 组件层都已收口**（见文末各阶段「已落地」）。**欠的是实机视觉巡检（第 0 / 6 / 7 项）与体积/Baseline Profile（第 8 项）** —— 这两笔恰恰是编译与单测看不见的部分，剩下的项照这份走，做完把结论写回来。

## 为什么是「未评估」而不是「评估后拒绝」

- material3 **1.4.0**（Expressive 落地后的首个稳定版）是 `caecdb7`（2026-07-16）随 Compose BOM 2026.06.01 带进来的。但 Expressive 是 **opt-in**：它挂在独立的 `MaterialExpressiveTheme` 入口和 `ExperimentalMaterial3ExpressiveApi` 标记后面，**升级不等于启用**，默认组件仍走经典 `MaterialTheme` 的 token。
- 项目的令牌体系早于它定型：`Theme.kt` / `AppThemes.kt`（07-13）、`Motion.kt`（07-14），BOM 抬到 1.4.0 是 07-16，而且这套约定已经写进 `CLAUDE.md` 成了硬规则。
- 全库搜不到任何 Expressive 相关的代码或决策记录（零命中）。所以现状是**默认继承**。
- `caecdb7` 自己写明「**未做实机视觉验证** —— M3 Expressive 改了配色/形状/动效 token，编译器与单测都看不见」。也就是说连 1.3.2 → 1.4.0 默认 token 变化的影响都还没被人眼确认过。**这笔欠账要先还**，否则两批视觉变化混在一起，出问题没法归因。

## 成功判据（先定，不然会退化成「把主题函数名换掉」）

1. **无障碍零回归**：正文对比度 ≥ 4.5:1；系统「移除动画」开启时所有动画时长为 0；触控目标 ≥ 48dp（`Dimens.touchTarget`）；读屏不出现「行 + 控件」两个焦点。
2. **动效只有一个来源**：`MotionScheme` 或 `Motion.kt`，二选一。两套并存即判失败 —— 那等于埋一个「关了动画还在动」的无障碍回归。
3. **不新增裸值**：仍然全部走令牌与封装组件（`CLAUDE.md` 的 UI 统一层硬规则不因换主题而松动）。
4. **包体可接受**：release 体积增幅有数。参照上次 BOM 升级是 7.3M → 7.5M（+2.2%）。
5. **可回退**：一次 revert 能干净退回，或有一个显式开关。
6. **收益说得出口**：能指出具体哪几处界面变好了，而不是「用上了新规范」。

## 待决问题（必须先答，答不了就别开工）

- [x] **动效以谁为准？** —— 见下方第 1 项，已解决：`MotionScheme` 是公开接口，能自己实现，所以「系统关了动画就 0 时长」这条要求套得上去，不必二选一。
- [x] **1.4.0 里 `MaterialExpressiveTheme` 本身稳定了吗？** —— **1.4.0 里根本用不了**。这里原先写「稳定」是**错的**：判据是 `javap -v` 看不到注解，可字节码里没注解恰恰因为它是 Kotlin `internal` —— internal 编译成 JVM public，javap 分辨不出可见性，只有 Kotlin 元数据里有。编译器一试就拦：`Cannot access 'fun MaterialExpressiveTheme(...)': it is internal in file`，`MotionScheme` 与 `MaterialTheme.motionScheme` 同样是 internal。
  真相是 1.4.0-beta01 把所有 `ExperimentalMaterial3ExpressiveApi` 标记的公开 API **整批移出了稳定线**（release note: "have been removed, please switch to 1.5.0-alpha"），类还留在 aar 里但降成 internal。官方文档上 `MaterialExpressiveTheme` 与 `MotionScheme.expressive()` 都写着 "Added in 1.5.0-alpha24"。
  所以启用 Expressive **必须离开稳定 BOM**，没有第二条路（稳定 BOM 只收稳定版，升 BOM 也拿不到）。好消息：1.5.0-alpha25 里这些 API **不需要 `@OptIn`**。
- [ ] **目标是「换主题」还是「换组件」？** 两者可以拆开：只在个别页面用 `FloatingToolbar` 这类新组件，不必全局换 theme。风险与收益完全不同档。
- [ ] **阅读器跟不跟？** 内容区（纸色/字色）是有意独立的 `ReaderTheme`，与本次无关；但浮层走 `ReaderThemeScope` 包 M3，会被影响。

## 逐项检查清单

每项都写现状数字，便于估工与判断爆炸半径。

### 0. 前置：实机视觉巡检 1.4.0 现状

补上 `caecdb7` 欠的那笔。全页面走一遍截图留档，作为后续对比的基线。
status: **pending**；verify: 每个页面有 1.4.0 现状截图

### 1. 动效层（最硬的冲突点）

现状：`Motion.kt` 196 行，钉死时长与曲线（`ENTER_MS` 220 / `EXIT_MS` 140 / `NAV_*` / 阅读器专用两条），对外给 `topBarEnter/Exit`、`bottomBarEnter/Exit`、`scrimEnter/Exit`、`expandEnter/Exit` 八个帮手；**13 处**调用 `animationsEnabled()`（内部挂 `ContentObserver` 实时监听系统「移除动画」），另有 `InkwellNavDisplay` 的 4 条转场 spec。

要答的问题：`MotionScheme` 能不能表达「系统关了动画就 0 时长」？它发出的 spec 不认识这条规则。

先把边界说清楚，别把已有状况算成新问题：1.4.0 的 M3 组件**内部**动画本来就读 `MotionScheme`（默认 standard），`Motion.kt` 从来只管**我们自己写的**那些动画 —— `CLAUDE.md` 已经把框架内建动画（ModalBottomSheet / DropdownMenu / PullToRefresh）划在约束之外。换 Expressive 会让「来自 `MotionScheme` 的那部分」变多、动得更明显，于是「关了动画还在动」从边角问题变成显眼问题。这是要评估的增量，不是凭空冒出来的。

**结论：接缝有，而且比预想的好。** `MotionScheme` 是**公开接口**，六个方法（spatial/effects × 快中慢），自己实现一个即可 —— `InstantMotionScheme`（`Theme.kt`）在系统关动画时把整套顶替成 `tween(0)`。于是这一项不但不是障碍，还**反过来补上了原有缺口**：框架内建组件动画从前逃在 `animationsEnabled()` 之外（`CLAUDE.md` 当时把它们划为例外，因为确实没有定制口），现在归进来了。

另有一个**踩进去过的坑**，记下来：`MaterialExpressiveTheme` 的 `motionScheme` 默认是 `null`，很容易以为「不传就是 expressive」。反编译 alpha25 的 `MaterialThemeKt` 看清楚了 —— `null` 走的是 `MaterialTheme.getMotionScheme()`，即**沿用外层主题**；根节点没有外层，拿到的是 `MaterialTheme.Values` 的默认值 `MotionScheme.standard()`。也就是说省掉这个参数的结果是「组件形态换成 Expressive、动效还是 standard」，而**编译器和单测都看不见**。所以必须显式 `MotionScheme.expressive()`（这个函数在 1.5.0-alpha25 是公开的，1.4.0 里是 internal）。

成功判据 2（动效只有一个来源）**只算部分达成**：现在是分工并存 —— `Motion.kt` 的 tween 管我们自己写的转场，`MotionScheme` 的 spring 管组件内部。无障碍风险已闭合（两边都会被关成 0 时长），剩下的是**观感一致性**：弹性 spring 和 220ms tween 摆在同一屏上，节奏不一样。要不要把 `Motion.kt` 也改成读 `MaterialTheme.motionScheme`，留给第 2 阶段 —— 那是纯观感取舍，不再是无障碍红线。

**后续（第 3 / 6 阶段）**：八个组件帮手已全部改读 `MotionScheme`，判据 2 在**组件层**达成；页面进退与阅读器开合作为两条**刻意例外**留在 tween，理由写在第 6 阶段与 `Motion.kt` 的 KDoc 里。所以这一项的终态不是「一个来源」，而是「一个来源 + 两条写明理由的例外」。
status: **已解决**；verify: 待实机 —— 开「移除动画」录屏，确认 Sheet 滑入 / Switch 拇指 / Chip 选中都不再有过渡

### 2. 颜色层

现状：`AppThemes.kt` 从「强调色 + 背景色」推导整套 `ColorScheme`（含 `surfaceContainer*` 全槽位），8 个预设 + 自定义；全库 **127 处** `MaterialTheme.colorScheme` 引用。已有 `AppThemesTest` 钉住 `onBackground/background ≥ 4.5:1`、`onPrimary/primary ≥ 3.0:1`（含极端浅背景与纯黑背景五组）。

要答的问题：Expressive 的默认色彩角色更强调层级与对比，接上去后 `schemeFrom()` 的推导是否还成立，还是要重写。若重写，`AppThemesTest` 必须先扩到覆盖新槽位。
status: **pending**；verify: `:app:testDebugUnitTest` 绿 + 八个预设的深浅两态截图

### 3. 形状与尺寸层

现状：`Theme.kt` 五档刻度（4/8/12/16/24），**11 处** `MaterialTheme.shapes` 引用；`Dimens.kt` 全部落在 4dp 栅格。

要答的问题：Expressive 默认组件更圆、更大，会不会把 4dp 栅格和 `touchTarget` 的约定顶破（尤其列表行高、Chip、Slider）。
status: **pending**；verify: 逐组件量一遍关键尺寸，不合栅格的列出来

### 4. 排版层

现状：**112 处** `MaterialTheme.typography` 引用，正文下限 `bodySmall`(12sp)。

要答的问题：Expressive 的强调字重会改哪些角色的默认值，正文下限是否被动。
status: **pending**

### 5. 组件层

现状：`ui/components` 下的封装文件（`AppButtons`、`AppSlider`、`AppTabs`、`AppTextField`、`AppTopBar`、`ChipRow`、`Common`、`ContentListItem`、`Dimens`、`ErrorState`、`Messages`、`Motion`、`OptionPicker`、`SettingRow`）；全库 **26 处** `@OptIn(ExperimentalMaterial3Api::class)`。

Expressive 新增的 `FloatingToolbar`、`ButtonGroup`、`LoadingIndicator` 正好撞上已有封装（`AppButtons`、阅读器菜单、`SelectionToolbar`）。逐个决定：替换、并存还是不用 —— **并存违反「有封装就用封装」**，要么换掉封装的内部实现，要么不引入。顺带复查那 26 处 opt-in 有没有已经转正、可以摘掉的。

对应表如下。总体取向：**只在收益说得出口、且能被封装收住的地方换**，其余明确写「不用」并留下理由 —— 免得下一个人再问一遍。

| Expressive 组件 | 撞上的现有实现 | 结论 |
|---|---|---|
| `LoadingIndicator` | 全库不确定进度（整页 / 按钮 / 顶栏 / 列表尾 / 阅读纸色 / 换源） | **已统一**到 `AppLoadingIndicator` / `LoadingState`。默认尺寸走形变多边形；**小于 `ContainerWidth` 时回退** `CircularProgressIndicator`（beta.5 曾强缩 18/24dp 触发 `maxWidth >= minWidth` 崩溃） |
| `PullToRefreshDefaults.LoadingIndicator` | 书架下拉刷新默认圆指示 | **已替换**（`BookshelfScreen`） |
| `ContainedLoadingIndicator` | 无对应（我们没有带容器的加载态） | 不用 |
| `WavyProgressIndicator` | 搜索 / 书源校验 / 更新下载 | **已替换**为 `DeterminateProgressBar`（`LinearWavyProgressIndicator`） |
| `HorizontalFloatingToolbar` | `SelectionToolbar` 动作行 | **已落地**。底部居中浮动药丸；替换表单仍用全宽 Surface（输入需要全宽） |
| `FloatingToolbar`（阅读菜单顶/底栏） | 全宽纸色栏 + 发丝线 | **不用浮动药丸**。同色纸上的投影会糊成脏灰线；栏内动作已改 `ButtonGroup`，Chip/Slider 走 `ReaderThemeScope` |
| `ButtonGroup` | 选区动作、阅读菜单底栏动作 | **已落地**（含 overflow） |
| `ButtonGroup` | `ChipRow` | 不用。语义是横滚单选 chip，不是定宽连体按钮组 |
| `SplitButton` | 无对应 | 不用 |
| `FloatingActionButtonMenu` | `ReplaceRuleScreen` 单个 FAB | 不用。只有一个动作；FAB 本身在 Expressive 主题下已是 Expressive 形态 |
| `MediumFlexibleTopAppBar` | 各页经典 `TopAppBar` | **已替换**（16 页收进 `AppTopBar`，配 `exitUntilCollapsed` 折叠）。四处仍留经典窄栏：搜索页/发现页的标题位是交互控件；书架与书源管理有多选态，两种栏高度不同会跳 |
| 按压形状形变（`ButtonDefaults.shapes()` / `IconButtonDefaults.shapes()` / `FilterChipDefaults.shapes()`） | `PrimaryButton`、`SecondaryButton`、裸 `IconButton`、`ChipRow` | **已替换**。这三类都有「带 `shapes` 的新重载」与「旧重载」两条路，裸写落到旧的一条，编译通过但按下不形变。收进 `AppIconButton` / `BackButton` / `AppFilterChip`，51 处图标按钮不再靠自觉传参数 |
| `TextFieldDefaults.roundedShape` / `tonalColors()` | `SearchField`、`CompactTextField`（手搓 `BasicTextField`） | **已采纳视觉、不换组件**。M3 `TextField` 最小 56dp，顶栏与对话框行放不下；但形状/容器色/光标色改取 Expressive tonal 官方值，并按聚焦切换 focused/unfocused 两档 |
| Expressive `Slider` 默认形态 | `SlimSlider`（自画 14dp 圆点 + 4dp 细轨） | **已改回默认**（`AppSlider`）。自画省下的高度换来两处代价：拖动反馈全丢、轨道色不跟主题走 |
| `MaterialShapes` | `InkwellShapes` 五档圆角刻度 | 不用。我们的刻度是 4dp 栅格；`MaterialShapes` 是装饰形状库 |
| 可交互 `ListItem` | 手搓列表行 | **已统一**到 `ContentListItem` / `ChapterListItem` |
| `FilterChip` 横条 | 手搓 chip 条 | **已统一**到 `ChipRow`（`trailing` 给额外动作） |
| `Snackbar` / `AlertDialog` | 自定义胶囊 / 24dp 圆角 | **官方 Snackbar**；`extraLarge` → 28dp |

opt-in：`ExperimentalMaterial3ExpressiveApi` 收在 `AppLoadingIndicator` / `DeterminateProgressBar` / `AppButtons`（按钮与图标按钮形变）/ `AppFilterChip` / `AppTopBar` / `SelectionToolbar` / `ReaderMenu` / 书架下拉刷新入口 —— 都在封装层，页面里没有一处。

status: **组件层按「全面符合」收口**；阅读菜单顶/底栏保持全宽纸色是纸书产品决定，不是漏用 Expressive。

### 6. 阅读器

内容区不动（`ReaderTheme` 独立，`ReaderThemeContrastTest` 钉的 ≥ 7:1 与本次无关）。要看的是 `ReaderThemeScope` 包裹的浮层：`ReaderMenu`、`SelectionToolbar`、`ChangeSourceSheet` —— 它们靠 scope 自动协调 Chip/Slider/分隔线的颜色，换主题后要确认还协调。
status: **pending**；verify: 深浅两款纸张主题下逐个浮层截图

### 7. 无障碍回归

`animationsEnabled()` 全覆盖走查 + 触控目标 + 读屏焦点。第 3 阶段后调用点从 13 处收到 4 处（`Theme.kt` 那一处是总闸，另三处是 reader 自绘翻页、`BookshelfScreen` 的 `animateItem`、`InkwellNavDisplay` 的阅读器 tween），其余由主题的 `InstantMotionScheme` 统一兜住 —— 这一项现在主要是验「开了系统『移除动画』后是否真的全静止」。
status: **pending**

### 8. 性能与体积

release 体积前后对比；**Baseline Profile 必须重生成** —— 换组件等于换类，旧 profile 里的规则会失配（Nav3 迁移刚栽过一次，912 条规则指向已不存在的类，ART 静默跳过）。手动触发 `.github/workflows/baseline-profile.yml` 即可。
status: **pending**；verify: 体积数字 + profile PR 里能看到新组件的类

### 9. 回退路径

想清楚怎么退：一次 revert，还是留一个开关。别等出了视觉事故再想。
status: **pending**

## 放弃条件（写在前面，避免沉没成本）—— **未触发**

留档备查。原文如下：只要第 1 项得出「`MotionScheme` 无法表达 `animationsEnabled()` 的 0 时长要求，且没有干净接缝」，就**停在这里**：不换主题入口，只在需要的页面局部 opt-in 吸收个别 Expressive 组件。理由是无障碍那条是硬规则（`CLAUDE.md`），而 Expressive 的收益是观感 —— 观感换不来违反硬规则的资格。

## 已落地（第 1 阶段：主题入口 + 动效方案）

原计划是「先统一动效来源，再换主题入口」两个 PR。实际反了过来 —— 因为第 1 项的解法（自己实现 `MotionScheme`）本身就得先有 Expressive 主题入口才谈得上，两件事在一个改动里才自洽。`Motion.kt` 没动，仍是我们自己转场的唯一来源。

改了四处：

- `gradle/libs.versions.toml`：`compose-bom` → **`compose-bom-alpha:2026.07.01`**（material3 1.5.0-alpha25，其余栈 1.12.0-rc01）。为什么必须换整个 BOM 而不是只钉 material3：单钉也圈不住 —— material3 1.5.0-alpha 要求 foundation/ui/animation 1.12.x，会把稳定 BOM 的 1.11.4 顶掉，结果是「toml 写 1.11.4、实际跑 1.12.0-beta01」；换 alpha BOM 反而更稳，它统一钉 rc01。
- `ui/theme/Theme.kt`：`MaterialTheme` → `MaterialExpressiveTheme`；新增 `InstantMotionScheme`，系统关动画时顶替整套 spec。配色（`AppThemes` 推导）与圆角（五档刻度）**原样保留**，本阶段不碰。
- `ui/reader/ReaderThemeScope.kt`：同样换成 `MaterialExpressiveTheme`，并显式透传 `motionScheme`（不透传会把 0 时长顶掉，见 `CLAUDE.md`）。
- `CLAUDE.md`：动效那条的「框架内建动画不受约束」例外**已删除** —— 现在受约束了；新增「主题入口」小节钉住「两个入口都必须是 Expressive」。

验证到哪一步：`:core:test`/`:reader:test`/`:app:testDebugUnitTest` **295 条全绿**，`assembleDebug` 通过，`:app:lintDebug` 通过。**实机视觉未验**（第 0 项那笔欠账仍在，而且现在欠得更多：1.4.0 默认 token 变化 + Expressive 组件形态两批叠在一起）。

新出现的弃用提示：`rememberModalBottomSheetState(skipPartiallyExpanded = …)` 在 1.5.0-alpha 被弃用，改用 `rememberBottomSheetState(initialValue = Hidden)`（命中 `AppIconSheet.kt`）。只是警告，本阶段没跟，留给组件层一起处理。

回退路径（第 9 项）：一次 revert 这四个文件即可，没有数据/持久化面的改动。BOM 退回 `androidx.compose:compose-bom:2026.06.01` 时记得把 `compose-bom-alpha` 也改回去。

## 已落地（第 3 阶段：动效单一来源 + Tab/Snackbar 收进封装）

前两阶段留下的是「局部对齐」：Expressive 主题开了，但我们自己写的转场还是 `Motion.kt` 里的硬编码 tween，与组件内部读的 `MotionScheme` 两套并存 —— 同一屏上 Sheet 弹性滑入、它上面的顶栏匀速划下来。这一阶段把它收成一个来源。

- `ui/components/Motion.kt`：八个帮手全部改读 `MaterialTheme.motionScheme`，位移取 `*SpatialSpec`、alpha 取 `*EffectsSpec`，退场取 `fast*` 档（「退场比入场快」不再靠手写毫秒）。删掉 `ENTER_MS`/`EXIT_MS`/`NAV_*` 与两条自定义 easing。帮手里**不再判** `animationsEnabled()` —— 无障碍由 `InstantMotionScheme` 在主题那一处兜住，判两遍等于两个来源。**只保留**阅读器开合两条 tween：时长与 `READER_SPLASH_DELAY_MS` 咬合，spring 给不出确定时长。（**这句后来不再成立**：页面进退也回到了 tween，见第 6 阶段。八个帮手走令牌这部分仍然有效。）
- ~~`ui/nav/InkwellNavDisplay.kt`：页面 push/pop 转场接令牌（阅读器缩放那条不动）。~~ **已推翻**，见第 6 阶段：页面进退是刻意的 tween 例外，`InkwellNavDisplay` 现在接的是 `Motion.pagePushTransform()` / `pagePopTransform()`。阅读器缩放那条从头到尾没动过。
- `ui/bookshelf/BookshelfScreen.kt`：`animateItem` 的三个 spec 接令牌；`motionOn` 保留，因为这个 API 的「不动画」写法是传 `null`，比 `tween(0)` 省掉每帧插值。
- 新增 `ui/components/AppTabs.kt`：`AppTabRow`（`PrimaryTabRow`）+ `AppTabContent`（横移 1/8 + 淡入淡出，`using(null)` 关掉 SizeTransform）。`ReaderMenu` 改用它 —— 上一轮只改了这唯一一处 Tab，但没有封装约束后来者，形态与节奏迟早再分叉。
- `Messages.kt` 的 Snackbar 已经是全局的（14 个页面都走 `AppSnackbarHost`，无裸 `Snackbar`），本轮只在 `CLAUDE.md` 里把「居中悬浮胶囊、页面别自己写 host」写成规则。

验证：`:core:test`/`:reader:test`/`:app:testDebugUnitTest` 全绿，`:app:lintDebug` + `assembleDebug` 通过。

**误诊记录（beta.8，已撤）**：用户报「三级回二级干等一秒」时，曾误判成页面转场 spring 的尾巴，把 push/pop 改回定长 tween。用户当场否掉 —— 那是返回落地前的等待，不是过渡动画。根因是设置树三级页也标成了 `detailPane`，被盖住的二级页被 scaffold 卸掉组合，返回时冷启动。已改 `extraPane`。

**这条误诊记录的结论只对了一半，订正如下**：「三级回二级干等一秒」的根因确实是 `detailPane` 卸组合，与转场无关 —— 这部分成立。但由此推出的「所以页面转场该继续走 `MotionScheme`」是**过度纠正**：当时是拿一个被误诊的 bug 去反推动效方案，两件事本就没有因果。后来页面进退还是回到了 tween，理由与那个 bug 无关：Expressive 的 spatial spring 带回弹，整屏横滑上看得出过冲；而要拟合的那条 HyperOS 曲线是「头两成时间冲掉九成行程」的极端前置减速，spring 的参数空间里表达不出来（见第 6 阶段）。原文末尾「别再照着那条改」现已作废，**以 `CLAUDE.md` 的「页面进退例外」为准**。

## 已落地（第 5 阶段：按压形变 + Flexible 顶栏 + 滑块回归默认）

上一轮盘点剩下的都是「组件换了，但调的是不带 Expressive 新形态的那条重载」，以及两处刻意自画。这一轮全收掉。先在 alpha25 的 aar 上 `javap` 确认过签名再动手 —— 这几个 API 都是**同名重载**，靠记忆写很容易以为传了参数其实落到旧的那条。

- `AppButtons.kt`：`Button`/`OutlinedButton` 传 `shapes = ButtonDefaults.shapes()`（按下圆角收一档）。新增 **`AppIconButton`**（`shapes = IconButtonDefaults.shapes()`）与 **`BackButton`** —— 后者收掉 20 个页面一字不差的「IconButton + AutoMirrored ArrowBack + contentDescription 返回」，读屏名字与 RTL 镜像从此只有一处能写错。全库 51 处图标按钮改完，页面里再无裸 `IconButton`。
- `ChipRow.kt`：新增 **`AppFilterChip`**（`shapes = FilterChipDefaults.shapes()`），`ChipRow` 内部与两处独立 chip（阅读菜单「系统亮度」、书源页「分组」）都走它。混用两种重载时，尾巴那个按下不动会像失灵。
- `AppTextField.kt`：形状取 `TextFieldDefaults.roundedShape`，容器/光标/文字/占位色取 `tonalColors()`，并接 `interactionSource` 让容器色随聚焦切档。从前是 `surfaceVariant` + 自定义圆角，换强调色时它不跟着走。**没有**改用 M3 `TextField`：56dp 下限塞不进顶栏。
- 新增 `AppSlider.kt` 替掉 `SlimSlider`（8 处调用）：回到 Expressive 默认厚轨 + 竖条 thumb + 停点。`activeColor`/`inactiveColor` 保留，只给阅读器浮层（纸色背景）用。
- 新增 `AppTopBar.kt`：`MediumFlexibleTopAppBar` + `rememberAppTopBarScroll()`（`exitUntilCollapsed`）+ `Modifier.topBarScroll()`。16 个内容页迁完。**折叠是前提**：只换组件不接滚动等于给每页白送一条永久变高的顶栏，比原来更差，所以把「建行为」和「接行为」包成看得出配对的两个函数。四处例外见上表。

验证：`:app:compileDebugKotlin` 通过；`:core:test` / `:reader:test` / `:app:testDebugUnitTest` 全绿；`assembleDebug` 通过。**实机仍未验** —— 这一轮改的恰恰是「按下去那一瞬间」和「顶栏折叠手势」，都是编译与单测看不见的东西。

## 已落地（第 6 阶段：页面进退转场收口）

第 3 阶段把八个组件帮手收进了 `MotionScheme`，页面进退则另立为 tween 例外（`CLAUDE.md` 的「页面进退例外」）。这一轮只动这一处例外，把它从「大致像 HyperOS」调到「参数说得出理由」。

改动都在 `ui/components/Motion.kt`，`InkwellNavDisplay` 只跟着改了一行注释。**两条是修 bug，不是调观感**：

- **push / pop 的位移不对称**。`pagePushExit` 把被盖住的页推到屏宽 -17.5%，而 `pagePopEnter` 只让它从 -10.5% 处回位 —— 同一个页面，推走时停在一处、回来时从另一处起步，差了 7% 屏宽，返回的第一帧会跳一下。现在两边共用 `PAGE_UNDER_SLIDE_FRACTION`（19%）。
- **同一次转场里两层用了两条时长**。入场页 350ms、被盖页 300ms，各走各的：被盖页提前 50ms 停住，压在上面的新页还在滑。分层并行的前提就是两层同一条进度，差几十毫秒会在余光里看出「背景先定住、前景后到」。现在 push 两层都用 `pagePushSpec()`（320ms），pop 两层都用 `pagePopSpec()`（280ms）。
  顺带把「退场比入场快」的适用范围写清楚了：它指 **pop 整体比 push 快**（280 vs 320），不是同一次转场里的两层之间。这条歧义已写进 `CLAUDE.md`，否则下一个人会照第 3 阶段的帮手写法把两层再拆开。

其余是观感项：曲线换成 `(0.2, 0.9, 0.1, 1)`（原 `(0.2, 0, 0, 1)` 起步有段近似线性的爬升，看着是「先动一下、再滑过去」两段感）；alpha 从位移里拆出来单走一档（push 200 / pop 180，约位移六成），免得新页整段滑行都半透明地压着旧页、两页的字叠在一起；被盖页淡到 0.6 而非 0（它还在屏幕上，淡到 0 是凭空消失，与「被压到下一层」相反）；横滑 35% → 38%、进出页缩放 0.92 → 0.95、被盖页 0.95 → 0.94。

**没做、且刻意没做的一处**：`NavDisplay.predictivePopTransitionSpec` 的 lambda 参数是返回手势的滑动**边缘**（`Int`），现在仍然忽略它，两个边缘一律「向右滑出」。从右边缘往左划时页面是逆着手指走的，看上去该跟手；但 `InkwellNavDisplay` 里已有相反的既定决定 —— 阅读器那处特意让手势返回与按键返回长得一样，理由是同一个动作不该有两种样子。按边缘分方向会直接违反它。**这个只有真机上两只手各划一次才判得了，留给实机巡检**。

验证：`:core:test` / `:reader:test` / `:app:testDebugUnitTest` 全绿，`:app:lintDebug` + `assembleDebug` 通过。**实机未验** —— 改的全是「滑动那 300 毫秒长什么样」，编译与单测一概看不见。

## 已完成（第 7 阶段：平滑圆角 + 大块元素按压下沉）

前六个阶段都在 M3 自己的刻度里调。这一阶段第一次引入**外部参照**：[Miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0），一个以实现 HyperOS 设计语言为目标的 Compose Multiplatform 组件库。

**为什么是读源码而不是加依赖。** Miuix 0.9.3 用 Kotlin 2.4.0 编译（tag `v0.9.3` 的 toml 与发布 POM 里的 `kotlin-stdlib:2.4.0` 都能对上），本项目卡在 2.3.21 —— KSP 至今没发 2.4.x，升上去 Room 的注解处理就崩（`libs.versions.toml` 里早记过这条）。旧编译器读不了新 Kotlin 的 metadata，这条路直接堵死。另外两条即使 Kotlin 不卡也在：它的 android 构件拖 `org.jetbrains.compose.foundation:foundation:1.11.1`，与本项目 1.12.0-rc01 的预发布栈没人验过；而且它是**与 M3 平行的另一套设计系统**（`MiuixTheme` 自带 Colors/TextStyles），用它等于放弃 `MaterialExpressiveTheme`，且不能半用 —— 半用就是一屏两套设计语言。

所以取的是**数值**，代码照着重写，出处写进 KDoc。

- **平滑圆角**（`ui/theme/SquircleShape.kt`）：`CornerBasedShape` 实现，五档形状刻度全部换掉，半径读数不变。每角一条三次贝塞尔，外扩 `1.1`、控制柄 `1 - 0.643`。**必须是 `CornerBasedShape` 而非裸 `Shape`** —— Expressive 按钮按下的形状形变靠 `copy()` 插值角半径，只实现 `Shape` 会让形变整个失效。`ReaderThemeScope` 传的是 `MaterialTheme.shapes`，自动跟上。
- **按压下沉**（`ui/components/PressFeedback.kt`）：`SinkIndication`，按下缩到 `0.94`、`spring(0.8, 600)` 欠阻尼收尾。走 `LayoutModifierNode` 在放置阶段改 scale，不是 `graphicsLayer` + `State`（后者每帧重组调用点，一次按压几十帧）。系统关动画时换 `snap()` —— 仍然沉下去，反馈不能丢。目前只用在书架的书封网格上。

**一堵撞上的墙，值得记住**：M3 组件把 `ripple()` 写死在内部，既不收 `indication` 也不读 `LocalIndication`。所以下沉**只能**用在我们自己持有 clickable 的地方，`ContentListItem`（M3 `ListItem` 交互重载）这类注不进去。别试图靠 `LocalRippleConfiguration provides null` 关掉水波纹求统一：关得掉，但关掉之后那些组件按下去毫无反馈，比两种反馈并存更糟。要让 M3 组件也沉下去，只能整套重写组件 —— 那就回到「换设计系统」那个选项了。

验证：`:core:test` / `:reader:test` / `:app:testDebugUnitTest` 全绿，`assembleDebug` 通过。**实机未验**，且这一阶段多出一个编译期看不见的风险点：`SquircleShape` 产出 `Outline.Generic`（Path）而非 `Outline.Rounded`，带阴影的 `Surface`/`Card` 要靠底层 `android.graphics.Outline.setPath` 投影、而那条路只接受**凸**路径。本形状是凸的，理应正常，但必须真机确认阴影没丢、边缘没锯齿。

## 剩下要做的

1. **第 0 / 6 / 7 项**：实机视觉巡检（含阅读器浮层、系统「移除动画」）—— 组件层已对齐，欠的是人眼确认。第 5 阶段留下两个必看点：顶栏折叠在 16 个页面里的手感（尤其带 `imePadding` 的表单页与带 FAB 的列表页），以及滑块变厚后阅读菜单的整体高度。第 6 阶段再添两个：进退页那 300 毫秒的整体节奏（重点看被盖住那页是否与上层同步、返回第一帧还跳不跳），以及**从左右两个边缘各划一次返回**，判断预测性返回要不要按边缘分方向。第 7 阶段再添两个，都是编译期看不见的：**带阴影的卡片/对话框投影是否还在**（`Outline.Generic` 走 `setPath`，只吃凸路径），以及书封按下去那一下的下沉幅度在真实网格间距下会不会显得整片抖动。
2. **第 8 项**：release 体积对比 + **Baseline Profile 重生成**（`LoadingIndicator`、`ButtonGroup`、`LinearWavyProgressIndicator`、`AppTabs`、`MediumFlexibleTopAppBar` 与三套 `*Shapes` 形变都是新类，旧 profile 规则覆盖不到）。
3. **还逃在 `MotionScheme` 之外的动效**，共三处，无障碍都靠 `animationsEnabled()` 单独兜着：页面进退与阅读器开合是**刻意例外**（理由分别见第 6 阶段与 `Motion.kt` 的 KDoc，两处都已在 `InkwellNavDisplay` 里接了关动画时的 instant 分支）；剩下 `reader/flip/PageFlipContainer.kt` 的翻页回弹（`tween` + `LinearOutSlowInEasing`）是**唯一还没想清楚的一处** —— reader 模块按约定不依赖 Compose 主题，且时长由手势速度算出来，`MotionScheme` 给不了确定值。要动它得先想清楚 reader 怎么拿到令牌而不反向依赖 Compose。

每一步都要跑通：`:core:test`、`:reader:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、`assembleDebug`。
