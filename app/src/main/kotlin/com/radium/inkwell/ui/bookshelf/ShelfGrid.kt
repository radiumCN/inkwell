package com.radium.inkwell.ui.bookshelf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.radium.inkwell.ui.components.Dimens

/**
 * 书架网格每行几本：设置 → 外观 → 书架 →「每行本数」，书架上双指捏合也能换（[shelfPinch]）。
 *
 * 存的是**手机竖屏上**每行的本数。平板、横屏不死守这个数（每行 4 本放到平板上，一本封面有半个手机大），
 * 按同一档的最小格宽多排几本，见 [ShelfGridCells]。
 */
internal object ShelfColumns {
    val OPTIONS = listOf(3, 4, 5)

    /** 加这项设置之前手机上就是每行 3 本：默认档不动谁的书架 */
    const val DEFAULT = 3

    /** 读到不认识的值（手改的 DataStore、以后删掉的档位）一律回默认 */
    fun sanitize(columns: Int?): Int = columns?.takeIf { it in OPTIONS } ?: DEFAULT

    /**
     * 每行这么多本时，格子里还印不印最新章。5 本一格只有 64dp 宽、放得下四个字，
     * 最新章印出来是「第 1203…」，没信息量；少这一行，一屏还能多一排书。列表模式、详情页照旧有
     */
    fun showsLatestChapter(columns: Int): Boolean = columns < 5
}

/** 这一档在宽屏上的最小格宽（为什么是这几个数见 [Dimens.bookshelfGridMin]） */
internal fun shelfMinCell(columns: Int): Dp = when (columns) {
    4 -> Dimens.bookshelfGridMin4
    5 -> Dimens.bookshelfGridMin5
    else -> Dimens.bookshelfGridMin
}

/**
 * 网格实际排几列：至少 [columns]，放得下更多（宽屏）就按 [minCell] 多排 —— 即 `GridCells.Adaptive(minCell)`
 * 再往上托一个底。手机竖屏上 Adaptive 那一项不会超过 [columns]（最小格宽就是这么挑的），结果正好是选的本数。
 * 三个长度同一个单位（调用方给 px）。
 */
internal fun shelfColumnCount(columns: Int, available: Float, spacing: Float, minCell: Float): Int =
    maxOf(columns, ((available + spacing) / (minCell + spacing)).toInt())

/**
 * 书架网格的列：手机上每行 [columns] 本，宽屏按这一档的最小格宽多排（[shelfColumnCount]）。
 * 余下的像素从左往右一格分一个，同 `GridCells.Fixed`。
 */
internal class ShelfGridCells(private val columns: Int) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = shelfColumnCount(columns, availableSize.toFloat(), spacing.toFloat(), shelfMinCell(columns).toPx())
        val withoutSpacing = availableSize - spacing * (count - 1)
        val slot = withoutSpacing / count
        val remaining = withoutSpacing % count
        return List(count) { slot + if (it < remaining) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is ShelfGridCells && other.columns == columns

    override fun hashCode(): Int = columns
}

/**
 * 捏合一下之后的本数：两指张开（放大）每行少一本、捏拢（缩小）多一本，到头不动。
 * 两指间距变到起手的 [PINCH_STEP] 倍（或 1/[PINCH_STEP]）才换一档 —— 手指搁上去的那点抖动不算。
 */
internal fun pinchColumns(current: Int, scale: Float): Int {
    val next = when {
        scale >= PINCH_STEP -> current - 1
        scale <= 1f / PINCH_STEP -> current + 1
        else -> current
    }
    return next.coerceIn(ShelfColumns.OPTIONS.first(), ShelfColumns.OPTIONS.last())
}

/** 换一档要两指间距变多少倍。相册「捏一下换一档」大约也是这个手感 */
internal const val PINCH_STEP = 1.25f

/**
 * 书架网格上双指捏合换每行本数（HyperOS 相册那样），和设置里的「每行本数」是同一个值。
 *
 * 照阅读页的双指调字号（QuickAdjust.kt）写：挂在网格上，在 Initial 阶段先于网格的滚动、书的点按 / 长按看到事件；
 * 第二根手指一落下就接手、把事件消费掉 —— 已经在滚的列表见到「已消费」就停，书的长按也不会被这一手触发。
 * 只有一根手指时一概不碰，滚动、点书、长按照旧。
 *
 * 一手里可以连换几档：每换一档以当时的两指间距为新起点，接着捏就再换一档。放开一根手指这一手就结束，
 * 剩下那根抬起之前什么也不做（别让它变成一次滚动或点按）。
 *
 * @param columns 当前本数（每次起手时读）
 * @param onColumns 换了一档：落盘、轻震一下
 */
internal fun Modifier.shelfPinch(
    columns: State<Int>,
    onColumns: (Int) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        var finished = false
        var startDistance = 1f
        var current = 0
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (!pinching) {
                if (pressed.size < 2) continue
                pinching = true
                startDistance = distance(pressed[0], pressed[1]).coerceAtLeast(1f)
                current = columns.value
            }
            event.changes.forEach { it.consume() }
            if (finished) continue
            if (pressed.size < 2) {
                finished = true
                continue
            }
            val d = distance(pressed[0], pressed[1])
            val next = pinchColumns(current, d / startDistance)
            if (next != current) {
                current = next
                startDistance = d.coerceAtLeast(1f)
                onColumns(next)
            }
        }
    }
}

private fun distance(a: PointerInputChange, b: PointerInputChange): Float = (a.position - b.position).getDistance()
