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
