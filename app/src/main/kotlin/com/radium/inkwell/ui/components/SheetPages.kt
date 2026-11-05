package com.radium.inkwell.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底部面板里的一级页 / 二级页（阅读设置的「排版 ›」「翻页 ›」「更多 ›」）。几何和整页转场一样：
 * 进二级页时它整页从右边推进来、一级页往左让 1/4；返回镜像；侧滑返回时页面跟着手指走。
 *
 * **为什么不用 [AppTabContent]**：那是同级页签之间的切换（只挪 1/8 宽、淡入淡出）。进二级页是「走进去」，
 * 用页签那套看着就是「闪一下换了页」。何况从前二级页的标题行是面板自己的（Miuix 的 title / startAction），
 * 不在过渡里：第一帧就整个冒出来、把内容往下顶一行，内容却还在慢慢淡入 —— 两样不同步，被指出「动画割裂」。
 * 所以二级页的标题行（[SheetPageHeader]）是页面自己的第一行，跟着整页一起滑；面板自己不再有标题行。
 *
 * - **不淡入淡出、不回弹**：spring 同整页转场（[Motion.PAGE_ENTER_STIFFNESS] / [Motion.PAGE_EXIT_STIFFNESS]）；
 *   侧滑跟手、以及松手后补完或弹回的那一段用线性（同 [Motion.PREDICTIVE_BACK_MS]），理由见 [Motion]。
 * - **两个位移自己驱动**（两个 [Animatable]），不走 AnimatedContent：被打断时（刚推进来又返回、侧滑到一半松手）
 *   Animatable 带着当前速度掉头；AnimatedContent 换目标时内置 slide 会换成它自己的默认 spring（见 ui/nav/PageSlide.kt）。
 * - **二级页盖在一级页上面**，进出都是它在动。完全盖住以后一级页移出组合（和 AnimatedContent 一样）：
 *   留在组合里的话，二级页上没有控件的空白处，点击会穿下去落到看不见的一级页上。
 * - **各页同高**：容器由调用方定高，页面铺满（二级页 = 标题行 + 内容），进出面板不跳。
 * - **页面底**：动起来的时候二级页铺一层 [background]（面板底色），否则推进来的那页是透明的，两页的字叠在一起；
 *   停稳后不铺 —— 阅读设置拖滑轨时面板底会淡掉，页面自己再铺一层就淡不干净。
 *
 * 返回：二级页上的返回键 / 侧滑先回 [root]（内容里注册的处理者比面板自己的晚，先拿到返回事件）；一级页上不拦，返回照旧收起面板。
 * 侧滑跟手要靠系统把手势进度送进面板窗口（Android 14 起）；拿不到进度时松手照样返回，只是走点 ‹ 时那段 spring。
 *
 * @param onBack 回到 [root]：调用方把 [page] 换成 [root]。返回键、侧滑和标题行的 ‹ 都走它
 */
