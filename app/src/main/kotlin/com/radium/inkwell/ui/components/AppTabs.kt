package com.radium.inkwell.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.radium.inkwell.ui.theme.AppTheme

/**
 * 分段页签的内容区：按切换方向轻轻横移 + 淡入淡出。
 *
 * 两条硬约束，都是踩过的：
 * - **横移幅度只取宽度的 1/8**。整页滑过来在面板里太抢戏，像又开了一层导航。
 * - **关掉 SizeTransform**（`using(null)`）。各页高度不同，不关的话切过去会把容器「顶高」——
 *   拉高该由用户自己拖，不该是切 Tab 的副作用。所以调用方要给内容区**定高**或定上限，
 *   别让高度跟着当前页内容变。
 *
 * 动效全部读 [AppTheme.motionScheme]：位移用 spatial、淡入淡出用 effects，与顶栏/底栏/导航转场同一套节奏。
 */
@Composable
fun <T> AppTabContent(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "AppTabContent",
    content: @Composable (T) -> Unit,
) {
    val motion = AppTheme.motionScheme
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        label = label,
        transitionSpec = {
            val forward = indexOf(targetState) >= indexOf(initialState)
            val enter = fadeIn(motion.defaultEffectsSpec()) + slideInHorizontally(
                animationSpec = motion.defaultSpatialSpec(),
                initialOffsetX = { if (forward) it / 8 else -it / 8 },
            )
            val exit = fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally(
                animationSpec = motion.fastSpatialSpec(),
                targetOffsetX = { if (forward) -it / 8 else it / 8 },
            )
            (enter togetherWith exit).using(null)
        },
    ) { current -> Column(Modifier.fillMaxWidth()) { content(current) } }
}

/**
 * 只为判断「往左还是往右」。Tab 状态通常就是个 Int；不是 Int 时统一当作前进 ——
 * 方向猜错的代价只是横移方向反了，不值得为此逼调用方额外传一个比较器。
 */
private fun indexOf(state: Any?): Int = (state as? Int) ?: 0
