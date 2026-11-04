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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全应用统一的分段 Tab：Miuix [TabRowWithContour]（HyperOS 的分段页签 —— 一条灰槽，选中那一格是槽里浮起的一块卡片色）。
 *
 * 封成组件是为了**后来者也自动对齐** —— 直接手写的话，形态与切换动效又会各页一套。
 * 只有这一个 Tab 入口，配套的切换过渡见 [AppTabContent]。
 *
 * 不用 Miuix 的另一种 `TabRow`：它是一格一个描边框、各自分开，放进阅读设置面板时一排四个框像四个按钮，
 * 跟下面卡片里的分段按钮也不是一路长相。这里和 [SegmentedChoice] 同一种「槽 + 浮块」：
 * 槽高 [Dimens.touchTarget]、外圆角 [AppShapes.radiusMedium]，两者摞在一起时轮廓一模一样。
 * 区别只在浮块的颜色 —— Tab 是切页，浮块用卡片色；分段是选值，浮块填强调色。
 *
 * Tab 格按槽宽均分；格子多到每格不足 Miuix 的下限（62dp）时才改成横滚。
 * 调用方给左右边距，和下面的内容左右对齐。
 *
 * @param containerColor 槽的颜色。必须和浮块（`surfaceContainer`）差一档；默认 `surfaceContainerHighest`，
 *   页面底与卡片上都分得开
 */
@Composable
fun AppTabRow(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainerHighest,
) {
    TabRowWithContour(
        tabs = titles,
        selectedTabIndex = selectedIndex,
        onTabSelected = onSelect,
        modifier = modifier,
        colors = TabRowDefaults.tabRowColors(backgroundColor = containerColor),
        height = Dimens.touchTarget,
        // Miuix 把浮块内缩固定的 5dp、外槽圆角 = 浮块圆角 + 5dp；这里倒推，让外槽正好是 radiusMedium
        cornerRadius = AppShapes.radiusMedium - TAB_CONTOUR_PADDING,
    )
}

/** Miuix [TabRowWithContour] 里浮块与槽边的间距（它写死在组件里，不是参数） */
private val TAB_CONTOUR_PADDING = 5.dp

/**
 * [AppTabRow] 的内容区：按切换方向轻轻横移 + 淡入淡出。
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
