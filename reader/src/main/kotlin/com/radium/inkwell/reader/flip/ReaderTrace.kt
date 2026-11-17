package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection

/** Discrete diagnostic events only. Never called from drawing or per-frame motion callbacks. */
data class ReaderTrace(val action: Action, val direction: FlipDirection? = null) {
    enum class Action {
        TAP, SWIPE_RELEASE, SWIPE_CANCELLED, ANIMATION_START, COMMIT,
        ROLLBACK, BLOCKED, INTERRUPTED, ACCESSIBILITY, SCROLL_BOUNDARY,
    }
}