@Composable
fun <T> SheetPages(
    page: T,
    root: T,
    onBack: () -> Unit,
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val animate = animationsEnabled()
    val width = LocalWindowInfo.current.containerSize.width.toFloat().coerceAtLeast(1f)
    // 两页的位移，单位是容器宽度。二级页：1 = 在右边外面，0 = 在位；一级页：0 = 在位，ROOT_SHIFT = 让到左边
    val subX = remember { Animatable(if (page == root) 1f else 0f) }
    val rootX = remember { Animatable(if (page == root) 0f else ROOT_SHIFT) }
    // 刚退回一级页时，二级页还要滑出去：记住它，滑完才清掉
    var leaving by remember { mutableStateOf(page.takeIf { it != root }) }
    val shownSub = if (page != root) page else leaving
    // 这一程是不是手在拖：是的话松手后补完 / 弹回也用线性，和拖的那段接得上。只在协程里读写，不触发重组
    val gesture = remember { GestureFlag() }
    val scope = rememberCoroutineScope()
    val currentPage by rememberUpdatedState(page)

    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = page != root,
        // 没滑过半就松手：从松手的位置线性退回二级页
        onBackCancelled = {
            scope.launch {
                settle(subX, rootX, toSub = true, linear = true, animate = animate, width = width)
                gesture.active = false
            }
        },
        onBackCompleted = onBack,
    )
    // 侧滑进度直接定位两页（不在组合里读，每一帧的进度不触发重组）
    LaunchedEffect(backState) {
        snapshotFlow { (backState.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress }
            .collect { progress ->
                if (progress != null && currentPage != root) {
                    gesture.active = true
                    subX.snapTo(progress)
                    rootX.snapTo(ROOT_SHIFT * (1f - progress))
                }
            }
    }
    LaunchedEffect(page) {
        if (page == root) {
            settle(subX, rootX, toSub = false, linear = gesture.active, animate = animate, width = width)
            leaving = null
        } else {
            leaving = page
            settle(subX, rootX, toSub = true, linear = false, animate = animate, width = width)
        }
        gesture.active = false
    }

    // 二级页完全盖住时一级页移出组合；只在「盖没盖住」翻转时重组，不是每一帧
    val covered by remember { derivedStateOf { subX.value <= 0f } }
    val rootShown = shownSub == null || !covered
    Box(modifier.clipToBounds()) {
        if (rootShown) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = rootX.value * size.width },
            ) { content(root) }
        }
        if (shownSub != null) {
            key(shownSub) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = subX.value * size.width }
                        .then(if (rootShown) Modifier.background(background) else Modifier),
                ) { content(shownSub) }
            }
        }
    }
}

/**
 * 二级页的标题行：左边 ‹，中间页名。字号字重照 Miuix 面板自带的标题行（title4、Medium）——
 * 长得和从前一样，只是现在它属于页面、跟着页面滑（见 [SheetPages]）。左右不留边：与面板标题行的 ‹ 同一个位置。
 */
@Composable
fun SheetPageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Dimens.touchTarget),
    ) {
        BackButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
        Text(
            title,
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.Center)
                .semantics { heading() },
        )
    }
}

/** 一级页让到左边的位置：容器宽度的 1/4，同整页转场 */
private const val ROOT_SHIFT = -0.25f

private class GestureFlag {
    var active = false
}

/**
 * 两页一起走到「二级页在位」（[toSub]）或「一级页在位」。两段同时跑：
 * - 系统「移除动画」：直接到位；
 * - [linear]（侧滑之后的那一段）：线性，时长按剩下的路程折算，两段同时到；
 * - 否则不回弹的 spring：进场的那页用 [Motion.PAGE_ENTER_STIFFNESS]、退场的用更快的 [Motion.PAGE_EXIT_STIFFNESS]。
 *   阈值按半个像素折算（值的单位是容器宽度，Float 默认阈值 0.01 相当于十来个像素，停得太早、最后一下跳到位）。
 */
private suspend fun settle(
    subX: Animatable<Float, *>,
    rootX: Animatable<Float, *>,
    toSub: Boolean,
    linear: Boolean,
    animate: Boolean,
    width: Float,
) = coroutineScope {
    val subTarget = if (toSub) 0f else 1f
    val rootTarget = if (toSub) ROOT_SHIFT else 0f
    val remaining = abs(subX.value - subTarget)
    fun spec(entering: Boolean): AnimationSpec<Float> = when {
        !animate -> snap()
        linear -> tween((Motion.PREDICTIVE_BACK_MS * remaining).roundToInt(), easing = LinearEasing)
        else -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = if (entering) Motion.PAGE_ENTER_STIFFNESS else Motion.PAGE_EXIT_STIFFNESS,
            visibilityThreshold = 0.5f / width,
        )
    }
    launch { subX.animateTo(subTarget, spec(entering = toSub)) }
    launch { rootX.animateTo(rootTarget, spec(entering = !toSub)) }
}
