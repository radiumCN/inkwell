# M3 Expressive 评估清单

**当前状态：第 1 阶段已落地** —— 主题入口已换成 `MaterialExpressiveTheme`，动效方案的无障碍接缝已解决（见文末「已落地」）。**实机视觉巡检（第 0 项）与组件层（第 5 项）仍未做**，剩下的项照这份走，做完把结论写回来。

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

现状：`ui/components` 下 **11 个**封装文件（`AppButtons`、`AppTextField`、`ChipRow`、`Common`、`Dimens`、`ErrorState`、`Messages`、`Motion`、`OptionPicker`、`SettingRow`、`SlimSlider`）；全库 **26 处** `@OptIn(ExperimentalMaterial3Api::class)`。

Expressive 新增的 `FloatingToolbar`、`ButtonGroup`、`LoadingIndicator` 正好撞上已有封装（`AppButtons`、阅读器菜单、`SelectionToolbar`）。逐个决定：替换、并存还是不用 —— **并存违反「有封装就用封装」**，要么换掉封装的内部实现，要么不引入。顺带复查那 26 处 opt-in 有没有已经转正、可以摘掉的。

对应表如下。总体取向：**只在收益说得出口、且能被封装收住的地方换**，其余明确写「不用」并留下理由 —— 免得下一个人再问一遍。

| Expressive 组件 | 撞上的现有实现 | 结论 |
|---|---|---|
| `LoadingIndicator` | `LoadingState`（`Common.kt`，5 个页面在用） | **已替换**。整页加载态换成形变多边形，调用点一处没动 |
| `LoadingIndicator`（小尺寸） | `AppButtons` 的 18dp 按钮转圈、`SettingRow` 行尾 18dp、Explore/Search 的 24dp 内联 | **不用**。形变要靠形状变化才读得出，缩到 ≤24dp 糊成一团，不如一圈弧线清楚。尺寸分界已写进 `LoadingState` 的 KDoc |
| `LoadingIndicator`（阅读器内） | `ReaderScreen` / `ChangeSourceSheet` 的转圈（纸色着色） | **不用**。内容区是刻意独立的纸张世界，多边形形变在纸上过跳 |
| `ContainedLoadingIndicator` | 无对应（我们没有带容器的加载态） | 不用 |
| `WavyProgressIndicator` | `LinearProgressIndicator` × 3（书源校验、搜索、更新下载） | **暂不用**。默认高度从 4dp 涨到约 10dp，会顶破这三处的紧凑布局；而且都是「要读进度数字」的确定进度，波浪是纯装饰。等实机看过再议 |
| `FloatingToolbar` | `SelectionToolbar`（选中文字后贴底的操作条） | **不用**。贴底是刻意决定 —— 浮动要算选区避让还得躲挖孔，`SelectionToolbar` 的 KDoc 已写明；换成浮动药丸等于推翻这个结论 |
| `FloatingToolbar` | `ReaderMenu` 的顶栏 / 底栏 | **不用**。它们是全宽栏 + 发丝线分层、与纸张同色不投影（同色纸上的投影会糊成脏灰线）；浮动药丸必然带阴影 |
| `ButtonGroup` | `SelectionToolbar` 里四个 TextButton 一排 | **候选，未落地**。这是全库最贴合的一处（连体按钮 + 按压挤压邻居），但它改的是核心阅读交互，必须实机看过再定 |
| `ButtonGroup` | `ChipRow` | 不用。`ChipRow` 是横滚单选 chip 条，不是定宽连体按钮组，语义不同 |
| `SplitButton` | 无对应（没有「主操作 + 下拉」形态的按钮） | 不用 |
| `FloatingActionButtonMenu` / `ToggleFloatingActionButton` | `ReplaceRuleScreen` 的单个 FAB | 不用。只有一个动作，展开菜单没有意义 |
| `MaterialShapes` | `InkwellShapes` 五档圆角刻度 | 不用。我们的刻度是 4dp 栅格的一部分；`MaterialShapes` 是装饰形状库（`LoadingIndicator` 内部已经在用它） |
| 可交互 `ListItem`（Expressive） | 各页手搓 `Row` + `clickable`/`combinedClickable` | **已统一**到 `ContentListItem` / `ChapterListItem`（`ui/components/ContentListItem.kt`）。覆盖书架、搜索/发现 `BookListRow`、书源/RSS/净化、目录三处、换源、OptionPicker、AppIcon、SettingRow/SwitchRow。未选 `surfaceContainerLow`、选中 `secondaryContainer`；LazyColumn 用 `listContentPadding` + `ListSpacing`。长列表不用 `SegmentedListItem` |

