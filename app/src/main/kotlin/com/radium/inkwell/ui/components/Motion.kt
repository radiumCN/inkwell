package com.radium.inkwell.ui.components

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
}

/**
 * 系统开了「移除动画」就别动。
 *
 * 翻页容器早就尊重这个设置了，别处却没有 —— 一个 App 里一半动画能关一半关不掉，
 * 对需要它的人来说等于没关。
 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
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
