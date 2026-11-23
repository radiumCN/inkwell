package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Discrete diagnostic events only. Never called from drawing or per-frame motion callbacks. */
data class ReaderTrace(
    val action: Action,
    val direction: FlipDirection? = null,
    val flipId: Int? = null,
    val render: Render? = null,
    val reason: String? = null,
    val settleMs: Long? = null,
    val swipe: Swipe? = null,
    val curlGrip: CurlGrip? = null,
) {
    data class Swipe(
        val start: IntOffset,
        val end: IntOffset,
        val viewport: IntSize,
        val durationMs: Long,
        val velocityX: Int,
    )

    enum class Action {
        TAP, SWIPE_RELEASE, SWIPE_CANCELLED, ANIMATION_START, COMMIT,
        ROLLBACK, BLOCKED, INTERRUPTED, ACCESSIBILITY, SCROLL_BOUNDARY, RENDER_SELECTED,
    }

    enum class Render { CURL_BITMAPS, CURL_LIVE_UNDER, CURL_LIVE_FRONT, CURL_PENDING_PAGE, SLIDE }
}
