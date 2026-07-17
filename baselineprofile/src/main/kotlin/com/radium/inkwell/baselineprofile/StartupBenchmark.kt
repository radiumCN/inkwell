package com.radium.inkwell.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 量 Baseline Profile 到底值不值：同一个包，跑两遍冷启动 —— 一遍不让 ART 做任何 AOT
 * （[CompilationMode.None]，模拟"全新安装、profile 还没生效"），一遍强制按 profile 编译
 * （[BaselineProfileMode.Require]，模拟"profile 已生效"）。两个数一减就是这套东西的收益。
 *
 * 为什么非要跑这个而不是直接信"Baseline Profile 有用"：这个项目在性能问题上已经连着猜错
 * 三次（排版链两次、cutout 一次），每次都是"听起来很有道理"然后白改一版。加了探针才发现
 * 真正的锅在别处。所以这次先出数再说 —— 只花几分钟，换的是"知道"而不是"觉得"。
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * ```
 * 模拟器上的绝对值不能当真机用（x86 + 虚拟化，比真机快得多也稳得多），但**两者之差**的方向
 * 和量级是可信的 —— 这里只关心差。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** 基线：没有任何 AOT，代码全靠解释执行 + 边跑边 JIT */
    @Test
    fun startupNoCompilation() = measure(CompilationMode.None())

    /** 有 Baseline Profile：热方法在装包时就编译好了 */
    @Test
    fun startupBaselineProfile() =
        measure(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun measure(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        // 冷启动波动大，少了看不出中位数；模拟器上一轮几秒，10 轮不贵
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
        // 等到书架顶栏真的出来再收工。只等 startActivityAndWait 的话，测到的是"窗口出现"，
        // 而用户眼里的"启动完"是内容出现 —— 这中间隔着整个 Compose 首次组合。
        device.wait(Until.hasObject(By.desc("更多")), 10_000L)
    }

    private companion object {
        const val PACKAGE = "com.radium.inkwell"
    }
}
