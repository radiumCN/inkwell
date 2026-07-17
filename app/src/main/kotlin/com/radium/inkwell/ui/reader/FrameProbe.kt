package com.radium.inkwell.ui.reader

import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 记下「书架上点下去」的那一刻（**临时**，定位完删掉）。
 *
 * 探针本身挂在 ReaderScreen 上，起点只能是**阅读页首次组合之后** —— 而「点下去到画面
 * 开始动」那一段它一帧都看不到，偏偏那一段同样会被读成卡顿（按下去没反应，比滑动不顺
 * 更让人觉得卡）。所以点击时先在这里打个点，阅读页起来后回头算这段空白有多长。
 *
 * 用全局单例而不是路由参数：这是临时脚手架，不该污染导航契约。
 */
object OpenBookClock {
    @Volatile
    private var tappedAtNanos: Long = 0

    fun tapped() {
        tappedAtNanos = System.nanoTime()
    }

    /** 点击 → 现在，单位 ms；没打过点就返回 null（比如直接从通知进阅读页） */
    fun elapsedMs(): Long? =
        tappedAtNanos.takeIf { it != 0L }?.let { (System.nanoTime() - it) / 1_000_000 }

    fun reset() {
        tappedAtNanos = 0
    }
}

/**
 * 入场期间的掉帧探针（**临时**，定位完删掉）。
 *
 * 「卡顿」有两种，肉眼分不出但修法完全相反：
 * - **真掉帧**：主线程被占住，帧与帧之间超过一个刷新周期 → 得找出占住它的活。
 * - **视觉跳动**：帧没掉，但内容位置/大小突然变了 → 得让它别变。
 *
 * 这里只回答第一种：挂 Choreographer 逐帧记时间差，超过 [JANK_MS] 就算一帧迟到。
 * 只测进书后 [WINDOW_MS]（覆盖 260ms 的入场动画 + 一点余量），测完自己停，不留开销。
 *
 * **起点是阅读页首次组合之后**，所以「点击 → 首次组合」那段空白测不到 ——
 * 那段由 [OpenBookClock] 单独报。
 *
 * 注意它测的是**帧回调的间隔**，不是 SurfaceFlinger 的真实合成时间 —— 够用来判断
 * 「主线程有没有被占住」，但不等于系统级的 FrameMetrics。
 */
@Composable
fun rememberFrameProbe(): FrameProbeResult {
    var result by remember { mutableStateOf(FrameProbeResult()) }
    // 点击 → 阅读页首次组合，这段探针看不到，先把它量下来
    val tapToCompose = remember { OpenBookClock.elapsedMs() }
    DisposableEffect(Unit) {
        val startedAt = System.nanoTime()
        var lastFrame = 0L
        var frames = 0
        var late = 0
        var worstMs = 0.0
        var stopped = false

        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (stopped) return
                if (lastFrame != 0L) {
                    val deltaMs = (frameTimeNanos - lastFrame) / 1_000_000.0
                    frames++
                    if (deltaMs > JANK_MS) {
                        late++
                        if (deltaMs > worstMs) worstMs = deltaMs
                    }
                }
                lastFrame = frameTimeNanos
                if ((frameTimeNanos - startedAt) / 1_000_000 >= WINDOW_MS) {
                    stopped = true
                    result = FrameProbeResult(
                        done = true, frames = frames, late = late, worstMs = worstMs,
                        tapToComposeMs = tapToCompose,
                    )
                    return
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose {
            stopped = true
            choreographer.removeFrameCallback(callback)
        }
    }
    return result
}

data class FrameProbeResult(
    val done: Boolean = false,
    val frames: Int = 0,
    val late: Int = 0,
    val worstMs: Double = 0.0,
    /** 点击书籍 → 阅读页首次组合。null = 没经过书架点击（如从通知直接进） */
    val tapToComposeMs: Long? = null,
) {
    /** 给人看的结论，别让人自己解读数字 */
    fun summary(): String {
        if (!done) return "测量中…"
        val head = tapToComposeMs?.let { "点击→阅读页首帧 ${it}ms\n" } ?: ""
        val body = if (late == 0) {
            "滑动期间 $frames 帧全部准时 —— 主线程没被占住，卡顿感来自别处（很可能是内容跳动）"
        } else {
            "滑动期间 $frames 帧，其中 $late 帧迟到，最长一帧 ${"%.0f".format(worstMs)}ms —— 主线程被占住了"
        }
        return head + body
    }
}

/** 超过这个就算迟到。按 60Hz 的 16.7ms 留点余量；高刷屏上会偏保守（只会少报，不会多报） */
private const val JANK_MS = 20.0

/** 测量窗口：覆盖 Motion.NAV_ENTER_MS(260) 的入场动画，外加余量看它之后有没有拖尾 */
private const val WINDOW_MS = 400L
