package com.radium.inkwell.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 动效层。**全应用只有一个动效来源**：[MaterialTheme.motionScheme]。
 *
 * 从前这里是一套硬编码 tween（入场 220ms / 退场 140ms + 自定义贝塞尔），而 M3 组件内部读的是
 * 主题里的 `MotionScheme`。两套并存的结果是同一屏上节奏对不上 —— 底部面板用弹性 spring 滑入、
 * 它上面的顶栏却匀速 tween 划下来，说不清哪里怪但就是不像一套东西。现在统一读主题：
 * `InkwellTheme` 把 `motionScheme` 钉成 `MotionScheme.expressive()`，系统开「移除动画」时
 * 整套换成 `InstantMotionScheme`（六个 spec 全 `tween(0)`）。
 *
 * 于是**无障碍也只剩一个开关**：这些帮手里不再逐个判 [animationsEnabled] —— 动画该不该动，
 * 由主题那一处决定，组件内部动画和我们自己写的转场一起静止，不会再漏掉某一处。
 *
 * spatial / effects 的分工是 M3 的约定，别混：
 * - **spatial**（带回弹的 spring）管**位置与尺寸**：滑入、展开、缩放
 * - **effects**（不回弹）管**纯视觉属性**：alpha、颜色
 *
 * 用 effects 做位移会发木；用 spatial 做淡入会让透明度过冲，看着像闪了一下。
 * 「退场比入场快」这条仍然在，只是不再靠手写毫秒数，而是退场取 `fast*`、入场取 `default*`。
 *
 * 仍然硬编码 tween 的只有阅读器开合那两条（见 [Motion]）：它们的时长与进书 splash 的等待
 * 窗口是**咬合**的，换成 spring 就没有确定时长可对齐了。
 */
object Motion {

    /**
     * 进阅读器专用：从被点那本书的位置放大展开（NavDisplay 里 scaleIn，原点定在书上）。
     *
     * 这条**刻意不走** `MotionScheme`：时长要和 [READER_SPLASH_DELAY_MS] 对齐（封面恰好在展开
     * 收尾时才可能出现，不跟展开抢戏），而 spring 给不出确定时长。曲线用 M3「强调减速」：起步更快、
     * 收尾更缓，落定那一下像被接住。200ms 比常规页转场更短 —— 进书首帧排版本就抢手
     * （见 InkwellNavDisplay 的转场注释 / ReaderViewModel.PREFETCH_LEAD_IN_MS），转场只做点到为止的
     * 方向暗示。返回走配对的 [readerExitSpec]。
     */
    const val READER_ENTER_MS = 200
    val ReaderEnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> readerEnterSpec(): FiniteAnimationSpec<T> = tween(READER_ENTER_MS, easing = ReaderEnterEasing)

    /**
     * 阅读页退场。**必须比入场快**，曲线也要和入场配成一对。
     *
     * 从前这里直接借页面转场的 180ms，与 [READER_ENTER_MS]（200）之比高达 90% —— 既违反
     * 「退场比入场快、约取 65%」，也违反 M3 motion 的同一条；而且入场用 M3 强调减速、退场用
     * 通用加速，两条曲线根本不是一套。
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
 * 大部分地方**不需要**直接问它 —— 主题已经把 [MaterialTheme.motionScheme] 换成 0 时长的一套，
 * 走令牌的动画自动静止。留着它是给两类逃在主题之外的场合：翻页容器那套自绘动画（reader 模块，
 * 拿不到 Compose 主题），以及「关了动画就干脆别做这件事」而不只是缩到 0 时长的判断
 * （如 LazyGrid 的 `animateItem` 直接传 null，省掉每帧的插值开销）。
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
fun topBarEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return slideInVertically(motion.defaultSpatialSpec()) { -it } + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun topBarExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return slideOutVertically(motion.fastSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec())
}

/** 底栏：从下方滑入 + 淡入 */
@Composable
fun bottomBarEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun bottomBarExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec())
}

/** 蒙层等纯淡入淡出的东西：只动 alpha，走 effects（spatial 的回弹会让透明度过冲） */
@Composable
fun scrimEnter(): EnterTransition = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())

@Composable
fun scrimExit(): ExitTransition = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

/**
 * 会撑高/收起、把周围内容顶开的一块面板（书架隐藏区、搜索进度条之类）。
 * 高度展开 + 淡入，而不是硬生生冒出来把下面的内容顶一下。
 */
@Composable
fun expandEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun expandExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
}
