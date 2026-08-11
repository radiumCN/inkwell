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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 动效层。组件内建与浮层帮手以 [MaterialTheme.motionScheme] 为唯一来源；
 * 页面级进退与阅读器开合是刻意例外（见下方）。
 *
 * `InkwellTheme` 把 `motionScheme` 钉成 `MotionScheme.expressive()`，系统开「移除动画」时
 * 整套换成 `InstantMotionScheme`（六个 spec 全 `tween(0)`）。顶栏/底栏/蒙层/展开帮手
 * 读令牌，不再逐个判 [animationsEnabled]。
 *
 * spatial / effects 的分工是 M3 的约定，别混：
 * - **spatial**（带回弹的 spring）管**位置与尺寸**：滑入、展开、缩放
 * - **effects**（不回弹）管**纯视觉属性**：alpha、颜色
 *
 * 用 effects 做位移会发木；用 spatial 做淡入会让透明度过冲，看着像闪了一下。
 * 「退场比入场快」：帮手里退场取 `fast*`、入场取 `default*`；页面级见 [pagePushTransform]。
 *
 * 两条不走 Expressive 默认档的例外：
 * 1. **页面进退**（[pagePushTransform] / [pagePopTransform]）：拟合 HyperOS 系统页切换 ——
 *    部分横滑 + 淡入淡出 + 轻微缩放，~320/280ms 的 cubic-bezier tween（alpha 另走更短的一档）。
 *    时长由框架 `MotionDurationScale` 乘 [ANIMATOR_DURATION_SCALE]；`scale==0` 时走
 *    [instantPageTransform]。
 * 2. **阅读器开合** tween：时长与进书 splash 窗口咬合，同样由框架乘倍率，勿再手乘。
 */
object Motion {

    /**
     * 「移除动画」时用的 0 时长 spec。页面转场不读主题令牌时靠它兜底，
     * 与 [InstantMotionScheme] 行为一致。
     */
    fun <T> instantSpec(): FiniteAnimationSpec<T> = tween(0)

    // ---- 页面进退（拟合 HyperOS：分层并行的部分横滑 + fade + 微缩放）----

    /**
     * 一次转场里**上下两层共用同一条时长**，push / pop 各一条。
     *
     * 分层并行（layered parallel）的要义是两层由同一条进度驱动，像一摞卡片被整体推动。
     * 从前是「入场页 350 / 退场页 300」—— 两层各走各的，被盖住那页提前 50ms 停住，而压在
     * 上面的新页还在滑；余光里是背景先定住、前景后到，那一下的错位就是廉价感的来源。
     *
     * 「退场比入场快」仍然守着，只是它管的是**两个动作之间**（返回整体比前进快 40ms），
     * 不是同一次转场里的两层之间。
     */
    const val PAGE_PUSH_MS = 320
    const val PAGE_POP_MS = 280

