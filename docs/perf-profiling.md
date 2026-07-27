# 性能剖析

## Development Plan

**Task**: 对 Inkwell 做一次有测量支撑的性能剖析，产出「哪里慢、慢多少、为什么」的定级清单；只补测量设施，不在本轮做大改。

**Relevant Skills And Rules**:
- `karpathy-guidelines` —— 先定可验证的成功判据再动手；不猜、不顺手改邻近代码；每条结论都要有对应的数。
- `anthropics-skills-browser` —— 查过，该 skill 面向 SKILL.md 写作，与性能剖析无关，**未采用**。本仓库也没有 Android 性能剖析的本地 skill，按 fallback 走通用最佳实践。
- `CLAUDE.md` —— 核心逻辑下沉纯 JVM、可单测；`core/` 不依赖 Android。这条直接决定了剖析分层：能在 JVM 测的就别上设备。
- 项目既有约定（`BaselineProfileGenerator` / `StartupBenchmark` 的 KDoc）—— 本项目在性能上**连着猜错三次**，规矩是「先出数再说」。本轮沿用：不出数的结论不写进清单。

**测量环境与可信度边界**（先写清楚，避免把不可信的数当结论）:
- 设备：仅有模拟器 `Pixel_10_Pro`（x86 + swiftshader 软件渲染），**无真机**。
- 因此：启动/帧率的**绝对值不可信**；只用同一台机器上的**相对差**。软件渲染下的帧耗时尤其不能外推到真机 GPU。
- 纯 JVM 的计算热点（分页、解析、规则求值）在开发机 JVM 上测，与设备无关的**算法复杂度问题**可信。
- Compose 编译器指标是**静态产物**，与设备无关，完全可信。

**Open Questions**:
- [x] 有没有可用设备 —— 有模拟器，无真机。已按上面的边界降级采信。
- [x] 阅读器（最重要的性能面）能否在全新安装上被 macrobenchmark 触达 —— **不能**，空书架进不去阅读器，且导入走系统文件选择器（`BaselineProfileGenerator` KDoc 已论证这条路会让发版绑在会自己坏的东西上）。故阅读器热点改用 JVM 层测量覆盖。

**Planned Steps And Tracking**:
1. Compose 编译器指标：开 `composeCompiler` reports/metrics，找不可跳过（non-skippable）的 composable 与不稳定（unstable）类型 —— status: **done**; verify: 已产出 `app-composables.txt` / `*-classes.txt`，结论见下
2. 纯 JVM 计算热点计时：分页链、TXT 切章、编码探测、HTML→元素、规则求值，按真实量级输入跑 —— status: **done**; verify: 已有 ms 级数字，未发现超线性项
3. 设备侧冷启动基线：跑既有 `StartupBenchmark`（None vs BaselineProfile 两档）—— status: **done**; verify: 见「冷启动」一节
4. 静态排查已知 Android 性能反模式（主线程 IO、DB N+1、无界缓存、逐帧分配）—— status: **done**; verify: 每条给出文件行号并标注证据强度
5. 汇总定级清单写回本文档 —— status: **done**; verify: 见「结论」

---

# 剖析结果

一句话结论：**没有找到值得现在动手的性能缺陷。** 四条测量线（Compose 编译器指标、JVM 计算热点、设备冷启动、静态排查）都指向同一件事 —— 这套代码已经被认真调过。下面把数留档，并列出 3 条「值得补」的项，全部是**测量能力**上的缺口，不是运行时缺陷。

## 1. Compose 编译器指标（证据强度：高，静态产物、与设备无关）

复现：`./gradlew :app:assembleRelease -PcomposeMetrics`，产物在 `app/build/compose_metrics/`。

- `:app` 86 个 composable、`:reader` 6 个，**全部 `restartable skippable`**。没有一个因为参数不稳定而丢掉跳过能力。
- 唯一「不可重启」的 8 个是 `animationsEnabled` / `topBarEnter` / `scrimExit` 等**有返回值**的工具函数 —— 有返回值的 composable 本来就不该 restartable，这是正确的，不是问题。
- `:app` 有 76 个 unstable 类，但清一色是 ViewModel / Repository / Room `*_Impl` / `$serializer`。它们**不作为 composable 参数传递**（否则上面那条不会全绿），所以不影响重组。

