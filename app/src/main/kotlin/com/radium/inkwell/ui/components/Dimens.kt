package com.radium.inkwell.ui.components

import androidx.compose.ui.unit.dp

/**
 * 间距 token。列表行的内边距从前散落成 h16/v8、h20/v14、h24/v12、h24/v10 四五种规格，
 * 页面之间对不齐。新写的行一律用这里的值。
 */
object Dimens {
    /** 屏幕级左右留白（设置页、面板） */
    val screenPadding = 20.dp
    /** 列表行左右内边距 */
    val rowHorizontal = 20.dp
    /** 列表行上下内边距 */
    val rowVertical = 14.dp

    /** 间距 */
    val gapS = 8.dp
    val gapM = 12.dp
    val gapL = 16.dp

    /** 触控目标下限（Material：48dp） */
    val touchTarget = 48.dp

    /** 底部面板里可滚动列表的高度上限。从前四处各写各的（400/420/440），面板高度参差不齐 */
    val sheetListMaxHeight = 420.dp
}