opt-in 复查结论：**26 处 `ExperimentalMaterial3Api` 一处都摘不掉** —— `AppBarKt` 在 alpha25 里仍带这个标记，而这些 opt-in 绝大多数是为 `TopAppBar` 那一套加的。反倒新增了 1 处 `ExperimentalMaterial3ExpressiveApi`（`Common.kt` 的 `LoadingState`）：`MaterialExpressiveTheme` 与 `MotionScheme` 本身已不需要 opt-in，但**组件另算**，`LoadingIndicator` 还是实验性的。收在封装里就是为这个 —— API 变了只改一处。

顺手确认了一件相关的事（本来担心是第 1 阶段引入的回归）：`ProgressIndicatorKt` / `LoadingIndicatorKt` / `WavyProgressIndicatorKt` **都不读 `MotionScheme`**（反编译，0 处引用）。所以「移除动画」下 `InstantMotionScheme` 不会把转圈冻成一张静态残图 —— 那种冻住的进度指示比转着更像坏了。

弃用迁移：`rememberModalBottomSheetState(skipPartiallyExpanded = true)` → `rememberBottomSheetState(initialValue = Hidden, enabledValues = setOf(Hidden, Expanded))`（`AppIconSheet.kt`，按官方 `ReplaceWith` 改）。

status: **本轮完成**（表内「候选/暂不用」两项挂在实机验证后）；verify: 见上表，每行有结论

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

- `ui/components/Motion.kt`：八个帮手全部改读 `MaterialTheme.motionScheme`，位移取 `*SpatialSpec`、alpha 取 `*EffectsSpec`，退场取 `fast*` 档（「退场比入场快」不再靠手写毫秒）。删掉 `ENTER_MS`/`EXIT_MS`/`NAV_*` 与两条自定义 easing。帮手里**不再判** `animationsEnabled()` —— 无障碍由 `InstantMotionScheme` 在主题那一处兜住，判两遍等于两个来源。**只保留**阅读器开合两条 tween：时长与 `READER_SPLASH_DELAY_MS` 咬合，spring 给不出确定时长。
- `ui/nav/InkwellNavDisplay.kt`：页面 push/pop 转场接令牌（阅读器缩放那条不动）。
- `ui/bookshelf/BookshelfScreen.kt`：`animateItem` 的三个 spec 接令牌；`motionOn` 保留，因为这个 API 的「不动画」写法是传 `null`，比 `tween(0)` 省掉每帧插值。
- 新增 `ui/components/AppTabs.kt`：`AppTabRow`（`PrimaryTabRow`）+ `AppTabContent`（横移 1/8 + 淡入淡出，`using(null)` 关掉 SizeTransform）。`ReaderMenu` 改用它 —— 上一轮只改了这唯一一处 Tab，但没有封装约束后来者，形态与节奏迟早再分叉。
- `Messages.kt` 的 Snackbar 已经是全局的（14 个页面都走 `AppSnackbarHost`，无裸 `Snackbar`），本轮只在 `CLAUDE.md` 里把「居中悬浮胶囊、页面别自己写 host」写成规则。

验证：`:core:test`/`:reader:test`/`:app:testDebugUnitTest` 全绿，`:app:lintDebug` + `assembleDebug` 通过。

**误诊记录（beta.8，已撤）**：用户报「三级回二级干等一秒」时，曾误判成页面转场 spring 的尾巴，把 push/pop 改回定长 tween。用户当场否掉 —— 那是返回落地前的等待，不是过渡动画。根因是设置树三级页也标成了 `detailPane`，被盖住的二级页被 scaffold 卸掉组合，返回时冷启动。已改 `extraPane`；页面转场**继续走** `MotionScheme`，beta.8 的 tween 例外已撤回，别再照着那条改。

## 剩下要做的

1. **第 0 项**：实机全页面截图巡检 —— 唯一的真实风险敞口，下面两项都卡在它后面；这轮换了转场物理，更该看一眼。
2. 表里挂起的两项：`SelectionToolbar` 要不要换 `ButtonGroup`；三处 `LinearProgressIndicator` 要不要换 `WavyProgressIndicator`（先量它的实际高度）。
3. **第 8 项**：release 体积对比 + **Baseline Profile 重生成**（`LoadingIndicator`、`AppTabs` 都是新类，旧 profile 规则覆盖不到）。

每一步都要跑通：`:core:test`、`:reader:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、`assembleDebug`。
