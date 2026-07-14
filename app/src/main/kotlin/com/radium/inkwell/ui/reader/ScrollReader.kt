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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.render.ScrollChapter
import com.radium.inkwell.reader.render.ScrollItemView
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 滚动阅读：正文连续排成一列，不切页。
 *
 * 列表项是**排版元素**（段落/标题/图片），不是页 —— 页与页堆叠起来，每屏底部都会留一道
 * 参差的空隙（分页器按整行断页，剩多少空白取决于这一屏排了几行）。按元素铺就没有这个问题，
 * 而且天然是懒加载的：滚到哪测量到哪，不会为了看一章把整章的 TextLayoutResult 全画出来。
 */
@Composable
fun ScrollReader(
    chapters: List<ScrollChapter>,
    layout: LayoutSpec,
    theme: ReaderTheme,
    onVisible: (chapterIndex: Int, elementIndex: Int) -> Unit,
    onCenterTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val marginTop = with(density) { layout.marginTopPx.toDp() }
    val marginBottom = with(density) { layout.marginBottomPx.toDp() }

    // 首个可见元素 = 当前读到哪。distinctUntilChanged 免得每一帧都往库里写进度
    LaunchedEffect(listState, chapters) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { flat ->
                locate(chapters, flat)?.let { (chapterIndex, elementIndex) ->
                    onVisible(chapterIndex, elementIndex)
                }
            }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(theme.background))
            // 滚动模式没有"点两侧翻页"，但点中间呼出菜单得留着
            .pointerInput(Unit) { detectTapGestures { onCenterTap() } }
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Spacer(Modifier.height(marginTop)) }
            chapters.forEach { chapter ->
                items(
                    count = chapter.items.size,
                    key = { i -> "${chapter.chapterIndex}:$i" },
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
            item { Spacer(Modifier.height(marginBottom + 48.dp)) }
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
            val item = chapter.items[cursor]
            val elementIndex = when (item) {
                is com.radium.inkwell.reader.paginate.PageItem.TextSlice -> item.elementIndex
                is com.radium.inkwell.reader.paginate.PageItem.ImageBox -> item.elementIndex
            }
            return chapter.chapterIndex to elementIndex
        }
        cursor -= chapter.items.size
    }
    return null
}
