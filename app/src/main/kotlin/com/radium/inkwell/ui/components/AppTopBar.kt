package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「有标题的内容页」统一顶栏：Miuix 大标题 [TopAppBar]，随内容滚动折叠（HyperOS 设置页那种）。
 *
 * 停在顶部时标题另起一行、32sp 大字（页面身份看得清），一往下滚就收成 52dp 的窄栏、标题缩回栏中（内容优先）。
 * **折叠是它成立的前提** —— 只换组件不接滚动，等于给每页白送一条永久变高的顶栏，比原来更差。
 * 所以 [scrollBehavior] 不是可选装饰：用 [rememberAppTopBarScroll] 建一个，再用 [topBarScroll] 挂到
 * `Scaffold` 上，两步缺一不可。
 *
 * **颜色跟内容区走**：默认 `surface`（与 Miuix `Scaffold` 默认画布、也就是页面底一致）。
 * 页面底若另取了色（阅读相关的纸色页），[containerColor] 传同一个，否则顶栏与内容之间一道缝。
 * 在 [AppScaffold] 里时顶栏是**毛玻璃**：栏本身透明，对滚到下面的内容取样模糊、再叠一层八成的 [containerColor]。
 *
 * [bottomContent] 放「贴在标题下面、不随内容滚走」的东西（分类 chip 条、页签）：放进顶栏，
 * 列表就能从它下面滚过去、一起被毛玻璃盖住；放在内容区顶上的话，那一截永远挡着列表。
 *
 * **不是所有顶栏都该换成它**，以下几类用 [AppToolbar]（窄栏）：
 * - 标题位放着交互控件的（搜索页的输入框、发现页的书源切换按钮）—— 大标题那一行放不下控件
 * - 多选态的上下文操作栏（书架、书源管理）—— 它是临时替上来的，跟着滚动折叠会让人以为退出了多选
 * - 书架首页 —— 顶栏下面还压着隐藏区与下拉刷新，再叠一层折叠手势容易互相抢
 * - 关于页 —— 顶上的流动头部（图标 + 应用名）本身就是标题，栏停在顶部时透明、头部滚上去才叠色出标题
 */
@Composable
fun AppTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /** 与本页 Scaffold / 内容区同色；默认页面底 surface */
    containerColor: Color = MiuixTheme.colorScheme.surface,
    bottomContent: @Composable () -> Unit = {},
    // actions 必须留在最后：调用方习惯写 `AppTopBar(...) { 操作按钮 }`，尾随 lambda 要落在这里，
    // 落进 bottomContent 照样编得过，按钮却跑到标题下面去了
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        largeTitle = title,
        modifier = modifier.barBlur(containerColor),
        color = if (barBlurActive()) Color.Transparent else containerColor,
        navigationIcon = { if (onBack != null) BackButton(onClick = onBack) },
        actions = actions,
        scrollBehavior = scrollBehavior,
        bottomContent = bottomContent,
    )
}

/**
 * 配 [AppTopBar] 用的折叠行为（Miuix 的 [MiuixScrollBehavior]：先收完大标题再让内容滚，回到顶部才重新展开）。
 */
@Composable
fun rememberAppTopBarScroll(): ScrollBehavior = MiuixScrollBehavior()

/**
 * 把滚动接到顶栏上（挂在 `Scaffold` 的 modifier 上）。
 *
 * 单独包一层是为了让「建行为」和「接行为」看起来像一对 —— 只做前者不做后者时顶栏不会折叠，
 * 而这种漏接在编译期毫无迹象。
 */
fun Modifier.topBarScroll(scrollBehavior: ScrollBehavior): Modifier =
    nestedScroll(scrollBehavior.nestedScrollConnection)

/**
 * 窄顶栏：一行 [Dimens.toolbarHeight]（= Miuix 大标题栏收起后的高度），标题位是**可组合内容**。
 *
 * Miuix 的 `TopAppBar` / `SmallTopAppBar` 标题只收字符串；搜索框、书源切换按钮、「已选 3 项」这类
 * 标题位放控件的页面用这个（为什么不用大标题栏见 [AppTopBar]）。字号字重照 Miuix 窄栏：title3、Medium。
 *
 * 自己让开状态栏与刘海（顶部 + 两侧），调用方不用再垫；放进 Scaffold 的 topBar 即可。
 * 在 [AppScaffold] 里时和 [AppTopBar] 一样是毛玻璃；[bottomContent] 同 [AppTopBar] 的那个槽。
 *
 * @param navigationIcon 一般是 [BackButton]；书架首页这种根页面不传
 * @param title 标题位。传纯文字时用 [ToolbarTitle]，字号与 Miuix 窄栏一致
 */
@Composable
fun AppToolbar(
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    containerColor: Color = MiuixTheme.colorScheme.surface,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    title: @Composable () -> Unit,
) {
    val blurred = barBlurActive()
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier
                .fillMaxWidth()
                .barBlur(containerColor)
                .then(if (blurred) Modifier else Modifier.background(containerColor)),
        ) {
            ToolbarRow(navigationIcon, actions, title)
            bottomContent()
        }
    }
}

@Composable
private fun ToolbarRow(
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    title: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.statusBars.only(WindowInsetsSides.Top)
                    .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            )
            .height(Dimens.toolbarHeight)
            // 左右各 gapXS：48dp 的图标按钮自带一圈可点区，再贴 16dp 就比 Miuix 窄栏的图标往里缩了一截
            .padding(horizontal = Dimens.gapXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null) {
            navigationIcon()
        }
        Box(
            Modifier
                .weight(1f)
                .padding(start = if (navigationIcon != null) Dimens.gapXS else Dimens.gapM, end = Dimens.gapXS),
            contentAlignment = Alignment.CenterStart,
        ) {
            title()
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** [AppToolbar] 的纯文字标题：Miuix 窄栏的字号字重（title3 / Medium），一行、超长省略 */
@Composable
fun ToolbarTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = MiuixTheme.textStyles.title3.fontSize,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
