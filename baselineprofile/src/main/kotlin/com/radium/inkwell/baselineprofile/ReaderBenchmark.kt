package com.radium.inkwell.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阅读器帧耗时基线：使用 ProfileSeed 的本地 txt，排除网站波动与文件选择器。
 * 翻前一页再翻回来，避免多轮迭代把夹具翻到书尾而测到无动作的空样本。
 * 在真机上比较同一设备、同一刷新率的 frameDurationCpuMs / frameOverrunMs 分位数；
 * 这里只提供测量入口，不把模拟器数据当成真实阅读性能。
 *
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.radium.inkwell.baselineprofile.ReaderBenchmark
 */
@RunWith(AndroidJUnit4::class)
class ReaderBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun repeatedPageTurns() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            killProcess()
            pressHome()
            startActivityAndWait { it.putExtra("com.radium.inkwell.seed_profile_book", true) }
            val book = device.wait(Until.findObject(By.text("Benchmark")), 20_000L)
            checkNotNull(book) { "基准夹具未导入，不能用空书架代替阅读器测量" }.click()
            check(device.wait(Until.gone(By.desc("搜索")), 10_000L)) { "未进入阅读器" }
            val ready = (1..4).any {
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                device.wait(Until.hasObject(By.desc("退出阅读")), 5_000L)
            }
            check(ready) { "阅读器未就绪，不能测量空白页" }
            device.pressBack()
            check(device.wait(Until.gone(By.desc("退出阅读")), 5_000L)) { "阅读菜单未关闭" }
            device.waitForIdle()
        },
    ) {
        val y = device.displayHeight / 2
        val left = (device.displayWidth * 0.2f).toInt()
        val right = (device.displayWidth * 0.85f).toInt()
        repeat(4) {
            device.swipe(right, y, left, y, 25)
            device.waitForIdle()
            device.swipe(left, y, right, y, 25)
            device.waitForIdle()
        }
    }

    private companion object { const val PACKAGE = "com.radium.inkwell" }
}
