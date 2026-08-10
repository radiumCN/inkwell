package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow

/**
 * 「有标题的内容页」统一顶栏：Expressive 的 [MediumFlexibleTopAppBar]，随内容滚动折叠。
 *
 * 相对经典 `TopAppBar` 的区别是两段式：停在顶部时标题另起一行、字号大一档（页面身份看得清），
 * 一往下滚就收成一条常规高度的窄栏（内容优先）。**折叠是它成立的前提** —— 只换组件不接滚动，
 * 等于给每页白送一条永久变高的顶栏，比原来更差。所以 [scrollBehavior] 不是可选装饰：
 * 用 [rememberAppTopBarScroll] 建一个，再用 [topBarScroll] 挂到 `Scaffold` 上，两步缺一不可。
 *
 * **不是所有顶栏都该换成它**，另外三类刻意留了经典窄栏：
 * - 标题位放着交互控件的（搜索页的输入框、发现页的书源切换按钮）—— 两段式会把控件摊到大标题
 *   的位置上，字号和控件自己的排版打架
 * - 多选态的上下文操作栏（书架、书源管理）—— M3 的 contextual action bar 本就是窄栏，
 *   而且它是临时替上来的，跟着滚动折叠会让人以为退出了多选
 * - 书架首页 —— 顶栏下面还压着隐藏区与下拉刷新，再叠一层折叠手势容易互相抢
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    MediumFlexibleTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = { if (onBack != null) BackButton(onClick = onBack) },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

/**
 * 配 [AppTopBar] 用的折叠行为。
 *
 * 取 `exitUntilCollapsed` 而不是 `enterAlways`：设置页那种一屏多行的表单，`enterAlways`
 * 会在往上轻扫时立刻把大标题弹回来，读到一半的内容被顶下去。`exitUntilCollapsed` 是
 * 「先收完再让内容滚」，回到顶部才重新展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberAppTopBarScroll(): TopAppBarScrollBehavior =
    TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

/**
 * 把滚动接到顶栏上（挂在 `Scaffold` 的 modifier 上）。
 *
 * 单独包一层是为了让「建行为」和「接行为」看起来像一对 —— 只做前者不做后者时顶栏不会折叠，
 * 而这种漏接在编译期毫无迹象。
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.topBarScroll(scrollBehavior: TopAppBarScrollBehavior): Modifier =
    nestedScroll(scrollBehavior.nestedScrollConnection)
