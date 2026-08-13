package com.radium.inkwell.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * HSV ↔ ARGB。阅读纸色滑条和主题设置共用 [android.graphics.Color] 这一套，
 * 别在两个页面各包一层 —— 一边改了打包方式，另一边的自定义色就会 silently 漂。
 */
fun argbToHsv(argb: Long): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), out)
    return out
}

fun hsvToArgb(h: Float, s: Float, v: Float): Long =
    android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL

fun Color.toHsv(): FloatArray = argbToHsv(toArgb().toLong() and 0xFFFFFFFFL)

fun hsvToColor(h: Float, s: Float, v: Float): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
