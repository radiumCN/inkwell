package com.radium.inkwell.ui.nav

import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.radium.inkwell.ui.components.Motion
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.squircle.squircleClip

/**
 * 阅读页开合（从书封放大进来、缩回书封退出）时把整页裁成圆角。
 *
 * 开合是 NavDisplay 对整页做的 scaleIn / scaleOut（见 InkwellNavDisplay 的 readerMeta），只缩放、不裁切：
 * 缩着的那一下整页是个直角矩形，压在屏幕是圆角、书封和卡片也是圆角的书架上。按返回键只有
 * [Motion.READER_EXIT_MS]，不太看得出；**侧滑返回**时页面跟着手指停在半路，四个直角就一直摆在那儿
 * （用户截图反馈过）。进书放大那 [Motion.READER_ENTER_MS] 同样是直角。
 *
 * 半径取**屏幕本身的圆角**（系统报的 [RoundedCorner]），不取固定令牌：满屏那一刻裁出来的角和屏幕的角重合，
 * 看不出变化；一开始缩，圆角跟着整页一起缩小 —— 和应用退回桌面时窗口的样子是一个意思。取固定值的话，
 * 屏幕圆角和它不一样大的机型，缩放刚起步那几帧角上会「突然换一种弯法」。屏幕没有圆角的机型退回
 * [AppShapes.radiusExtraLarge]（和底部面板一样），从 0 跟着转场渐变上去，免得第一帧凭空冒出圆角。
 *
 * 半径挂在 AnimatedContent 给这一页的 transition 上，时长、曲线和缩放那条一致（[Motion.readerEnterSpec] /
 * [Motion.readerExitSpec]）：侧滑时 NavDisplay seek 的是同一个 transition，圆角照样跟手；也不会让退场
 * 比缩放多拖一截（transition 要等挂在它上面的动画全跑完，退场页才移出组合）。
 *
 * **只在转场进行中裁**：squircle 遮罩每帧要多画一遍，平时阅读、翻页没必要付这一层；落定（Visible）就整个摘掉。
 */
@Composable
internal fun ReaderOpenCorners(content: @Composable () -> Unit) {
    val transition = LocalNavAnimatedContentScope.current.transition
    val animate = animationsEnabled()
    val screenCorner = rememberScreenCornerRadius()
    val radius by transition.animateDp(
        transitionSpec = {
            when {
                // 系统「移除动画」：开合本身是瞬间的（见 readerMeta），这里也别多挂一段
                !animate -> tween(0)
                targetState == EnterExitState.Visible -> Motion.readerEnterSpec()
                else -> Motion.readerExitSpec()
            }
        },
        label = "ReaderOpenCorners",
    ) { state ->
        when {
            screenCorner > 0.dp -> screenCorner
            state == EnterExitState.Visible -> 0.dp
            else -> AppShapes.radiusExtraLarge
        }
    }
    val moving = transition.currentState != transition.targetState
    Box(
        if (moving) Modifier.squircleClip(radius) else Modifier,
        propagateMinConstraints = true,
    ) {
        content()
    }
}

/**
 * 屏幕圆角的半径；没有圆角（或系统不报）是 0。
 *
 * 跟着窗口尺寸重取：折叠屏内外屏的圆角不一样大。和 ReaderScreen 取挖孔一样同步问 window 要
 * （`rootWindowInsets`）—— 从书架点进来时 insets 早就派发过，进书第一帧就有。
 */
@Composable
private fun rememberScreenCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    return remember(view, density, windowSize) {
        with(density) { screenCornerRadiusPx(view).toDp() }
    }
}

/**
 * 四个角里取最大的：分屏时窗口只有贴着屏幕角的那一两个角会报半径，其余是 null。
 * 阅读页始终全屏，绝大多数时候四个一样大。
 */
private fun screenCornerRadiusPx(view: View): Int {
    val insets = view.rootWindowInsets ?: return 0
    return SCREEN_CORNERS.maxOf { insets.getRoundedCorner(it)?.radius ?: 0 }
}

private val SCREEN_CORNERS = intArrayOf(
    RoundedCorner.POSITION_TOP_LEFT,
    RoundedCorner.POSITION_TOP_RIGHT,
    RoundedCorner.POSITION_BOTTOM_RIGHT,
    RoundedCorner.POSITION_BOTTOM_LEFT,
)
