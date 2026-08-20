package com.radium.inkwell.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.render.ScrollChapter
import com.radium.inkwell.reader.render.ScrollItemView
import com.radium.inkwell.ui.components.Dimens
import kotlinx.coroutines.flow.distinctUntilChanged

/** 滚动列表要跳到的章内位置。[charOffset] 为 [Int.MAX_VALUE] 表示该章末尾 */
data class ScrollJump(val chapterIndex: Int, val charOffset: Int)

/**
 * 当前视口里「正在读」的位置，以及屏顶/屏底落到哪一章。
 *
 * 进度必须跟 [chapterIndex] 走；邻章预排得看 [lastVisibleChapter] —— 屏顶还停在上一章
 * 时，正文大半已经是下一章了，只报屏顶会既对不上标题、也滑到窗口末尾就没了。
 */
data class ScrollVisibleReport(
    val chapterIndex: Int,
    val elementIndex: Int,
    val firstVisibleChapter: Int,
    val lastVisibleChapter: Int,
)

/** LazyColumn 可见项的几何；抽出来好单测锚点，不绑 Compose 运行时 */
internal data class VisibleSlot(val index: Int, val offset: Int, val size: Int)

/**
 * 滚动阅读：正文连续排成一列，不切页。
 *
 * 列表项是**排版元素**（段落/标题/图片），不是页 —— 页与页堆叠起来，每屏底部都会留一道
 * 参差的空隙（分页器按整行断页，剩多少空白取决于这一屏排了几行）。按元素铺就没有这个问题，
 * 而且天然是懒加载的：滚到哪测量到哪，不会为了看一章把整章的 TextLayoutResult 全画出来。
 *
 * 目录 / 上一章 / 下一章必须走 [jump]：列表自己不会因为 ViewModel 改了章号就滚过去，
 * 不跳的话标题变了、眼前还是刚才那一章。
 */
