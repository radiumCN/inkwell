package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp

/**
 * Scaffold 给的内边距去掉底部那一份。
 *
 * 应用是 edge-to-edge 的（MainActivity.enableEdgeToEdge），没有底栏的页面里，Scaffold 内边距的底部就是
 * 系统导航栏（手势条）的高度。从前各页一律 `Modifier.padding(padding)`：整块内容被抬到导航栏上面，
 * 列表在手势条上方就齐刷刷截断了，下面空出一条页面底色 —— 手势条压在一条色带上，而不是浮在内容上。
 *
 * 书架页一开始就是对的，现在全应用照它来：
 * - 外层容器只让开顶栏与两侧：`Modifier.padding(padding.exceptBottom())`；
 * - 导航栏的高度垫在**可滚动内容的末尾**：Lazy 列表放进 `contentPadding` 的 bottom，
 *   `verticalScroll` 的 Column 加在滚动之后的 `padding(bottom = …)` 里，取 `padding.calculateBottomPadding()`。
 * 这样内容能滚到手势条底下，最后一行又仍能完整滚出来、不被挡住。
 *
 * 带输入框的页面照旧 `consumeWindowInsets(padding)` 再 `imePadding()`：消费的是**完整**的 padding，
 * 键盘弹起时 imePadding 让出「键盘 − 导航栏」，加上内容末尾垫的导航栏高度，正好一个键盘高。
 *
 * 不滚动的居中态（加载、空态、错误态）照旧 `padding(padding)`：它们没有东西要滚到手势条下面。
 */
@Composable
@ReadOnlyComposable
fun PaddingValues.exceptBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}

/**
 * 会滚动的底部弹层（目录、阅读设置、选项面板、换源）垫在**滚动内容末尾**的导航栏高度：与页面同一个道理，
 * 让列表滚到手势条下面，最后一行又仍能完整滚出来。
 *
 * Miuix 的面板一直铺到屏幕底、不替内容让导航栏；[AppBottomSheet] 默认在内容末尾垫一份，
 * 会滚动的面板关掉那份（`padNavigationBar = false`），改把这个值放进列表的 `contentPadding` 末尾 ——
 * 垫在列表外面的话，列表在手势条上方就截断了，下面空一条面板底色。
 * 键盘由 Miuix 面板自己 `imePadding()` 让开，这里不用管。
 */
@Composable
fun sheetBottomInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
