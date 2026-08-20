package com.radium.inkwell.reader.flip

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.render.RenderablePage
import com.radium.inkwell.reader.render.drawPageItems

/**
 * 滚动阅读：与仿真/覆盖同一套分页，用 [pageOffset] 在 prev/cur/next 三页上滑。
 * 越过当前页内容高就 [onFlip]，菜单标题跟 [showPage] 走，不会和正文错章。
 */
@Composable
fun ScrollPageReader(
    current: RenderablePage?,
    prev: RenderablePage?,
    next: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    hasPrev: Boolean,
    hasNext: Boolean,
    gesturesEnabled: Boolean,
    onFlip: (FlipDirection) -> Unit,
    onCenterTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offset = remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pendingFlip by remember { mutableStateOf<FlipDirection?>(null) }
    val currentLatest = rememberUpdatedState(current)
    val prevLatest = rememberUpdatedState(prev)
    val nextLatest = rememberUpdatedState(next)
    val layoutLatest = rememberUpdatedState(layout)
    val hasPrevLatest = rememberUpdatedState(hasPrev)
    val hasNextLatest = rememberUpdatedState(hasNext)
    val onFlipLatest = rememberUpdatedState(onFlip)

    val leftover = remember { mutableFloatStateOf(0f) }
    var leftoverChain by remember { mutableIntStateOf(0) }
    val pageKey = current?.spec?.chapterIndex to current?.spec?.pageIndexInChapter
    var seenPageKey by remember { mutableStateOf(pageKey) }
    if (pageKey != seenPageKey) {
        seenPageKey = pageKey
        // 先落到新页顶，避免沿用上一页坐标系把新页推到画面外。
        offset.floatValue = 0f
    }
    val nextReady = next != null
    val prevReady = prev != null
    LaunchedEffect(pageKey, nextReady, prevReady) {
        val extra = leftover.floatValue
        val chained = pendingFlip
        if (chained == null) {
            leftover.floatValue = 0f
            return@LaunchedEffect
        }
        leftover.floatValue = 0f
        pendingFlip = null
        if (extra == 0f) return@LaunchedEffect
        if (leftoverChain >= 8) {
            leftover.floatValue = 0f
            return@LaunchedEffect
        }
        leftoverChain++
        val spec = layoutLatest.value
        val curH = pageContentHeight(currentLatest.value).let {
            if (it <= 0f) spec.contentHeightPx else it
        }
        val prevH = pageContentHeight(prevLatest.value)
        val nextH = pageContentHeight(nextLatest.value)
        val step = consumeScroll(
            pageOffset = 0f,
            dragDelta = extra,
            currentHeight = curH,
            prevHeight = prevH,
            nextHeight = nextH,
            hasPrev = hasPrevLatest.value,
            hasNext = hasNextLatest.value,
            viewportHeight = spec.contentHeightPx,
        )
        offset.floatValue = step.drawOffset
        if (step.flip != null) {
            leftover.floatValue = step.leftover
            pendingFlip = step.flip
            onFlipLatest.value(step.flip)
        }
    }

    val scrollableState = rememberScrollableState { composeDelta ->
        // scrollable 默认 reverseDirection=false：正 delta = 手指下滑，与 pageOffset 同号。
        val drag = composeDelta
        val spec = layoutLatest.value
        val curH = pageContentHeight(currentLatest.value).let {
            if (it <= 0f) spec.contentHeightPx else it
        }
        val prevH = pageContentHeight(prevLatest.value)
        val nextH = pageContentHeight(nextLatest.value)
        leftoverChain = 0
        val before = offset.floatValue
        val step = consumeScroll(
            pageOffset = before,
            dragDelta = drag,
            currentHeight = curH,
            prevHeight = prevH,
            nextHeight = nextH,
            hasPrev = hasPrevLatest.value,
            hasNext = hasNextLatest.value,
            viewportHeight = spec.contentHeightPx,
        )
        offset.floatValue = step.drawOffset
        if (step.flip != null) {
            leftover.floatValue = step.leftover
            pendingFlip = step.flip
            onFlipLatest.value(step.flip)
            return@rememberScrollableState composeDelta
        }
        val applied = step.drawOffset - before
        if (drag == 0f) 0f else composeDelta * (applied / drag)
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .drawBehind {
                drawRect(Color(theme.background))
                val cur = current ?: return@drawBehind
                val contentTop = scrollContentTop(layout)
                val contentBottom = scrollContentBottom(layout)
                val curH = pageContentHeight(cur).let {
                    if (it <= 0f) layout.contentHeightPx else it
                }
                val y = offset.floatValue
                // 只在正文带里画。页眉/页脚是 PageInfoBar 的固定层，字滑进去会盖住章节名和时间。
                clipRect(
                    left = 0f,
                    top = contentTop,
                    right = size.width.toFloat(),
                    bottom = contentBottom,
                ) {
                    drawPageItems(cur, layout, theme, originY = y + contentTop)
                    next?.let { nxt ->
                        drawPageItems(nxt, layout, theme, originY = y + contentTop + curH)
                    }
                    prev?.let { prv ->
                        val prevH = pageContentHeight(prv).let {
                            if (it <= 0f) layout.contentHeightPx else it
                        }
                        drawPageItems(prv, layout, theme, originY = y + contentTop - prevH)
                    }
                }
            }
            .scrollable(
                state = scrollableState,
                orientation = Orientation.Vertical,
                enabled = gesturesEnabled,
                // 默认 false：正 delta = 手指下滑，和 pageOffset 同号。verticalScroll 会把这个翻掉，这里别学。
                reverseDirection = false,
            )
            .pointerInput(gesturesEnabled, size) {
                if (!gesturesEnabled) return@pointerInput
                detectTapGestures { pos ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    when {
                        pos.x < w / 3f -> {
                            if (hasPrevLatest.value) {
                                pendingFlip = FlipDirection.BACKWARD
                                onFlipLatest.value(FlipDirection.BACKWARD)
                            }
                        }
                        pos.x > w * 2f / 3f -> {
                            if (hasNextLatest.value) {
                                pendingFlip = FlipDirection.FORWARD
                                onFlipLatest.value(FlipDirection.FORWARD)
                            }
                        }
                        else -> onCenterTap()
                    }
                }
            },
    )
}
