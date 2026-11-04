package com.radium.inkwell.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.ui.theme.AppMotionScheme
import com.radium.inkwell.ui.theme.AppTheme

/**
 * 动效层。我们自己写的组件动画与浮层帮手都以 [AppTheme.motionScheme] 为唯一来源；
 * **整页转场**（前进 / 返回 / 侧滑）与**阅读器开合**是刻意的例外，走下方 [Motion] 里命名的时长与曲线。
 * Miuix 组件内部的动画（开关拇指、弹窗滑入、按压缩放）是它自己的 spring，不读这里。
 *
 * `InkwellTheme` 默认提供 [AppMotionScheme.Default]，系统开「移除动画」时整套换成
 * [AppMotionScheme.Instant]（六个 spec 全 `tween(0)`）。顶栏/底栏/蒙层/展开读令牌，
 * 不再逐个判 [animationsEnabled]；不读令牌的整页转场与阅读器开合自己判。
 *
 * spatial / effects 的分工（沿用 M3 的约定），别混：
 * - **spatial**（spring）管**位置与尺寸**：滑入、展开、缩放
 * - **effects**（不回弹）管**纯视觉属性**：alpha、颜色
 *
 * 用 effects 做位移会发木；用 spatial 做淡入会让透明度过冲，看着像闪了一下。
 * 「退场比入场快」：帮手里退场取 `fast*`、入场取 `default*`。
 *
 * 不读令牌的两处，理由各不相同：
 * - **阅读器开合**：时长与进书 splash 窗口咬合，spring 给不出确定时长（[Motion.READER_ENTER_MS]）；
 * - **整页转场**：用不回弹的 spring 而非令牌（[Motion.PAGE_ENTER_STIFFNESS]），侧滑另需线性 tween
 *   才跟手（[Motion.PREDICTIVE_BACK_MS]）；位移由 ui/nav/PageSlide.kt 自己做，不用内置 slide。
 */
object Motion {

    /**
     * 「移除动画」时用的 0 时长 spec。阅读器开合不读主题令牌时靠它兜底，
     * 与 [AppMotionScheme.Instant] 行为一致。
     */
    fun <T> instantSpec(): FiniteAnimationSpec<T> = tween(0)

    /** 系统关动画时的页面转场（瞬间淡变，避免残留位移）。 */
    fun instantPageTransform(): ContentTransform =
        ContentTransform(
            fadeIn(instantSpec()),
            fadeOut(instantSpec()),
        )

    /**
     * 进阅读器专用：从被点那本书的位置放大展开（NavDisplay 里 scaleIn，原点定在书上）。
     *
     * 这条**刻意不走** [AppMotionScheme]：时长要和 [READER_SPLASH_DELAY_MS] 对齐（封面恰好在展开
     * 收尾时才可能出现，不跟展开抢戏），而 spring 给不出确定时长。曲线用 M3「强调减速」：起步更快、
     * 收尾更缓，落定那一下像被接住。200ms 比常规页转场更短 —— 进书首帧排版本就抢手
     * （见 InkwellNavDisplay 的转场注释 / ReaderViewModel.PREFETCH_LEAD_IN_MS），转场只做点到为止的
     * 空间暗示。返回走配对的 [readerExitSpec]。
     */
    const val READER_ENTER_MS = 200
    /** M3「强调减速」：起步快、收尾缓，落定像被接住。阅读器入场用 */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val ReaderEnterEasing: Easing = EmphasizedDecelerate

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

    /**
     * 整页前进 / 返回转场：**不回弹**的 spring（dampingRatio = 1），不走 [AppTheme.motionScheme]。
     *
     * 刚度沿用位移令牌（默认 700 / 快速 1400），只去掉回弹。带回弹的 spring（M3 Expressive 时代实测），
     * 放在小组件上是手感，放在整页上就成了「顿一下」：逐帧实测，进设置时新页冲过终点 18px
     * 再用约 230ms 爬回 0，被盖的书架目标 -305 却冲到 -334 再抖回来。
     *
     * 也试过定时长 tween（beta.8）：正常进退干净了，但**快速进退**仍卡 —— 转场被打断时
     * Compose 的 Transition 会把非 spring 的 spec 换成它自己的默认 spring，掉头那页起步慢、
     * 尾巴一像素一像素地爬。spring 是被打断时唯一会被保留的 spec，掉头带着当前速度转向，
     * 和正常进退同一条曲线。实现与细节见 ui/nav/PageSlide.kt。
     *
     * 入场 700 约 350ms 停稳；被盖页取 1400（「退场比入场快」），它只挪 1/4 屏，早一点停稳不显眼。
     */
    const val PAGE_ENTER_STIFFNESS = 700f
    const val PAGE_EXIT_STIFFNESS = 1400f