### 值得记住的一条：`:reader` 靠「强跳过 + 引用稳定」成立

`PageCanvas` / `PageFlipContainer` / `ScrollItemView` 的参数 `RenderablePage`、`ScrollChapter` 是 unstable 的，但函数仍标着 skippable —— 这是 Kotlin 2.x 默认开启的**强跳过（strong skipping）**：unstable 参数改用**引用相等**（`===`）比较，而不是 `equals()`。

也就是说，这几个绘制层能不能跳过，**完全取决于实例引用稳不稳**。目前是稳的，因为 `RenderablePage` 在 `ReaderViewModel.renderable()`（ViewModel 层）构造，不在组合期新建；无关字段走 `state.copy(...)` 时引用原样保留。

**这是一条隐性契约**：哪天有人图省事把 `RenderablePage(...)` 挪进 composable 里构造，编译器指标不会变（照样显示 skippable），但每帧都会重建实例、每帧全页重绘，而且没有任何告警。改这几个文件时要留意。

## 2. JVM 计算热点（证据强度：中 —— 数真实，但跑在开发机 x86 JVM 上）

用合成的中文长篇（1200 章 × 40 段 × 60 字 ≈ 294 万字符 ≈ 8 MB UTF-8）实测：

| 项 | 中位数 |
|---|---|
| `TxtChapterSplitter.split`（294 万字符） | **5.4 ms** |
| `TxtParser.open` 端到端（读盘 + 解码 + 切章） | **30.5 ms** |
| `TxtBookHandle` 常驻堆（handle 存活期间） | **≈ 6 MB** |
| `loadChapter`（中间一章） | **0.3 ms** |
| `Jsoup.parse`（400 段网页正文） | **2.9 ms** |
| `HtmlToElements.convert`（400 段） | **0.9 ms** |

**一个被推翻的假设**（留档，免得下次再猜一遍）：动手前我判断 `TxtChapterSplitter.scanLines` 会是瓶颈 —— 它为全文**每一非空行**都建一个 `Line` 对象、附带两次 `substring`，20 万行就是 40 万次分配。实测 5.4 ms，占整条导入链的 1/6，**不成立**。原因是长度检查 `line.content.length <= maxTitleLength` 排在正则前面短路，绝大多数正文行根本走不到正则；而分配本身对 JVM 分代 GC 太便宜了。

按手机比开发机慢 5–10 倍折算，导入一本 8 MB 的书约 150–300 ms，一次性操作，可接受。

## 3. 冷启动（证据强度：低 —— 模拟器软件渲染，只看**两档之差**，绝对值不可外推真机）

环境：`Pixel_10_Pro` AVD（x86 + swiftshader），10 轮 × 2 档。macrobenchmark 默认拒绝在模拟器上跑，本次用命令行参数临时放行（**刻意不写进构建文件**，否则 CI 会静默采信模拟器数）：

