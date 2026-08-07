package com.radium.inkwell.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全应用统一的分段 Tab。
 *
 * 用 [PrimaryTabRow] 而不是 `SecondaryTabRow`：Primary 的指示条是贴着文字宽度的短粗圆角块，
 * 这是 Expressive 下「选中态在动」的那一种；Secondary 那根通栏细线在浅纸背景上几乎看不出切换。
 * 指示条的位移由主题的 `MotionScheme` 驱动（系统关动画时随 `InstantMotionScheme` 一起静止）。
 *
 * 封成组件是为了**后来者也自动对齐** —— 直接手写 `TabRow` 的话，形态与切换动效又会各page一套。
 * 只有这一个 Tab 入口，配套的切换过渡见 [AppTabContent]。
 */
@Composable
fun AppTabRow(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(selectedTabIndex = selectedIndex, modifier = modifier) {
        titles.forEachIndexed { i, title ->
            Tab(
                selected = selectedIndex == i,
                onClick = { onSelect(i) },
                text = { Text(title) },
            )
        }
    }
}

/**
 * [AppTabRow] 的内容区：按切换方向轻轻横移 + 淡入淡出。
 *
 * 两条硬约束，都是踩过的：
 * - **横移幅度只取宽度的 1/8**。整页滑过来在面板里太抢戏，像又开了一层导航。
 * - **关掉 SizeTransform**（`using(null)`）。各页高度不同，不关的话切过去会把容器「顶高」——
 *   拉高该由用户自己拖，不该是切 Tab 的副作用。所以调用方要给内容区**定高**或定上限，
 *   别让高度跟着当前页内容变。
 *
 * 动效全部读主题令牌：位移用 spatial、淡入淡出用 effects，与顶栏/底栏/导航转场同一套节奏。
 */
@Composable
fun <T> AppTabContent(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "AppTabContent",
    content: @Composable (T) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
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
