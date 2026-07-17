package com.radium.inkwell.ui.reader

import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs

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
 * 这里只回答第一种：挂 Choreographer 逐帧记时间差。只测进书后 [WINDOW_MS]（覆盖 260ms
 * 的入场动画 + 一点余量看有没有拖尾），测完自己停，不留开销。
 *
 * **起点是阅读页首次组合之后**，所以「点击 → 首次组合」那段空白测不到 ——
 * 那段由 [OpenBookClock] 单独报。
 *
 * ### 刷新率不写死
 *
 * 60/90/120/144Hz 各家都有，每帧预算从 16.7ms 到 6.9ms 不等，**阈值写死等于测了个寂寞**：
 * 按 60Hz 定的 20ms 阈值放到 120Hz 屏上，凡是掉 1 帧的（8.4~20ms）全被当成"准时"放过。
 *
 * 所以周期从**两个来源**取，互相验证：
 * - `Display.refreshRate` —— 系统报的当前模式。可变刷新率设备上它**会撒谎**（报 120，
 *   实际以 60 合成），省电模式下也会变。
 * - **实测最快的一帧** —— Choreographer 的回调对齐 vsync，帧间隔只可能是周期的整数倍，
 *   所以观测到的最小间隔就是一个周期。不依赖任何 API 的自觉。
 *
 * 判定用实测值（那是这条管线真实交付的节奏）；两者不一致时把系统报的一并打出来 ——
 * 那本身就是线索。
 *
 * 注意它测的是**帧回调的间隔**，不是 SurfaceFlinger 的真实合成时间 —— 够用来判断
 * 「主线程有没有被占住」，但不等于系统级的 FrameMetrics。
 */
@Composable
fun rememberFrameProbe(): FrameProbeResult {
    var result by remember { mutableStateOf(FrameProbeResult()) }
    // 点击 → 阅读页首次组合，这段探针看不到，先把它量下来
    val tapToCompose = remember { OpenBookClock.elapsedMs() }
    val view = LocalView.current
    DisposableEffect(Unit) {
        val declaredHz = view.display?.refreshRate ?: 0f
        val startedAt = System.nanoTime()
        var lastFrame = 0L
        // 逐帧原样收，判定留到最后 —— 判定要用 vsync 周期，而周期得看完整段数据才反推得出来。
        val offsets = IntArray(MAX_FRAMES)
        val deltas = FloatArray(MAX_FRAMES)
        var n = 0
        var stopped = false

        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (stopped) return
                if (lastFrame != 0L && n < MAX_FRAMES) {
                    offsets[n] = ((frameTimeNanos - startedAt) / 1_000_000).toInt()
                    deltas[n] = (frameTimeNanos - lastFrame) / 1_000_000f
                    n++
                }
                lastFrame = frameTimeNanos
                if ((frameTimeNanos - startedAt) / 1_000_000 >= WINDOW_MS || n >= MAX_FRAMES) {
                    stopped = true
                    result = summarize(offsets, deltas, n, declaredHz, tapToCompose)
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

/** 把逐帧原始数据收敛成结论。周期靠实测反推，见 [rememberFrameProbe] 的 KDoc。 */
private fun summarize(
    offsets: IntArray,
    deltas: FloatArray,
    n: Int,
    declaredHz: Float,
    tapToComposeMs: Long?,
): FrameProbeResult {
    // 至少要几帧才谈得上"最快的一帧"；太少说明样本里可能一帧准时的都没有，
    // 最小值会偏大 → 阈值跟着抬高 → 反而漏报。宁可说"测不出"也不给个假结论。
    if (n < MIN_FRAMES_FOR_VSYNC) {
        return FrameProbeResult(true, n, declaredHz, 0f, emptyList(), tapToComposeMs)
    }
    var vsync = Float.MAX_VALUE
    for (i in 0 until n) if (deltas[i] < vsync) vsync = deltas[i]

    val late = ArrayList<LateFrame>()
    for (i in 0 until n) if (deltas[i] > vsync * LATE_FACTOR) late += LateFrame(offsets[i], deltas[i])
    return FrameProbeResult(true, n, declaredHz, vsync, late, tapToComposeMs)
}

/** 一帧超时：距入场 [atMs] 毫秒处，这一帧花了 [tookMs] 毫秒 */
data class LateFrame(val atMs: Int, val tookMs: Float)

data class FrameProbeResult(
    val done: Boolean = false,
    val frames: Int = 0,
    /** 系统报的刷新率；0 = 没报。**不一定可信**，见 [rememberFrameProbe] 的 KDoc */
    val declaredHz: Float = 0f,
    /** 实测 vsync 周期（ms）；0 = 帧数太少，反推不出来 */
    val vsyncMs: Float = 0f,
    val lateFrames: List<LateFrame> = emptyList(),
    /** 点击书籍 → 阅读页首次组合。null = 没经过书架点击（如从通知直接进） */
    val tapToComposeMs: Long? = null,
) {
    /** 给人看的结论，别让人自己解读数字 */
    fun summary(): String {
        if (!done) return "测量中…"
        return buildString {
            tapToComposeMs?.let { append("点击→阅读页首帧 ${it}ms\n") }

            val declaredMs = if (declaredHz > 0f) 1000f / declaredHz else 0f
            append(
                when {
                    vsyncMs <= 0f -> "窗口内只有 $frames 帧，测不出刷新率"
                    declaredMs <= 0f -> "实测每帧 ${fmt(vsyncMs)}ms（系统没报刷新率）"
                    // 系统报的与实测对不上：多半是可变刷新率降了频。两个都打出来，按实测算。
                    abs(vsyncMs - declaredMs) > declaredMs * TOLERANCE ->
                        "屏幕报 ${fmt(declaredHz)}Hz(每帧 ${fmt(declaredMs)}ms)，" +
                            "实测每帧 ${fmt(vsyncMs)}ms —— 对不上，按实测算"
                    else -> "屏幕 ${fmt(declaredHz)}Hz · 每帧预算 ${fmt(vsyncMs)}ms"
                }
            )
            if (vsyncMs <= 0f) return@buildString
            append("\n入场 ${WINDOW_MS}ms / $frames 帧：")
            if (lateFrames.isEmpty()) {
                append("全部准时 —— 主线程没被占住，卡顿感来自别处（很可能是内容跳动）")
            } else {
                append("${lateFrames.size} 帧超时 —— ")
                append(lateFrames.take(MAX_SHOWN).joinToString(" ") { "${it.atMs}ms(${fmt(it.tookMs)})" })
                if (lateFrames.size > MAX_SHOWN) append(" …还有 ${lateFrames.size - MAX_SHOWN} 处")
            }
        }
    }

    private fun fmt(v: Float) = "%.1f".format(v)
}

/** 测量窗口：覆盖 Motion.NAV_ENTER_MS(260) 的入场动画，外加余量看它之后有没有拖尾 */
private const val WINDOW_MS = 400L

/** 400ms 在 144Hz 上约 58 帧；给到 256 是让将来更高刷的屏也装得下，反正只是两个小数组 */
private const val MAX_FRAMES = 256

/** 少于这么多帧就别反推周期了，理由见 [summarize] */
private const val MIN_FRAMES_FOR_VSYNC = 8

/** 超过一个周期的这么多倍算超时。1.5 = 确实掉了整整一帧，留半帧余量给计时噪声 */
private const val LATE_FACTOR = 1.5f

/** 系统报的刷新率与实测差出这个比例就认为对不上 */
private const val TOLERANCE = 0.25f

/** 超时帧最多列这么多个，够看出聚在哪就行，再多刷屏 */
private const val MAX_SHOWN = 8