```
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

| 档位 | timeToInitialDisplayMs 中位数 | min | max |
|---|---|---|---|
| `CompilationMode.None`（无 AOT） | 1154.8 | 1055.7 | 1285.3 |
| `BaselineProfileMode.Require` | **1014.4** | 955.3 | 1219.8 |

**Baseline Profile 净收益 ≈ −140 ms（−12.2%）。** 这套设施在起作用，值得继续维护。

其它：release APK **7.74 MB**（debug 75.18 MB）—— 对一个 Compose 应用来说很精简，包体不是问题。

## 4. 静态排查（逐条核对过，非推测）

已经做对、无需改动的：

- **13 处 Lazy 列表全部带稳定 `key`** —— 没有一处漏。
- **分页跑在 `Dispatchers.Default`**（`ReaderViewModel.kt:917` / `:1089`），不占主线程。
- **缓存目录遍历在 `Dispatchers.IO`**（`SettingsScreen.kt:106`），且带注释说明原因。
- **分页结果内存有界** —— `trimWindow` 只保留 center±1（`ReaderViewModel.kt:1167`），注释写明 `TextLayoutResult` 很重。
- **预取避让入场动画** —— `prefetchAhead` 的 `PREFETCH_LEAD_IN_MS` 是实测驱动的（KDoc 记着「掉帧全部落在进书后 56~147ms」），这是一次做得很正的性能修复。
- **`animationsEnabled()` 被提到 `items` 外面**（`BookshelfScreen.kt:332`），否则每本书挂一个 `ContentObserver`。

---

# 结论：3 条值得补的项

全部是**测量能力**缺口，不是运行时缺陷。按性价比排序。

### A. 应用从不调用 `reportFullyDrawn()` —— 启动基准测的不是它自以为测的东西（建议做）

- **位置**：全仓库无 `reportFullyDrawn` / `ReportDrawn*`（已全文搜索确认）。
- **证据**：实测输出里只有 `timeToInitialDisplayMs`，没有 `timeToFullDisplayMs`。
- **为什么要紧**：`StartupBenchmark` 的 KDoc 明确说「用户眼里的『启动完』是内容出现」，并为此加了 `device.wait(By.desc("更多"))`。但那行 `wait` 只保证**迭代结束前应用已起来**（让下一轮冷启动公平），它**不改变上报的指标** —— 报出来的 1014 ms 仍是首帧（TTID），而书架的书是从 Room 异步来的，首帧时可能还是空的。也就是说，代码的意图和指标的口径对不上，而且这个偏差是静默的。
- **建议**：在书架首批数据落地处加 `ReportDrawnWhen { !isLoading }`（androidx.activity 提供），`StartupTimingMetric` 就会同时给出 `timeToFullDisplayMs`。改动小、无运行时开销。

### B. `TextMeasurer(cacheSize = 0)` 关掉了文本测量缓存，且无依据（建议先量再定）

- **位置**：`reader/.../measure/ComposeTextMeasureFacade.kt:45`。
- **证据强度**：低 —— **这是读码推断，没有实测支撑**。`git log -S` 显示这个 `0` 来自初版提交（`7555822 reader: Canvas 自绘排版分页引擎与全量翻页动画`），不是后来调优的结果，所以「当初就想清楚了」这个假设不成立。
- **两面**：一次 `paginate()` 内每段只测一次，缓存帮不上；改字号/边距后重排走的是不同 style，缓存必然 miss。真正可能受益的是「同一章同一 spec 被排两遍」（翻页模式与滚动模式切换）。而代价是 `TextLayoutResult` 很重，缓存会带来一份不显眼的常驻内存 —— 项目别处的注释对这点很警惕。
- **建议**：**不要凭直觉改成非零**。要动就先在设备上量「切换翻页/滚动模式」这条路径，有数再定。

### C. `refreshNeighbors()` 每次都重建邻页对象（建议记录，暂不动）

- **位置**：`ReaderViewModel.kt:1060`，`neighborPages` → `renderable()` 每次调用都 `new RenderablePage`。
- **证据强度**：低 —— **读码推断，未实测**。
- **影响**：结合第 1 节那条「引用相等」的机制，即使邻页内容没变，新实例也会让 `prev`/`next` 两个 `PageCanvas` 各做一次整页重绘。频率是**每次翻页一次**（不是每帧），而且正好落在翻页动画窗口里。
- **为什么不建议现在动**：没有实测证明它可感知，而这里的代码有大量关于「进度不能漂」的正确性约束（见 `showPage` / `ensurePaginated` 的 KDoc），为一个未证实的收益去动它不划算。等有真机、能量帧耗时了再说。

---

# 本次剖析没能覆盖的（说清楚，免得被当成「已验过」）

- **无真机**。冷启动的绝对值、以及一切帧耗时/掉帧数据都拿不到可信值。软件渲染下的帧数据尤其不能外推。
- **阅读器本身没有被设备侧测量覆盖**，原因与 `BaselineProfileGenerator` KDoc 里论证过的一致：全新安装书架是空的，走不进阅读器；而让它走进去要么依赖网络书源、要么依赖各 ROM 不一样的系统文件选择器，两条都会让测量本身变得不可靠。**这意味着「进书卡不卡」「翻页掉不掉帧」这两个最贴近用户体感的问题，本轮没有数。**
- **没有做 Perfetto trace 的深入分析**。模拟器上的启动 trace 绝对值不可信，逐段拆解的收益不足以抵消其误导风险。
- 第 2 节的 JVM 数字跑在开发机上，手机上的折算倍数（5–10×）是经验值，**未实测**。

要把上面这些补上，最小代价是接一台真机：那样 A 能立刻验证，B/C 也能从「读码推断」升级成有数的结论。

