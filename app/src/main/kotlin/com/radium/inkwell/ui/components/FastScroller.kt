package com.radium.inkwell.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 长列表右边缘的快速滚动条（目录这种上千行的列表）：滚动时右边冒出一根滑块，按住上下拖整个列表一拖就到，
 * 旁边气泡写着拖到的那一行（[label]，章名）和 [detail]（全书位置）；不滚了一会儿自己隐去。
 *
 * 从前两千多章的书想跳到中间只能一路甩。Miuix 没有滚动条，这是自己画的：
 * - **只有行数够多才出现**（[minItems]）：几十行的列表甩两下就到，多一根滑块只是碍事。
 * - 滑块平时是一根细的说明色短条，按住变粗、换强调色；可按区域是 48dp 见方（触控下限），只在滑块露出来时才有 ——
 *   藏着的时候右边那一条照样点得到列表行。
 * - 拖动时滑块跟手，列表按比例 `scrollToItem`；松手后滑块回到由列表位置算出来的地方（两边同一个比例，不跳）。
 * - 每拖过全列表的 1% 轻震一下（HyperOS 拖进度条的手感）。
 * - **读屏不念**：它只是滑动列表的快捷方式，读屏照常滑列表。
 *
 * 放法：和 LazyColumn 放进同一个 Box、叠在它上面（`Modifier.matchParentSize()`），[contentPadding] 给列表的上下留白，
 * 滑块的行程就不会伸进留白里（面板里列表末尾垫着导航栏高度）。
 */
@Composable
fun FastScroller(
    state: LazyListState,
    itemCount: Int,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    detail: ((Int) -> String)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    minItems: Int = FAST_SCROLL_MIN_ITEMS,
) {
    if (itemCount < minItems) return
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var dragTarget by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val count by rememberUpdatedState(itemCount)

    // 滚着、拖着就露出来；停下来等一会儿再藏，给手指留出去按它的时间
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress || dragging }.collectLatest { active ->
            if (active) {
                shown = true
            } else {
                delay(FAST_SCROLL_HIDE_DELAY_MS)
                shown = false
            }
        }
    }
    val alpha by animateFloatAsState(
        if (shown) 1f else 0f,
        AppTheme.motionScheme.defaultEffectsSpec(),
        label = "fastScrollAlpha",
    )
    val thumbWidth by animateDpAsState(
        if (dragging) Dimens.gapS else Dimens.gapXS,
        AppTheme.motionScheme.defaultSpatialSpec(),
        label = "fastScrollThumb",
    )
    // 列表现在滚到了哪：第一行（带滚出去的那一截）÷ 能滚的行数。只在 offset 的 lambda 里读，滚动时不重组
    val listFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                0f
            } else {
                val first = state.firstVisibleItemIndex +
                    state.firstVisibleItemScrollOffset / visible.first().size.coerceAtLeast(1).toFloat()
                (first / scrollableRows(info.totalItemsCount, visible.size)).coerceIn(0f, 1f)
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(contentPadding)
            .clearAndSetSemantics { },
    ) {
        if (alpha == 0f) return@BoxWithConstraints
        val density = LocalDensity.current
        val hit = Dimens.touchTarget
        val travel = with(density) { (maxHeight - hit).toPx() }.coerceAtLeast(1f)
        fun thumbY(): Int = ((if (dragging) dragFraction else listFraction) * travel).roundToInt()

        // 可按的那一块：48dp 见方，滑块画在它右边
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY()) }
                .size(hit)
                .alpha(alpha)
                .pointerInput(state, travel) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFraction = listFraction
                            dragging = true
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        val before = (dragFraction * 100).toInt()
                        dragFraction = (dragFraction + dy / travel).coerceIn(0f, 1f)
                        if ((dragFraction * 100).toInt() != before) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        }
                        val visible = state.layoutInfo.visibleItemsInfo.size
                        val target = (dragFraction * scrollableRows(count, visible)).roundToInt()
                            .coerceIn(0, (count - 1).coerceAtLeast(0))
                        dragTarget = target
                        scope.launch { state.scrollToItem(target) }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = Dimens.gapS)
                    .size(width = thumbWidth, height = hit - Dimens.gapS)
                    .squircleSurface(
                        if (dragging) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        thumbWidth / 2,
                    ),
            )
        }

        // 气泡：拖着时贴在滑块左边，写着拖到的那一行
        if (dragging) {
            val scheme = MiuixTheme.colorScheme
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-hit.roundToPx(), thumbY()) }
                    .widthIn(max = maxWidth * FAST_SCROLL_BUBBLE_MAX_WIDTH)
                    .squircleSurface(scheme.onSurface, AppShapes.radiusMedium)
                    .padding(horizontal = Dimens.gapM, vertical = Dimens.gapS),
            ) {
                Text(
                    label(dragTarget),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = scheme.surface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        detail(dragTarget),
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.surface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 能滚的行数：总行数减去一屏放得下的。一屏就装得下时给 1，免得除零 */
private fun scrollableRows(total: Int, visible: Int): Float = (total - visible).coerceAtLeast(1).toFloat()

/** 少于这么多行不出滚动条 */
const val FAST_SCROLL_MIN_ITEMS = 100

/** 停止滚动后滚动条再露多久才藏起来 */
private const val FAST_SCROLL_HIDE_DELAY_MS = 1500L

/** 气泡最宽占列表宽度的比例：章名再长也给左边留出一截看得见的列表 */
private const val FAST_SCROLL_BUBBLE_MAX_WIDTH = 0.7f
