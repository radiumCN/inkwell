package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * 会滚动的底部弹层（目录、阅读设置、换源）垫在**滚动内容末尾**的导航栏高度：与页面同一个道理，
 * 让列表滚到手势条下面，最后一行又仍能完整滚出来。
 *
 * Miuix 的面板一直铺到屏幕底、不替内容让导航栏；[AppBottomSheet] 默认在内容末尾垫一份，
 * 会滚动的面板关掉那份（`padNavigationBar = false`），改把这个值放进列表的 `contentPadding` 末尾 ——
 * 垫在列表外面的话，列表在手势条上方就截断了，下面空一条面板底色。
 * 键盘由 Miuix 面板自己 `imePadding()` 让开，这里不用管。
 */
@Composable
fun sheetBottomInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
