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
import com.radium.inkwell.reader.api.TapZones
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
    /** 点击是否按 [tapZones] 翻页。滚动默认关，避免误触丢掉页内偏移；关着时点哪儿都开菜单。 */
    clickToFlip: Boolean = false,
    tapZones: TapZones = TapZones.DEFAULT,
    onFlip: (FlipDirection) -> Unit,
    onCenterTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offset = remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pendingFlip by remember { mutableStateOf<FlipDirection?>(null) }
    // pendingFlip 是否已经交给 onFlip。滚动回调/点按当场就翻了，pendingFlip 只是让换页那帧接上 leftover；
    // 只有换页当帧算出的「连翻」才要下面的 LaunchedEffect 代发。不分开的话，重组先看到 pendingFlip、
    // 后看到新页（跨章要等分页，几乎必然）时 effect 会再翻一次 —— 而 ViewModel 读的是已经前进过的页，
    // 结果是跳过新章第一页，或同一章被重复加载。
    var pendingDispatched by remember { mutableStateOf(false) }
    val currentLatest = rememberUpdatedState(current)
    val prevLatest = rememberUpdatedState(prev)
    val nextLatest = rememberUpdatedState(next)
    val layoutLatest = rememberUpdatedState(layout)
    val hasPrevLatest = rememberUpdatedState(hasPrev)
    val hasNextLatest = rememberUpdatedState(hasNext)
    val onFlipLatest = rememberUpdatedState(onFlip)
    val clickToFlipLatest = rememberUpdatedState(clickToFlip)
    val tapZonesLatest = rememberUpdatedState(tapZones)
    // 与 onFlip 一样取最新：点按的 pointerInput 不以回调为 key，直接捕获会一直拿着第一次组合时的那个
    val onCenterTapLatest = rememberUpdatedState(onCenterTap)

    val leftover = remember { mutableFloatStateOf(0f) }
    var leftoverChain by remember { mutableIntStateOf(0) }
    val pageKey = current?.spec?.chapterIndex to current?.spec?.pageIndexInChapter
    var seenPageKey by remember { mutableStateOf(pageKey) }
    if (pageKey != seenPageKey) {
        seenPageKey = pageKey
        // 换页当帧就把 leftover 接上。先归零再等 LaunchedEffect 补，中间会闪一帧页顶。
        val extra = leftover.floatValue
        val incoming = pendingFlip
        leftover.floatValue = 0f
        pendingFlip = null
        if (incoming == null || extra == 0f) {
            offset.floatValue = 0f
        } else if (leftoverChain >= 8) {
            offset.floatValue = 0f
        } else {
            leftoverChain++
            val curH = scrollPageHeight(current).let { if (it <= 0f) 1f else it }
            val step = consumeScroll(
                pageOffset = 0f,
                dragDelta = extra,
                currentHeight = curH,
                prevHeight = scrollPageHeight(prev),
                nextHeight = scrollPageHeight(next),
                hasPrev = hasPrev,
                hasNext = hasNext,
                viewportHeight = layout.contentHeightPx,
            )
            offset.floatValue = step.drawOffset
            leftover.floatValue = step.leftover
            pendingFlip = step.flip
            pendingDispatched = false
        }
    }
    val nextReady = next != null
    val prevReady = prev != null
    LaunchedEffect(pageKey, nextReady, prevReady, pendingFlip) {
        val chained = pendingFlip ?: return@LaunchedEffect
        if (pendingDispatched) return@LaunchedEffect
        if (leftoverChain >= 8) {
            leftover.floatValue = 0f
            pendingFlip = null
            return@LaunchedEffect
        }
        leftoverChain++
        pendingFlip = null
        onFlipLatest.value(chained)
    }

    val scrollableState = rememberScrollableState { composeDelta ->
        // scrollable 默认 reverseDirection=false：正 delta = 手指下滑，与 pageOffset 同号。
        val drag = composeDelta
        val spec = layoutLatest.value
        val curH = scrollPageHeight(currentLatest.value).let {
            if (it <= 0f) 1f else it
        }
        val prevH = scrollPageHeight(prevLatest.value)
        val nextH = scrollPageHeight(nextLatest.value)
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
            pendingDispatched = true
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
                val curH = scrollPageHeight(cur).let {
                    if (it <= 0f) 1f else it
                }
                val y = offset.floatValue
                // 只在正文带里画。页眉/页脚是 PageInfoBar 的固定层，字滑进去会盖住章节名和时间。
                clipRect(
                    left = 0f,
                    top = contentTop,
                    right = size.width.toFloat(),
                    bottom = contentBottom,
                ) {
                    drawPageItems(
                        cur, layout, theme, originY = y + contentTop,
                        collapseMissingImages = true,
                    )
                    next?.let { nxt ->
                        drawPageItems(
                            nxt, layout, theme, originY = y + contentTop + curH,
                            collapseMissingImages = true,
                        )
                    }
                    prev?.let { prv ->
                        val prevH = scrollPageHeight(prv).let {
                            if (it <= 0f) 1f else it
                        }
                        drawPageItems(
                            prv, layout, theme, originY = y + contentTop - prevH,
                            collapseMissingImages = true,
                        )
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
            .pointerInput(gesturesEnabled, size, clickToFlip) {
                if (!gesturesEnabled) return@pointerInput
                detectTapGestures { pos ->
                    tapAction(
                        pos.x, pos.y, size.width.toFloat(), size.height.toFloat(),
                        tapZonesLatest.value, enabled = clickToFlipLatest.value,
                    ).dispatch(onMenu = { onCenterTapLatest.value() }) { dir ->
                        val can = when (dir) {
                            FlipDirection.BACKWARD -> hasPrevLatest.value
                            FlipDirection.FORWARD -> hasNextLatest.value
                        }
                        if (can) {
                            pendingFlip = dir
                            pendingDispatched = true
                            onFlipLatest.value(dir)
                        }
                    }
                }
            },
    )
}
