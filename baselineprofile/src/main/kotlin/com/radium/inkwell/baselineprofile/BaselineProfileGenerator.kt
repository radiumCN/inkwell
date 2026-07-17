package com.radium.inkwell.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
 * 设置、书源管理、搜索页。这占了方法量的绝大头 —— 生成出来 23062 条，其中 compose/ui 就
 * 7290 条、compose/ui/text 1418 条。而进书时**占住主线程**的正是 Compose 的组合与绘制，
 * 所以这一段恰好压在痛点上。
 *
 * **没覆盖：阅读器自己那几个类**（Paginator、正文解析、ReaderScreen）。全新安装的书架是
 * 空的，生成器走不进阅读器。让它走进去只有两条路：跑网络书源（那样书源一挂、发版就挂），
 * 或在系统文件选择器上做 UI 自动化（各 ROM 的选择器长得不一样，CI 上必然飘）。两条都是
 * 把发版绑在一个会自己坏掉的东西上。这几个类跑在 Dispatchers.Default/IO 上，不直接占主线程，
 * 优先级低于上面那一大块。
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
 * 产物落在 `app/src/release/generated/baselineProfiles/`，**要提交进仓库** —— 发版只用提交
 * 好的那份，不现场生成，所以生成器飘了也不会连累发版。代价是它会随代码漂移：改动大了记得
 * 重跑一次，不重跑不会报错，只会悄悄退回"第一次进书很卡"。
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
        startActivityAndWait()

        // 书架首帧。空书架走的是 EmptyState，有书走列表 —— 两条都在冷启动路径上，
        // 等"更多"按钮出来就说明顶栏已经组合完毕，两条都到位了。
        device.wait(Until.hasObject(By.desc("更多")), UI_TIMEOUT_MS)
        device.waitForIdle()

        openFromMenu("设置")
        openFromMenu("书源管理")

        // 搜索页：进书之外最常走的一条路
        device.findObject(By.desc("搜索"))?.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    /** 从书架右上角"更多"菜单进某一页，看一眼再退回来 */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.openFromMenu(item: String) {
        device.findObject(By.desc("更多"))?.click()
        device.wait(Until.hasObject(By.text(item)), UI_TIMEOUT_MS)
        device.findObject(By.text(item))?.click()
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.desc("更多")), UI_TIMEOUT_MS)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.radium.inkwell"

        /** 等 UI 出现的上限。生成跑在模拟器上，比真机慢，给宽一点；卡住了宁可失败也别静默少收 */
        const val UI_TIMEOUT_MS = 10_000L
    }
}