@Composable
fun ScrollReader(
    chapters: List<ScrollChapter>,
    layout: LayoutSpec,
    theme: ReaderTheme,
    jump: ScrollJump?,
    onJumpConsumed: () -> Unit,
    onVisible: (ScrollVisibleReport) -> Unit,
    onCenterTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val marginTop = with(density) { layout.marginTopPx.toDp() }
    val marginBottom = with(density) { layout.marginBottomPx.toDp() }

    val ignoreVisible = remember { booleanArrayOf(false) }
    val prevChapters = remember { mutableListOf<ScrollChapter>() }
    val chaptersLatest = rememberUpdatedState(chapters)
    val jumpLatest = rememberUpdatedState(jump)
    val onVisibleLatest = rememberUpdatedState(onVisible)
    val onJumpConsumedLatest = rememberUpdatedState(onJumpConsumed)
    if (jump != null) ignoreVisible[0] = true

    // 落位和报进度拆开：报进度若跟 chapters 绑在一起，窗口一更新就会用还没补正的下标
    // 去报「上一章」，ViewModel 再预排、再插、再报 —— 整本书往前一路滚。
    LaunchedEffect(jump, chapters) {
        if (chapters.isEmpty()) return@LaunchedEffect
        ignoreVisible[0] = true
        var placed = false
        try {
            val target = jump
            if (target != null) {
                val chapter = chapters.find { it.chapterIndex == target.chapterIndex }
                    ?: return@LaunchedEffect
                val element = elementIndexForOffset(chapter, target.charOffset)
                val flat = flatIndexOf(chapters, target.chapterIndex, element) ?: return@LaunchedEffect
                listState.scrollToItem(flat)
                prevChapters.clear()
                prevChapters.addAll(chapters)
                onJumpConsumedLatest.value()
                placed = true
            } else {
                val extra = leadingItemDelta(prevChapters, chapters)
                prevChapters.clear()
                prevChapters.addAll(chapters)
                if (extra != 0) {
                    val idx = (listState.firstVisibleItemIndex + extra).coerceAtLeast(0)
                    listState.scrollToItem(idx, listState.firstVisibleItemScrollOffset)
                }
                placed = true
            }
            withFrameNanos { }
        } finally {
            if (placed) ignoreVisible[0] = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.map { VisibleSlot(it.index, it.offset, it.size) }
            visibleReport(
                chapters = chaptersLatest.value,
                visible = visible,
                viewportStart = info.viewportStartOffset,
                viewportEnd = info.viewportEndOffset,
            )
        }
            .distinctUntilChanged()
            .collect { report ->
                if (report == null || ignoreVisible[0] || jumpLatest.value != null) return@collect
                onVisibleLatest.value(report)
            }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(theme.background)),
    ) {
        // 点空白呼菜单必须挂在 LazyColumn 自己身上：挂到外层 Box 会被 detectTapGestures
        // 把 down 吃掉，列表就滑不动 —— 滚动模式看起来像整页卡死。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onCenterTap() } },
        ) {
            item { Spacer(Modifier.height(marginTop)) }
            chapters.forEach { chapter ->
                items(
                    count = chapter.items.size,
                    key = { i -> "${chapter.chapterIndex}:$i" },
                    contentType = { i -> chapter.items[i]::class },
                ) { i ->
                    ScrollItemView(
                        chapter = chapter,
                        index = i,
                        layout = layout,
                        theme = theme,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { Spacer(Modifier.height(marginBottom + Dimens.touchTarget)) }
        }
    }
}

/**
 * 把列表里的扁平下标换算回 (章, 元素)。
 * 头尾各有一个留白项，所以要减 1 —— 这一位没减对，进度就会整体错一格。
 */
internal fun locate(chapters: List<ScrollChapter>, flatIndex: Int): Pair<Int, Int>? {
    var cursor = flatIndex - 1 // 顶部留白
    if (cursor < 0) return chapters.firstOrNull()?.let { it.chapterIndex to 0 }
    for (chapter in chapters) {
        if (cursor < chapter.items.size) {
            return chapter.chapterIndex to itemElementIndex(chapter.items[cursor])
        }
        cursor -= chapter.items.size
    }
    return null
}

/** 反向：章 + 元素 → 列表扁平下标（含顶部留白）。目标章还不在窗口里则 null */
internal fun flatIndexOf(
    chapters: List<ScrollChapter>,
    chapterIndex: Int,
    elementIndex: Int,
): Int? {
    var flat = 1
    for (chapter in chapters) {
        if (chapter.chapterIndex == chapterIndex) {
            val i = chapter.items.indexOfFirst { itemElementIndex(it) == elementIndex }
            return if (i >= 0) flat + i else flat
        }
        flat += chapter.items.size
    }
    return null
}

/** 字符偏移落到哪一段。[Int.MAX_VALUE] 表示章末 */
internal fun elementIndexForOffset(chapter: ScrollChapter, charOffset: Int): Int {
    if (chapter.items.isEmpty()) return 0
    if (charOffset == Int.MAX_VALUE) return itemElementIndex(chapter.items.last())
    val offsets = chapter.charOffsets
    if (offsets.isEmpty()) return itemElementIndex(chapter.items.first())
    return offsets.entries
        .filter { it.value <= charOffset }
        .maxByOrNull { it.value }
        ?.key
        ?: itemElementIndex(chapter.items.first())
}

internal fun itemElementIndex(item: PageItem): Int = when (item) {
    is PageItem.TextSlice -> item.elementIndex
    is PageItem.ImageBox -> item.elementIndex
}

/**
 * 阅读锚点：视口上方 [fraction] 处那一项。
 * 用 firstVisible 会把「上一章还剩几行顶在屏顶」当成当前章。
 */
internal fun pickAnchorIndex(
    visible: List<VisibleSlot>,
    viewportStart: Int,
    viewportEnd: Int,
    fraction: Float = 0.3f,
): Int? {
    if (visible.isEmpty()) return null
    val span = (viewportEnd - viewportStart).coerceAtLeast(0)
    val anchorY = viewportStart + span * fraction
    return visible.firstOrNull { it.offset + it.size > anchorY }?.index
        ?: visible.last().index
}

/** 把可见几何换成进度章 + 窗口两端章。屏底留白算窗口最后一章，好触发预排下一章 */
internal fun visibleReport(
    chapters: List<ScrollChapter>,
    visible: List<VisibleSlot>,
    viewportStart: Int,
    viewportEnd: Int,
): ScrollVisibleReport? {
    if (chapters.isEmpty()) return null
    val anchor = pickAnchorIndex(visible, viewportStart, viewportEnd) ?: return null
    val reading = locate(chapters, anchor) ?: run {
        val last = chapters.last()
        val lastItem = last.items.lastOrNull() ?: return null
        last.chapterIndex to itemElementIndex(lastItem)
    }
    val firstChapter = visible.firstOrNull()?.let { locate(chapters, it.index)?.first }
        ?: chapters.first().chapterIndex
    val lastChapter = visible.lastOrNull()?.let { locate(chapters, it.index)?.first }
        ?: chapters.last().chapterIndex
    return ScrollVisibleReport(
        chapterIndex = reading.first,
        elementIndex = reading.second,
        firstVisibleChapter = firstChapter,
        lastVisibleChapter = lastChapter,
    )
}

/**
 * 窗口只留 center±1。屏底已经压在窗口最后一章、或屏顶压在窗口第一章时，
 * 必须提前把邻章排进来 —— 进度章号可能还停在上一章，不能只看锚点。
 */
internal fun scrollPrefetchCenter(
    firstVisibleChapter: Int,
    lastVisibleChapter: Int,
    windowFirst: Int?,
    windowLast: Int?,
    chapterCount: Int,
    nextCached: Boolean,
    prevCached: Boolean,
): Int? {
    if (windowLast != null &&
        lastVisibleChapter >= windowLast &&
        lastVisibleChapter + 1 < chapterCount &&
        !nextCached
    ) {
        return lastVisibleChapter
    }
    if (windowFirst != null &&
        firstVisibleChapter <= windowFirst &&
        firstVisibleChapter > 0 &&
        !prevCached
    ) {
        return firstVisibleChapter
    }
    return null
}

/**
 * 窗口头部增减了多少个列表项（不含顶部留白）。
 * 正数 = 前面插入了上一章，下标要加上去才还停在原来那一段；
 * 负数 = 前面的章被裁掉，下标要减。
 */
internal fun leadingItemDelta(old: List<ScrollChapter>, new: List<ScrollChapter>): Int {
    val oldFirst = old.firstOrNull()?.chapterIndex ?: return 0
    val newFirst = new.firstOrNull()?.chapterIndex ?: return 0
    if (newFirst < oldFirst) {
        return new.filter { it.chapterIndex < oldFirst }.sumOf { it.items.size }
    }
    if (newFirst > oldFirst) {
        return -old.filter { it.chapterIndex < newFirst }.sumOf { it.items.size }
    }
    return 0
}
