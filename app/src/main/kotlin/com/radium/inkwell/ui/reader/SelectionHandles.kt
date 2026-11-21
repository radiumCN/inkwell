package com.radium.inkwell.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.radium.inkwell.reader.render.SelectionEnd
import com.radium.inkwell.reader.render.SelectionHandlePosition
import com.radium.inkwell.ui.components.Dimens

private val handleStem = Dimens.selectionHandleStem
private val handleRadius = Dimens.selectionHandleKnob / 2
private val handleTouchRadius = Dimens.touchTarget / 2

@Composable
internal fun SelectionHandles(
    handles: Map<SelectionEnd, SelectionHandlePosition>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (handles.isEmpty()) return
    Canvas(modifier) {
        handles.values.forEach { handle ->
            val center = handle.anchor + Offset(0f, handleStem.toPx())
            drawLine(color, handle.anchor, center, strokeWidth = Dimens.strokeThick.toPx())
            drawCircle(color, handleRadius.toPx(), center)
        }
    }
}

/** 父节点固定在整页坐标系，拖动时手柄换行也不会改变指针的坐标原点。 */
internal fun Modifier.dragSelectionHandles(
    handles: State<Map<SelectionEnd, SelectionHandlePosition>>,
    onMove: (SelectionEnd, Offset) -> Unit,
): Modifier = pointerInput(Unit) {
    val stem = handleStem.toPx()
    val touchRadius = handleTouchRadius.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.isConsumed) return@awaitEachGesture
        val hit = handles.value.entries.minByOrNull { (_, handle) ->
            (down.position - handle.anchor - Offset(0f, stem)).getDistance()
        } ?: return@awaitEachGesture
        val handle = hit.value
        if ((down.position - handle.anchor - Offset(0f, stem)).getDistance() > touchRadius) {
            return@awaitEachGesture
        }
        // 手柄上起手由这一层独占，翻页和点空白取消选区都交给其他触点。
        down.consume()
        val origin = Offset(handle.anchor.x, handle.lineCenterY)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed) break
            change.consume()
            if (!change.pressed) break
            if (change.position != change.previousPosition) {
                onMove(hit.key, origin + change.position - down.position)
            }
        }
    }
}