    /**
     * 侧滑返回（预测性返回）转场的总时长。与 [PAGE_ENTER_STIFFNESS] 同样不走令牌，但另需**线性**，原因与 spring 的形状有关：
     *
     * 侧滑时 NavDisplay 不「播放」转场，而是按手势进度 **seek** 到转场的某个时刻；松手后再用
     * `tween((1 - 进度) × 总时长)` 把剩下的拖完。spring 的位移曲线前段极陡 —— 实测进度 0.37 时页面
     * 已冲过屏宽（1286 / 1220）再回弹，手指才划了三分之一画面就到头了，之后怎么拖都不动，像卡住。
     * 只有线性 tween 能让「进度 = 位移」，页面跟着手指走。
     *
     * 300ms 只决定松手后剩余部分走多快（剩一半就是 150ms），与返回箭头那条 spring 收尾观感相近。
     */
    const val PREDICTIVE_BACK_MS = 300
}

/**
 * 系统 [Settings.Global.ANIMATOR_DURATION_SCALE]（开发者选项「动画程序时长缩放」）。
 *
 * - `0`：「移除动画」—— 主题换 [AppMotionScheme.Instant]；不读令牌的页面进退由 [animationsEnabled] 自己归零
 * - `0.5` / `1` / `2`…：阅读器与页面进退的动画由框架 `MotionDurationScale` 自动乘倍率
 *
 * 用 ContentObserver 实时听，别 `remember {}` 读一次 —— 用户改完设置应立刻生效。
 * 同一组合里只挂一处观察者；[animationsEnabled] 复用本函数，避免双重监听。
 */
@Composable
fun rememberAnimatorDurationScale(): Float {
    val context = LocalContext.current
    val resolver = context.contentResolver

    fun read(): Float =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    var scale by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = read()
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

/**
 * 系统开了「移除动画」就别动（[rememberAnimatorDurationScale] != 0）。
 *
 * 大部分地方**不需要**直接问它 —— 主题已经把 [AppTheme.motionScheme] 换成 0 时长的一套，
 * 走令牌的动画自动静止。留着它是给两类逃在主题之外的场合：翻页容器那套自绘动画（reader 模块，
 * 拿不到 Compose 主题），以及「关了动画就干脆别做这件事」而不只是缩到 0 时长的判断
 * （如 LazyGrid 的 `animateItem` 直接传 null，省掉每帧的插值开销）。
 */
@Composable
fun animationsEnabled(): Boolean = rememberAnimatorDurationScale() != 0f

/** 顶栏：从上方滑入 + 淡入 */
@Composable
fun topBarEnter(): EnterTransition {
    val motion = AppTheme.motionScheme
    return slideInVertically(motion.defaultSpatialSpec()) { -it } + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun topBarExit(): ExitTransition {
    val motion = AppTheme.motionScheme
    return slideOutVertically(motion.fastSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec())
}

/** 底栏：从下方滑入 + 淡入 */
@Composable
fun bottomBarEnter(): EnterTransition {
    val motion = AppTheme.motionScheme
    return slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun bottomBarExit(): ExitTransition {
    val motion = AppTheme.motionScheme
    return slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec())
}

/** 蒙层等纯淡入淡出的东西：只动 alpha，走 effects（spatial 的回弹会让透明度过冲） */
@Composable
fun scrimEnter(): EnterTransition = fadeIn(AppTheme.motionScheme.defaultEffectsSpec())

@Composable
fun scrimExit(): ExitTransition = fadeOut(AppTheme.motionScheme.fastEffectsSpec())

/**
 * 会撑高/收起、把周围内容顶开的一块面板（书架隐藏区、搜索进度条之类）。
 * 高度展开 + 淡入，而不是硬生生冒出来把下面的内容顶一下。
 */
@Composable
fun expandEnter(): EnterTransition {
    val motion = AppTheme.motionScheme
    return expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec())
}

@Composable
fun expandExit(): ExitTransition {
    val motion = AppTheme.motionScheme
    return shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
}
