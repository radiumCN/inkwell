package com.radium.inkwell.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 动效 token。
 *
 * 从前全项目只有翻页容器里有动画，别处一律硬出硬消（菜单直接 `if (visible) { ... }`）。
 * 与其让每个人现场拍一个 300ms，不如把时长和曲线定死在一处 —— 动效不统一比没有动效更糟，
 * 用户说不上来哪里怪，只觉得"这 App 有点糙"。
 */
object Motion {
    /** 入场：150~300ms（Material 的微交互区间） */
    const val ENTER_MS = 220

    /**
     * 退场比入场快。退场慢会让人觉得"点了没反应" —— 用户已经决定离开了，
     * 界面还在慢悠悠地淡出。取入场的 ~65%。
     */
    const val EXIT_MS = 140

    /** 入场用减速（快进慢停），退场用加速（慢起快走） */
    val EnterEasing: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val ExitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    fun <T> enterSpec(): FiniteAnimationSpec<T> = tween(ENTER_MS, easing = EnterEasing)
    fun <T> exitSpec(): FiniteAnimationSpec<T> = tween(EXIT_MS, easing = ExitEasing)

    /** 页面转场。比浮层慢一点（跨的距离更远），但仍然遵守「退场更快」 */
    const val NAV_ENTER_MS = 260
    const val NAV_EXIT_MS = 180

    fun <T> navEnterSpec(): FiniteAnimationSpec<T> = tween(NAV_ENTER_MS, easing = EnterEasing)
    fun <T> navExitSpec(): FiniteAnimationSpec<T> = tween(NAV_EXIT_MS, easing = ExitEasing)

    /**
     * 进阅读器专用：从被点那本书的位置放大展开（NavHost 里 scaleIn，原点定在书上）。
     *
     * 曲线用 M3「强调减速」而非普通减速 [EnterEasing]：起步更快、收尾更缓，落定那一下像被接住，
     * 消掉线性 tween 那种匀速划过的机械感。时长压到比常规页转场 [NAV_ENTER_MS] 更短 —— 进书首帧
     * 排版本就抢手（见 NavHost 的转场注释 / ReaderViewModel.PREFETCH_LEAD_IN_MS），转场只做点到为止的
     * 方向暗示，别跟首帧抢时间。返回走配对的 [readerExitSpec]：镜像曲线、时长更短，理由见其注释。
     */
    const val READER_ENTER_MS = 200
    val ReaderEnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> readerEnterSpec(): FiniteAnimationSpec<T> = tween(READER_ENTER_MS, easing = ReaderEnterEasing)

    /**
     * 阅读页退场。**必须比入场快**，曲线也要和入场配成一对。
     *
     * 从前这里直接借 [navExitSpec]（180ms），与 [READER_ENTER_MS]（200）之比高达 90% ——
     * 既违反本文件开头就立下的「退场比入场快、约取 65%」，也违反 M3 motion 的同一条；
     * 而且入场用 M3 强调减速、退场用通用加速，两条曲线根本不是一套。
     *
     * 130ms ≈ 200 的 65%。曲线取 M3「强调加速」（emphasized accelerate）：慢起快走，
     * 与入场的强调减速（快进慢停）正好互为镜像 —— 进来时被稳稳接住，离开时干脆抽走。
     */
    const val READER_EXIT_MS = 130
    val ReaderExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> readerExitSpec(): FiniteAnimationSpec<T> = tween(READER_EXIT_MS, easing = ReaderExitEasing)

    /**
     * 进阅读页放大展开的起始缩放（返回时缩回同一值）。
     *
     * 取 0.85 而不是更小：这段动画里阅读页四周会露出书架，缩得越小露得越多、越像"从一个小方块弹出来"，
     * 而我们要的是"窗口从这本书那儿长出来"。0.85 够看出长大，又不至于让书架抢戏。
     */
    const val READER_OPEN_SCALE = 0.85f

    /**
     * 进书 splash（正文没就位时在纸面上显示书封）的**出场等待**。
     *
     * 这个延迟就是「方案 A」的全部要义：绝大多数进书是缓存热的，正文在这段窗口内就已就绪 ——
     * 那样 splash 永不出场，进书一毫秒都不会变慢。只有真的要等，才让封面出来交代「在开哪本书」。
     * 取值对齐 [READER_ENTER_MS]：封面恰好在展开动画收尾时才可能出现，不跟展开抢戏。
     *
     * 不要为了「多看见它」而调小 —— 那等于给每次进书加一道地板，正是方案 B 被否掉的原因。
     */
    const val READER_SPLASH_DELAY_MS = 200L

    /**
     * splash 一旦露面的**保底停留**。
     *
     * 没有它，正文在第 210ms 就绪时封面只闪 10ms，比不显示更难看。露了面就至少待满这么久再走。
     */
    const val READER_SPLASH_MIN_MS = 200L
}

/**
 * 系统开了「移除动画」就别动。
 *
 * 翻页容器早就尊重这个设置了，别处却没有 —— 一个 App 里一半动画能关一半关不掉，
 * 对需要它的人来说等于没关。
 *
 * 用 ContentObserver 监听而不是 `remember {}` 读一次：读一次的话，用户在系统设置里
 * 关掉动画再切回来，旧值还生效 —— 得杀进程才认。而「关掉动画」恰恰是那种关掉了
 * 就希望立刻生效的设置。
 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver

    fun read(): Boolean =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    var enabled by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = read()
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** 顶栏：从上方滑入 + 淡入 */
@Composable
fun topBarEnter() = if (animationsEnabled()) {
    slideInVertically(Motion.enterSpec()) { -it } + fadeIn(Motion.enterSpec())
} else {
    fadeIn(tween(0))
}

@Composable
fun topBarExit() = if (animationsEnabled()) {
    slideOutVertically(Motion.exitSpec()) { -it } + fadeOut(Motion.exitSpec())
} else {
    fadeOut(tween(0))
}

/** 底栏：从下方滑入 + 淡入 */
@Composable
fun bottomBarEnter() = if (animationsEnabled()) {
    slideInVertically(Motion.enterSpec()) { it } + fadeIn(Motion.enterSpec())
} else {
    fadeIn(tween(0))
}

@Composable
fun bottomBarExit() = if (animationsEnabled()) {
    slideOutVertically(Motion.exitSpec()) { it } + fadeOut(Motion.exitSpec())
} else {
    fadeOut(tween(0))
}

/** 蒙层等纯淡入淡出的东西 */
@Composable
fun scrimEnter() = if (animationsEnabled()) fadeIn(Motion.enterSpec()) else fadeIn(tween(0))

@Composable
fun scrimExit() = if (animationsEnabled()) fadeOut(Motion.exitSpec()) else fadeOut(tween(0))

/**
 * 会撑高/收起、把周围内容顶开的一块面板（书架隐藏区、搜索进度条之类）。
 * 高度展开 + 淡入，而不是硬生生冒出来把下面的内容顶一下。
 */
@Composable
fun expandEnter() = if (animationsEnabled()) {
    expandVertically(Motion.enterSpec()) + fadeIn(Motion.enterSpec())
} else {
    fadeIn(tween(0))
}

@Composable
fun expandExit() = if (animationsEnabled()) {
    shrinkVertically(Motion.exitSpec()) + fadeOut(Motion.exitSpec())
} else {
    fadeOut(tween(0))
}