    /**
     * 位移与缩放的曲线。
     *
     * 控制点取 HyperOS 系统页切换的拟合值：第二个控制点 y 已经到 0.9，意味着**头两成时间就
     * 冲掉了九成行程**，剩下八成时间全用来极缓地收尾。「跟手」就是这么来的 —— 手指离开的
     * 瞬间页面基本已经到位，长尾只负责把最后一点距离稳稳放下。
     *
     * 比从前的 `(0.2, 0, 0, 1)` 更靠前发力：那条起步还有一段近似线性的爬升，落到眼里是
     * 「先动一下、再滑过去」的两段感。
     *
     * pop 那条收得略缓（0.8 而非 0.9）：返回是把页面抽走，不需要进场那种扑面而来的冲劲。
     */
    val PagePushEasing: Easing = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)
    val PagePopEasing: Easing = CubicBezierEasing(0.3f, 0.8f, 0.2f, 1f)

    /**
     * 透明度**单独走一条更短的时长**（约位移的六成），不跟位移共用。
     *
     * 位移 320ms 里若让 alpha 也走满，新页会在整段滑行途中半透明地压着旧页，两页的字叠在一起
     * 是脏的。让 alpha 早早落定：眼睛先确认「这是一个完整的新页面」，剩下的位移只交代方向。
     */
    const val PAGE_PUSH_FADE_MS = 200
    const val PAGE_POP_FADE_MS = 180

    /** 进出屏那一页的横向行程（占屏宽）—— 不是整屏硬推。 */
    const val PAGE_SLIDE_FRACTION = 0.38f

    /**
     * 被盖住那页的横向行程，约为 [PAGE_SLIDE_FRACTION] 的一半。
     *
     * 两层走**不等距**才有纵深：等距的话两页像焊在一起平移，看不出谁压着谁。
     */
    const val PAGE_UNDER_SLIDE_FRACTION = 0.19f

    /** 进出屏那页的缩放端点；被盖住那页收得再狠一点（[PAGE_UNDER_SCALE]）。 */
    const val PAGE_SCALE_START = 0.95f
    const val PAGE_UNDER_SCALE = 0.94f

    /**
     * 被盖住的页**不淡到全透明**，停在 0.6。
     *
     * 它此刻还在屏幕上（只让出不到两成宽），淡到 0 就成了凭空消失，与「被压到下一层去了」的
     * 深度暗示正好相反。留 0.6 是让它看着像退到后面一层，而不是被删掉。
     */
    const val PAGE_UNDER_ALPHA = 0.6f

    fun <T> pagePushSpec(): FiniteAnimationSpec<T> =
        tween(PAGE_PUSH_MS, easing = PagePushEasing)

    fun <T> pagePopSpec(): FiniteAnimationSpec<T> =
        tween(PAGE_POP_MS, easing = PagePopEasing)

    private fun <T> pagePushFadeSpec(): FiniteAnimationSpec<T> =
        tween(PAGE_PUSH_FADE_MS, easing = PagePushEasing)

    private fun <T> pagePopFadeSpec(): FiniteAnimationSpec<T> =
        tween(PAGE_POP_FADE_MS, easing = PagePopEasing)

    /** 系统关动画时的页面转场（瞬间淡变，避免残留位移/缩放）。 */
    fun instantPageTransform(): ContentTransform =
        ContentTransform(
            fadeIn(instantSpec()),
            fadeOut(instantSpec()),
        )

    /** push：新页从右切入并放大显现，旧页左让、略缩、淡到 [PAGE_UNDER_ALPHA]。 */
    fun pagePushTransform(): ContentTransform =
        pagePushEnter() togetherWith pagePushExit()

    /** pop：当前页右滑出并缩小，底层页从左回位、放大、由 [PAGE_UNDER_ALPHA] 淡回不透明。 */
    fun pagePopTransform(): ContentTransform =
        pagePopEnter() togetherWith pagePopExit()

    fun pagePushEnter(): EnterTransition =
        slideInHorizontally(pagePushSpec()) { (it * PAGE_SLIDE_FRACTION).toInt() } +
            fadeIn(pagePushFadeSpec()) +
            scaleIn(initialScale = PAGE_SCALE_START, animationSpec = pagePushSpec())

    fun pagePushExit(): ExitTransition =
        slideOutHorizontally(pagePushSpec()) {
            -(it * PAGE_UNDER_SLIDE_FRACTION).toInt()
        } +
            fadeOut(pagePushFadeSpec(), targetAlpha = PAGE_UNDER_ALPHA) +
            scaleOut(targetScale = PAGE_UNDER_SCALE, animationSpec = pagePushSpec())

    fun pagePopEnter(): EnterTransition =
        slideInHorizontally(pagePopSpec()) {
            -(it * PAGE_UNDER_SLIDE_FRACTION).toInt()
        } +
            fadeIn(pagePopFadeSpec(), initialAlpha = PAGE_UNDER_ALPHA) +
            scaleIn(initialScale = PAGE_UNDER_SCALE, animationSpec = pagePopSpec())

    fun pagePopExit(): ExitTransition =
        slideOutHorizontally(pagePopSpec()) { (it * PAGE_SLIDE_FRACTION).toInt() } +
            fadeOut(pagePopFadeSpec()) +
            scaleOut(targetScale = PAGE_SCALE_START, animationSpec = pagePopSpec())

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
 * 系统 [Settings.Global.ANIMATOR_DURATION_SCALE]（开发者选项「动画程序时长缩放」）。
 *
 * - `0`：「移除动画」—— 主题换 [InstantMotionScheme]，页面走 [Motion.instantPageTransform]
 * - `0.5` / `1` / `2`…：页面 / 阅读器 / 主题里的 tween 由框架 `MotionDurationScale` 自动乘倍率
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
 * 大部分地方**不需要**直接问它 —— 主题已经把 [MaterialTheme.motionScheme] 换成 0 时长的一套，
 * 走令牌的动画自动静止。留着它是给两类逃在主题之外的场合：翻页容器那套自绘动画（reader 模块，
 * 拿不到 Compose 主题），以及「关了动画就干脆别做这件事」而不只是缩到 0 时长的判断
 * （如 LazyGrid 的 `animateItem` 直接传 null，省掉每帧的插值开销）。
 */
@Composable
fun animationsEnabled(): Boolean = rememberAnimatorDurationScale() != 0f

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
