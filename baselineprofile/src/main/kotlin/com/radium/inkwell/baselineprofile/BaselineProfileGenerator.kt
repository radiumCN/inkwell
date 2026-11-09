package com.radium.inkwell.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * 生成 Baseline Profile：把这段旅程里跑到的类/方法列成清单随包发出去，
 * ART 在**安装时**就把它们 AOT 编译好。
 *
 * 为什么值得为它单开一个模块：实测同一台机器反复进同一本书，卡顿从 65ms 一路降到 16ms——
 * 那条下降曲线是 ART 在 JIT 热身。没有 Baseline Profile 时，全新安装后代码先解释执行、
 * 边跑边编译，**第一次**用最慢；跑热了才快。而真实用户每次开 App 都是"第一次"，
 * 永远吃不到热身后的那个数。这个 profile 就是把热身的结果预先塞给他。
 *
 * ### 这段旅程覆盖了什么、没覆盖什么
 *
 * 覆盖：冷启动（Application/Koin/Room/DataStore）、Compose 起步与首帧、书架、导航转场、
 * 设置（含关于）、书源与净化 → 书源管理、搜索页、**阅读器进书与翻页**（夹具 txt，见 [ProfileSeed]）。
 * 生成出来的规则量里 compose/ui 仍是大头，进书时占住主线程的也是组合与绘制。
 *
 * 阅读器靠启动 extra 把 `assets/benchmark/seed.txt` 导入书架再点进去，不碰文件选择器、
 * 也不走网络书源 —— 那两条在 CI 上都会自己坏掉。
 *
 * ### 别去写手工规则补它 —— 试过了，不成立
 *
 * 直觉的补法是在 `app/src/main/baseline-prof.txt` 里手写通配规则（`HSPL` + 包路径 +
 * `**->**(**)**`）把缺的点名补上。**它在本项目无效，
 * 而且是静默无效**：合并阶段规则确实进去了（merged_art_profile 里看得到），但 release 开着
 * `isMinifyEnabled`，R8 会把 profile 按混淆表重写一遍，而
 * - 通配规则映射不到某个具体混淆名 → 整条丢掉；
 * - 显式类名也救不了：R8 用的是 proguard-android-optimize，`Paginator` 这种类**整个被内联**
 *   掉了，mapping.txt 里压根没有它的类映射，只剩方法被并进别的类。
 *
 * 两种写法过完 R8 都是 0 条，且不报错、不告警。要补覆盖只有一条正路：**让生成器真的把那段
 * 代码跑一遍**，让规则以 R8 认得的形式产生。
 *
 * ### 怎么重新生成
 *
 * ```
 * emulator -avd <avd> -no-window &      # 需 API ≥ 35 的镜像
 * ./gradlew :app:generateBaselineProfile
 * ```
 * 手边没设备就手动触发 CI 的 **Baseline Profile** 工作流
 * （`.github/workflows/baseline-profile.yml`），它在模拟器上跑同一条命令并开 PR 回写。
 *
 * 产物落在 `app/src/release/generated/baselineProfiles/`，**要提交进仓库** —— 发版只用提交
 * 好的那份，不现场生成，所以生成器飘了也不会连累发版。代价是它会随代码漂移：改动大了记得
 * 重跑一次，不重跑不会报错，只会悄悄退回"第一次进书很卡"。**Nav3 迁移就这么栽过一次**：
 * profile 里 912 条规则指向 `androidx/navigation` 下的类、还有指向已删除的 `InkwellNavHostKt`，
 * 全部失配 —— 而 ART 对不匹配的规则静默跳过，看着几万行、实际一条不生效。
 *
 * ### 装上不等于生效 —— 实机验证必读
 *
 * profile 进了包只是第一步。实测一整条链：
 * ```
 * adb install app-release.apk        → [status=verify]        没有 AOT
 * 启动一次（ProfileInstaller 写入）   → [status=verify]        还是没有
 * adb shell cmd package bg-dexopt-job → [status=speed-profile] 这时才生效
 * ```
 * 中间隔着 ART 的后台 dexopt 任务，真机上它要"息屏 + 充电 + 空闲"才跑，通常是夜里。
 * 所以**装上 beta 立刻测是测不出 Baseline Profile 的**，只会测到别的改动 —— 想当场验证
 * 就手动敲上面那行 bg-dexopt-job 把它逼出来，或者充一夜电第二天再测。
 *
 * 查当前状态：`adb shell dumpsys package dexopt | grep -A3 com.radium.inkwell`
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        // 顺带产出 startup profile：AGP 用它重排 dex 里的类，把冷启动要用的挪到一起，
        // 减少启动时的缺页。与 baseline profile 是两件事，两件都要。
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait {
            it.putExtra("com.radium.inkwell.seed_profile_book", true)
        }

        // 书架首帧。空书架走的是 EmptyState，有书走列表 —— 两条都在冷启动路径上，
        // 等顶栏的齿轮出来就说明顶栏已经组合完毕，两条都到位了。
        awaitBookshelf()

        // 设置树：一级页 → 关于；一级页 → 书源与净化 → 书源管理。
        // 书架上从前是个三点「更多」菜单，2026-07 换成了齿轮。生成器没跟着改，又是 `?.click()`
        // 找不到就静默跳过 —— 可后面那句 pressBack 照按不误，在书架根页上按返回**直接退出了
        // App**，之后的搜索、进书、翻页全对着桌面点了个空。生成出来的 profile 于是只剩冷启动
        // 和书架：设置页 0 条、阅读器 1 条，进设置、进书全回到解释执行，卡顿就是这么来的。
        // 所以现在一律走 [tap]：找不到就当场失败，宁可这次不更新 profile，也别交一份残缺的。
        tap(By.desc("设置"), "书架顶栏的设置齿轮")
        // 「关于」在设置页最底下：顶上加了概览卡、各组加了标题之后，矮一点的模拟器一屏装不下，得先滚到它
        tap(By.text("关于"), "设置 → 关于", scrollToFind = true)
        back()
        tap(By.text("书源与净化"), "设置 → 书源与净化")
        tap(By.text("书源管理"), "书源与净化 → 书源管理")
        back()
        back()
        back()
        awaitBookshelf()

        // 搜索页：进书之外最常走的一条路
        tap(By.desc("搜索"), "书架顶栏的搜索")
        back()
        awaitBookshelf()

        // 夹具书：等导入落书架（onCreate 里异步），再点进去覆盖分页/翻页/阅读菜单。
        //
        // 进没进书分两段确认，各报各的错：从前只等正文里的「第1章」，等不到时分不清是「点了
        // 没离开书架」还是「进了阅读器但字没读到 / 排版太慢」。阅读器正文画在画布上，
        // 能稳妥认出来的是阅读菜单里的「退出阅读」按钮 —— 顺带把菜单也收进 profile。
        tap(By.text(SEED_TITLE), "书架上的夹具书（ProfileSeed 导入）")
        check(device.wait(Until.gone(By.desc("搜索")), UI_TIMEOUT_MS)) {
            "点了夹具书却没离开书架：书卡点击没打开阅读器。${describeScreen()}"
        }
        device.waitForIdle()
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe((w * 0.85f).toInt(), h / 2, (w * 0.2f).toInt(), h / 2, 25)
        device.waitForIdle()
        // 页还没排出来时点中间会被吞掉，只点一次就可能白等满超时 —— 隔一段补点一下，
        // 间隔放宽到 5 秒，免得菜单刚慢吞吞地弹出来就被下一下点回去
        val menuShown = (1..READER_TIMEOUT_MS / MENU_RETRY_MS).any {
            device.click(w / 2, h / 2)
            device.wait(Until.hasObject(By.desc("退出阅读")), MENU_RETRY_MS)
        }
        check(menuShown) { "进了阅读页但呼不出阅读菜单：阅读器没排出页，或点中间没反应。${describeScreen()}" }
        device.waitForIdle()
        // 不数返回键：菜单此刻可能开着也可能正在收（上面补点那一下可能把它点了回去），
        // 写死按两下的话，菜单其实已关时第二下就落在书架根页上，把 App 退了 —— CI 上真出过
        backToBookshelf()
    }

    /** 按返回直到回到书架；每按一下之前先看是不是已经到了，绝不在书架根页上多按 */
    private fun MacrobenchmarkScope.backToBookshelf() {
        // 每按一下前屏上是什么都记下来：失败时才看得出是哪一下走岔的
        val trail = mutableListOf<String>()
        repeat(MAX_BACKS) { i ->
            if (onBookshelf()) return awaitBookshelf()
            trail += "第 ${i + 1} 下返回前 —— ${describeScreen()}"
            // 返回键会被前台的系统窗口接走、根本到不了阅读器：CI 上第一下关掉菜单后，阅读器进入
            // 沉浸式全屏，焦点随即落到包名为 android 的系统窗口上（首次全屏的提示，或慢模拟器上的
            // 无响应对话框），之后三下返回全打在它身上。先把它点掉再按
            if (dismissSystemWindow()) trail += "  → 点掉了前台的系统窗口"
            back()
            device.wait(Until.hasObject(By.desc("搜索")), BACK_SETTLE_MS)
        }
        awaitBookshelf(trail.joinToString("\n", prefix = "\n"))
    }

    /**
     * 书架根页的标志：顶栏的「搜索」与「设置」同时在。只认「设置」不够 ——
     * 阅读菜单里也有个叫「设置」的按钮，菜单开着时会把阅读页错认成书架
     */
    private fun MacrobenchmarkScope.onBookshelf(): Boolean =
        device.hasObject(By.desc("搜索")) && device.hasObject(By.desc("设置"))

    /** 等到元素出现再点。找不到直接失败 —— 见 generate() 里那段「静默跳过 + 返回键退出 App」的事故 */
    /**
     * @param scrollToFind 目标可能在一屏之外：先等页面出来，屏上没有就把页面里能滚的那块往下滚到它出现。
     *   UiAutomator 只认屏上看得见的节点，Compose 的 verticalScroll 里滚出去的行找不到
     */
    private fun MacrobenchmarkScope.tap(selector: BySelector, what: String, scrollToFind: Boolean = false) {
        if (scrollToFind) {
            device.wait(Until.hasObject(By.scrollable(true)), UI_TIMEOUT_MS)
            if (!device.hasObject(selector)) {
                device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(selector))
            }
        }
        // 找到与点下之间页面可能重组（转场还在走、列表刚挂上），手里那个节点随即作废，
        // click() 抛 StaleObjectException —— CI 第二轮在「书源管理」上真碰到过。重新找一次再点
        repeat(TAP_ATTEMPTS) { attempt ->
            val target = device.wait(Until.findObject(selector), UI_TIMEOUT_MS)
                ?: error("找不到「$what」—— 界面改过了？生成器要跟着改，否则 profile 会悄悄只剩冷启动。${describeScreen()}")
            try {
                target.click()
                device.waitForIdle()
                return
            } catch (e: StaleObjectException) {
                if (attempt == TAP_ATTEMPTS - 1) throw e
                device.waitForIdle()
            }
        }
    }

    /**
     * 失败时把「此刻屏上是什么」一起报出去。CI 上看不到屏幕，只凭一句「等不到 xx」分不清
     * 是 App 退了、弹了个没想到的对话框，还是停在别的页 —— 每猜一次就是一轮几分钟的模拟器
     */
    private fun MacrobenchmarkScope.describeScreen(): String {
        val labels = runCatching {
            device.findObjects(By.pkg(PACKAGE)).asSequence()
                .flatMap { sequenceOf(it.contentDescription, it.text) }
                .filterNotNull()
                .filter { it.isNotBlank() }
                .distinct()
                .take(40)
                .toList()
        }.getOrElse { listOf("（读取失败：${it.javaClass.simpleName}）") }
        val foreground = device.currentPackageName
        // 前台不是自己时，那个窗口上写了什么才是关键（是全屏提示还是无响应对话框）
        val foregroundLabels = if (foreground == PACKAGE) "" else runCatching {
            "；前台窗口上：" + device.findObjects(By.pkg(foreground)).asSequence()
                .flatMap { sequenceOf(it.contentDescription, it.text) }
                .filterNotNull()
                .filter { it.isNotBlank() }
                .distinct()
                .take(20)
                .toList()
        }.getOrDefault("")
        return "当前前台：$foreground；屏上的文字/描述：$labels$foregroundLabels"
    }

    /**
     * 前台是别人家的窗口（系统的首次全屏提示、无响应对话框）时，点它的确认按钮让焦点回来。
     * 「等待」而不是「关闭应用」：关了就前功尽弃。点掉了返回 true
     */
    private fun MacrobenchmarkScope.dismissSystemWindow(): Boolean {
        if (device.currentPackageName == PACKAGE) return false
        val button = device.findObject(By.text(SYSTEM_DISMISS)) ?: return false
        // 系统窗口自己也可能刚好在消失，节点作废就当没点到，下一轮再看
        val clicked = runCatching { button.click() }.isSuccess
        device.waitForIdle()
        return clicked
    }

    private fun MacrobenchmarkScope.back() {
        device.pressBack()
        device.waitForIdle()
    }

    /** 回到书架根页：以顶栏的搜索 + 齿轮为准（多选态下才有的「更多」不能当标志），见 [onBookshelf] */
    private fun MacrobenchmarkScope.awaitBookshelf(history: String = "") {
        // 轮询两样一起在，而不是 wait 其中一样：搜索页自己也可能有个「搜索」，
        // 转场没走完时只等到它就往下判，会把「还在路上」误报成「回不去」
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (!onBookshelf()) {
            dismissSystemWindow()
            check(SystemClock.uptimeMillis() < deadline) {
                "等不到书架顶栏的搜索与设置：没回到书架，或 App 已经退出。${describeScreen()}$history"
            }
            Thread.sleep(POLL_MS)
        }
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.radium.inkwell"

        /** 与 app 里 ProfileSeed.TITLE 一致 */
        const val SEED_TITLE = "Benchmark"

        /** 等 UI 出现的上限。生成跑在模拟器上，比真机慢，给宽一点；卡住了宁可失败也别静默少收 */
        const val UI_TIMEOUT_MS = 10_000L

        /**
         * 阅读器首屏要导入、分章、排版：生成时代码被重置成解释执行，模拟器又是软件渲染，
         * 比别的页慢得多，单独放宽
         */
        const val READER_TIMEOUT_MS = 30_000L
        const val MENU_RETRY_MS = 5_000L

        /** 阅读页回书架最多按几下返回：选区 / 菜单 / 阅读页各一层，再留一下余量 */
        const val MAX_BACKS = 4
        const val BACK_SETTLE_MS = 3_000L
        const val POLL_MS = 200L
        const val TAP_ATTEMPTS = 3

        /** 系统窗口上「放行」的那个按钮：首次全屏提示的「知道了」、无响应对话框的「等待」 */
        val SYSTEM_DISMISS: Pattern =
            Pattern.compile("(?i)got it|wait|ok|知道了|我知道了|等待|确定")
    }
}
