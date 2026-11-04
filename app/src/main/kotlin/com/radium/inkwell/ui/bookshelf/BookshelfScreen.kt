package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.groupPosition
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.ui.state.ToggleableState
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.settingGroupItemShape
import com.radium.inkwell.ui.components.AppSnackbarHost
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.settingsCardColor
import com.radium.inkwell.ui.components.settingsPageColor
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.util.BiometricAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.radium.inkwell.ui.components.Motion
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.expandEnter
import com.radium.inkwell.ui.components.expandExit
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
/**
 * 把封面在窗口里的位置换算成整屏的比例坐标，作为进书放大动画的原点 ——
 * 点哪本书，阅读页就从哪本书那儿长出来。位置未知（书还没测量 / 窗口尺寸为 0）就退回中心。
 */
/**
 * 封面在布局里的位置句柄；点开书时才换算成窗口坐标（进书从这本书所在的位置展开）。
 *
 * 从前用 onGloballyPositioned 每次都算 boundsInWindow() 再写进 State：书架一滑，每个可见封面每帧都算一遍坐标、
 * 写一次快照 —— 而这个值只在点击那一下才用得到。LayoutCoordinates 是活的，点击时算出来就是当下的位置；
 * 这里存的是普通引用不是 State，滑动时写它不触发任何重组。
 */
/** 「继续阅读」卡片在 Lazy 列表里的 key。书的 key 是 UUID，撞不上 */
private const val CONTINUE_READING_KEY = "continue-reading"

internal class CoverAnchor {
    var coordinates: LayoutCoordinates? = null

    fun boundsInWindow(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

private fun originOf(bounds: Rect?, window: IntSize): TransformOrigin {
    if (bounds == null || window.width <= 0 || window.height <= 0) return TransformOrigin.Center
    return TransformOrigin(
        (bounds.center.x / window.width).coerceIn(0f, 1f),
        (bounds.center.y / window.height).coerceIn(0f, 1f),
    )
}

@Composable
fun BookshelfScreen(
    onOpenBook: (String, TransformOrigin) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 欢迎页的「从 WebDAV 恢复」与「用户协议」 */
    onOpenWebDav: () -> Unit,
    onOpenDisclaimer: () -> Unit,
    /**
     * 隐藏区收起了（✕、长按收摊、切后台自动收起都算），带上此刻隐藏的书 id。
     * 宽屏右栏若正开着其中一本的详情，由导航层关掉 —— 书架这一栏管不到旁边那一栏。
     */
    onHiddenCollapsed: (hiddenIds: Set<String>) -> Unit,
    viewModel: BookshelfViewModel,
    /** 返回栈里还有没有阅读页。从这里开的书，读完退回来、退出动画播完，书架才放开冻住的顺序 */
    readerOpen: Boolean = false,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val continueReading by viewModel.continueReading.collectAsStateWithLifecycle()
    val onboarded by viewModel.onboarded.collectAsStateWithLifecycle()
    // 阅读页退干净了再放开顺序（见 BookshelfViewModel.frozenOrder）：退出动画是缩回这本书原来的位置，
    // 放早了它已经跑到第一格。返回栈在转场**开始**时就去掉了阅读页，这里得等过整段转场：
    // 普通返回是 READER_EXIT_MS 的 tween；侧滑返回跟手 seek，松手后剩下的那段按 PREDICTIVE_BACK_MS 的
    // 线性 tween 走完 —— 两段加起来，哪种退法都覆盖得到。晚一点放开只是书滑上去的时机晚一点，不会错。
    // 书架一直在组合里、或回来时才重新组合，都走这里
    LaunchedEffect(readerOpen) {
        if (!readerOpen) {
            delay((Motion.READER_EXIT_MS + Motion.PREDICTIVE_BACK_MS).toLong())
            viewModel.releaseShelfOrder()
        }
    }
    val shelfReady by viewModel.shelfReady.collectAsStateWithLifecycle()
    // 首批书架数据落地再报 fully drawn，启动基准才是「看见书」而不是首帧空白
    ReportDrawnWhen { shelfReady }
    // 换算进书展开原点用：把封面在窗口里的坐标除以窗口尺寸
    val windowSize = LocalWindowInfo.current.containerSize
    val allBooks by viewModel.allBooks.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val selectionMode = selected.isNotEmpty()
    // 多选态下按系统返回：先退出多选，而不是直接退出整个页面
    BackHandler(selectionMode) { viewModel.clearSelection() }
    var actionTarget by remember { mutableStateOf<BookEntity?>(null) }
    var groupTarget by remember { mutableStateOf<BookEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    var showGroupAssign by remember { mutableStateOf(false) }
    var groupInput by remember { mutableStateOf("") }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    val exploreEnabled by viewModel.exploreEnabled.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenCount.collectAsStateWithLifecycle()
    val requireAuth by viewModel.hiddenRequireAuth.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val refreshProgress by viewModel.refreshProgress.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? androidx.fragment.app.FragmentActivity
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 不能进页记一次就完：用户可能切去系统设置删掉（或重新设上）锁屏再回来。
    // 每回到前台重查一次，点开隐藏区时还会再现查一遍（见 revealHidden）
    var biometricAvailable by remember { mutableStateOf(BiometricAuth.isAvailable(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        biometricAvailable = BiometricAuth.isAvailable(context)
    }
    val haptic = LocalHapticFeedback.current

    // 滚动状态提到这里，展开隐藏区时要能把它拨回顶部（见下面的 LaunchedEffect）
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    /**
     * 展开隐藏区后，等「并进了隐藏书」的那份列表到了再回到顶部。
     *
     * 隐藏的书按「最近阅读」插回原位，常常就插在第一本前面。Lazy 列表按 key 锚定首个可见项：
     * 它让原来那本书原地不动，新冒出来的几本全落在视口上方 —— 看起来像什么都没发生，
     * 得手动往上滑才看得见。不能在展开那一刻就回顶：那时列表还是旧的，等新书插进来又被锚定挤到上面去。
     * 用 requestScrollToItem：它在下一次测量生效，正好与这次数据变化同一帧。
     */
    var scrollTopOnReveal by remember { mutableStateOf(false) }
    LaunchedEffect(scrollTopOnReveal, books) {
        if (scrollTopOnReveal && books.any { it.hidden }) {
            if (layout == BookshelfLayout.GRID) gridState.requestScrollToItem(0)
            else listState.requestScrollToItem(0)
            scrollTopOnReveal = false
        }
    }
    // 新列表还没到就又收起了：别把这次回顶留到下一回
    LaunchedEffect(showHidden) { if (!showHidden) scrollTopOnReveal = false }

    /**
     * 换了分组就回到顶上。从前停在「全部」的第 20 排切到只有 10 本的分组，人会被夹在列表末尾，
     * 或停在锚定的那本书附近（发现页切分类同理，见 ExploreScreen）。
     *
     * 等新分组的列表到了再滚（最多等一会儿）：先滚的话列表随后按旧的锚点 key 又跳到别处。
     * 「旧列表」得在**点下 chip 的那一刻**记（[groupSwitchFrom]）：到了 effect 里再取，新分组和新列表
     * 往往已经同一帧到了，就会傻等一个永远不来的「下一份」，每次切分组都白等满超时。
     * 只由点击触发：从别的页面返回、分组被删后自动退回「全部」，都不该把人拽回顶部。
     */
    var groupSwitchFrom by remember { mutableStateOf<List<BookEntity>?>(null) }
    LaunchedEffect(groupSwitchFrom) {
        val from = groupSwitchFrom ?: return@LaunchedEffect
        withTimeoutOrNull(GROUP_SWITCH_WAIT_MS) { snapshotFlow { books }.first { it !== from } }
        if (layout == BookshelfLayout.GRID) gridState.requestScrollToItem(0)
        else listState.requestScrollToItem(0)
        groupSwitchFrom = null
    }

    fun openHidden() {
        if (viewModel.openHiddenPanel()) scrollTopOnReveal = true
    }

    /**
     * 展开隐藏书籍。开了验证就先过一遍系统的指纹/面容/设备密码。
     * 收起不需要验证 —— 关灯不用钥匙。
     *
     * 设备上已经没有任何锁屏（用户在系统设置里删了密码）时**不验证、直接放行**并说明原因：
     * 那种状态下 authenticate 每次都失败，而关掉「展开需验证」的开关就长在隐藏区里 ——
     * 进不去就关不掉，书被永久锁在外面。这把锁本就没有找回途径（见 AppPrefs.hiddenRequireAuth），
     * 宁可放行，也不能把人关在自己的书外面。
     */
    fun revealHidden() {
        if (!requireAuth || activity == null) {
            openHidden()
            return
        }
        // 点下去这一刻现查，别信进页时记下的那份
        biometricAvailable = BiometricAuth.isAvailable(context)
        if (!biometricAvailable) {
            openHidden()
            viewModel.messages.emit("这台设备没有设置锁屏，已跳过验证。不需要这把锁的话，可以在上方关掉它")
            return
        }
        scope.launch {
            when (val r = BiometricAuth.authenticate(activity, "查看隐藏的书")) {
                BiometricAuth.Result.Success -> openHidden()
                // 用户自己按了取消，别再弹个错误教育他
                BiometricAuth.Result.Cancelled -> Unit
                is BiometricAuth.Result.Failed -> viewModel.messages.emit("验证失败: ${r.message}")
            }
        }
    }

    // 隐藏区收起时（✕、长按收摊、切后台自动收起），还开着的长按菜单 / 分组 / 删除弹窗若指着一本
    // 隐藏书，一并关掉 —— 否则切回来书架已经收好，菜单 / 弹窗上却还摊着那本隐藏书的书名。
    // 宽屏右栏的详情交给导航层（onHiddenCollapsed）。只认「开 → 收」这一下：进页时本来就是收着的，
    // 那时去动导航栈，正赶上侧滑返回的话会把栈多弹一层。
    val currentOnHiddenCollapsed by rememberUpdatedState(onHiddenCollapsed)
    var wasShowingHidden by remember { mutableStateOf(showHidden) }
    LaunchedEffect(showHidden) {
        if (wasShowingHidden && !showHidden) {
            if (actionTarget?.hidden == true) actionTarget = null
            if (groupTarget?.hidden == true) {
                groupTarget = null
                groupInput = ""
            }
            if (deleteTarget?.hidden == true) deleteTarget = null
            currentOnHiddenCollapsed(allBooks.filter { it.hidden }.mapTo(HashSet()) { it.id })
        }
        wasShowingHidden = showHidden
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importBooks(uris) }

    fun launchImport() {
        importLauncher.launch(
            arrayOf(
                "text/plain", "application/epub+zip",
                "application/octet-stream", "application/x-mobipocket-ebook",
            )
        )
    }

    // 指着隐藏书的菜单/弹窗，在隐藏区收起的那一帧就别画了（状态由上面的 LaunchedEffect 清，它晚一帧）
    fun BookEntity.stillVisible() = showHidden || !hidden

    // 「全部隐藏」要按**整个书架**判，不能拿分组筛选后的 books 判：选中一个恰好没有可见书的
    // 分组时 books 也是空的，若在这里整页换成空态，筛选条跟着一起消失，用户就没法切回「全部」了 ——
    // 分组为空另有列表里的提示。
    //
    // 书全被隐藏了也走这同一个空态。这里**不能**写「N 本书已隐藏」—— 那等于把秘密写在最显眼的地方。
    // 必须和真正的空书架一模一样（连「导入本地书」按钮也在）：别人看不出区别，而你知道长按标题能回来。
    // 从前是两段各写一份，全隐藏那份漏了导入按钮，一眼就和真空架对不上 —— 所以合成一个分支。
    val allHidden = allBooks.isNotEmpty() && hiddenCount == allBooks.size && !showHidden
    // 欢迎页只给真正空、且从没用过的书架（全藏起来的不算，见 WelcomeState）。
    // 标记还没读到（null）时两样都先不画：别先闪一下普通空态再换成欢迎页
    val showWelcome = allBooks.isEmpty() && onboarded != true
    // 有书可摆：Room 第一帧到了、不是欢迎页也不是空态。隐藏区状态条与分组筛选条只在这时出现
    val showShelf = shelfReady && !showWelcome && allBooks.isNotEmpty() && !allHidden

    /**
     * 钉在顶栏下面、不随书滚走的两样：隐藏区状态条、分组筛选条。放进顶栏的 bottomContent（多选栏也放，
     * 挑书时照样能切分组），书就能从它们下面滚过去、一起被毛玻璃盖住 —— 放在内容区顶上的话，
     * 那一截永远挡着书架，玻璃顶栏下面也就没东西可透。
     */
    val shelfHeader: @Composable () -> Unit = {
        if (showShelf) {
            Column {
                // 隐藏区的状态条。**只在已经展开时出现** —— 它的存在本身就是线索，
                // 所以它只能长在你已经进来之后的地方。设置页里一个字都不提隐藏书籍。
                // 「展开需验证」直接挂在条上，不再套一层设置弹层。
                AnimatedVisibility(
                    visible = showHidden,
                    enter = expandEnter(),
                    exit = expandExit(),
                ) {
                    HiddenStatusBar(
                        requireAuth = requireAuth,
                        biometricAvailable = biometricAvailable,
                        onToggleAuth = { viewModel.setHiddenRequireAuth(it) },
                        // ✕ = 退出整个隐藏区（状态条收起 + 书藏回去），与长按标题一键收摊等价
                        onCollapse = { viewModel.collapseHiddenAll() },
                    )
                }

                // 只有真的分了组才显示筛选条 —— 没分组的人不该被一排"全部"占掉一行屏幕。
                // 收敛到共享 ChipRow（与发现页/订阅页同一形态、同一首尾边距）
                if (groups.isNotEmpty()) {
                    val chipOptions = listOf("全部") + groups + listOf("未分组")
                    val selectedChip = when (group) {
                        null -> 0
                        BookshelfViewModel.UNGROUPED -> chipOptions.lastIndex
                        else -> (groups.indexOf(group) + 1).coerceAtLeast(0)
                    }
                    ChipRow(
                        options = chipOptions,
                        selectedIndex = selectedChip,
                        onSelect = { i ->
                            groupSwitchFrom = books
                            viewModel.setGroup(
                                when (i) {
                                    0 -> null
                                    chipOptions.lastIndex -> BookshelfViewModel.UNGROUPED
                                    // chipOptions[0] 是「全部」，所以分组下标 = i-1
                                    else -> groups[i - 1]
                                }
                            )
                        },
                        contentPadding = PaddingValues(
                            horizontal = Dimens.listHorizontal,
                            vertical = Dimens.gapXS,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 长按一本书的操作菜单：从这本书上弹出来（HyperOS 的长按菜单），不再从屏幕底升起一整块面板 ——
     * 人的视线本来就落在长按的那本书上。和书放进同一个 Box，菜单按这个 Box 定位。
     *
     * 按 id 而不是整个实体对比：菜单开着时追更 / 进度回写会换来一份新的 BookEntity，按相等比就当场收起了。
     * 收起时只清自己那份：快速长按另一本书时，上一本的菜单收起不该把新目标也清掉。
     */
    val bookMenu: @Composable (BookEntity) -> Unit = { book ->
        AppDropdownMenu(
            expanded = actionTarget?.id == book.id && book.stillVisible(),
            onDismissRequest = { if (actionTarget?.id == book.id) actionTarget = null },
            items = buildList {
                add(AppMenuItem("书籍详情", onClick = { onOpenDetail(book.id) }))
                add(
                    AppMenuItem(
                        "设置分组",
                        summary = book.groupName.ifBlank { "未分组" },
                        onClick = {
                            groupInput = book.groupName
                            groupTarget = book
                        },
                    ),
                )
                // 「多选」不带说明：说明里若写「批量删除、分组或隐藏」，平时这一项就把藏书功能写在了脸上
                add(AppMenuItem("多选", onClick = { viewModel.startSelection(book.id) }))
                // 「从书架隐藏」只在隐藏区打开时出现 —— 平时长按看不到，别人想不到能藏书。
                if (showHidden) {
                    add(
                        AppMenuItem(
                            if (book.hidden) "取消隐藏" else "从书架隐藏",
                            summary = if (book.hidden) "重新显示在书架上" else "书、进度、缓存都还在，只是列表里不显示",
                            onClick = { viewModel.setHidden(book.id, !book.hidden) },
                        ),
                    )
                }
                add(
                    AppMenuItem(
                        "从书架删除",
                        summary = "本地文件与缓存将一并删除",
                        onClick = { deleteTarget = book },
                    ),
                )
            },
        )
    }

    // 与设置页同一套画布：页面底 surface（见 settingsPageColor），进出设置不再闪色。
    // 顶栏与画布同色（在 AppScaffold 里是同色的毛玻璃），否则滚动时顶出一道色缝
    val pageColor = settingsPageColor()
    AppScaffold(
        // 这两条顶栏刻意留窄栏（AppToolbar），不换 AppTopBar 的折叠大标题：一是进出多选会在两种
        // 栏之间切，高度不同就会跳；二是标题位是长按入口、副标题还要播追更进度，大标题那一行
        // 放不下这两样。下面还压着隐藏区与下拉刷新，再叠一层折叠手势也容易互相抢。
        containerColor = pageColor,
        topBar = {
            if (selectionMode) {
                // 批量操作栏：跟书源管理同一套 —— 高频动作留成图标，低频的收进溢出菜单。
                var overflowOpen by remember { mutableStateOf(false) }
                AppToolbar(
                    navigationIcon = {
                        AppIconButton(onClick = viewModel::clearSelection) {
                            Icon(MiuixIcons.Close, contentDescription = "退出多选")
                        }
                    },
                    containerColor = pageColor,
                    actions = {
                        AppIconButton(onClick = viewModel::selectAll) {
                            Icon(MiuixIcons.SelectAll, contentDescription = "全选")
                        }
                        AppIconButton(onClick = { confirmBatchDelete = true }) {
                            Icon(
                                MiuixIcons.Delete,
                                contentDescription = "删除",
                                tint = MiuixTheme.colorScheme.error,
                            )
                        }
                        Box {
                            AppIconButton(onClick = { overflowOpen = true }) {
                                Icon(MiuixIcons.More, contentDescription = "更多")
                            }
                            AppDropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                                items = buildList {
                                    add(
                                        AppMenuItem("设置分组", onClick = {
                                            overflowOpen = false
                                            groupInput = ""
                                            showGroupAssign = true
                                        }),
                                    )
                                    // 隐藏相关只在隐藏区露面 —— 平时菜单里出现「隐藏」等于把功能写在脸上
                                    if (showHidden) {
                                        add(
                                            AppMenuItem("从书架隐藏", onClick = {
                                                overflowOpen = false
                                                viewModel.setHiddenSelected(true)
                                            }),
                                        )
                                        add(
                                            AppMenuItem("取消隐藏", onClick = {
                                                overflowOpen = false
                                                viewModel.setHiddenSelected(false)
                                            }),
                                        )
                                    }
                                },
                            )
                        }
                    },
                    bottomContent = shelfHeader,
                ) {
                    ToolbarTitle("已选 ${selected.size} 本")
                }
            } else {
                AppToolbar(
                    containerColor = pageColor,
                    title = {
                        // 长按标题 = 隐藏书籍的入口。它本身不可见、不可猜 ——
                        // 一个写在菜单里的「显示隐藏的书」，等于告诉所有人这里藏了东西。
                        // 追更时副标题显示进度：PullToRefresh 转圈早收起后靠这里知道还在刷。
                        Column(
                            Modifier.combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                // 隐藏区开着时再长按 = 一键收摊 —— 有人走过来时你需要这一下
                                onLongClick = { if (showHidden) viewModel.collapseHiddenAll() else revealHidden() },
                            ),
                        ) {
                            ToolbarTitle("书架")
                            if (refreshProgress.running) {
                                Text(
                                    "更新中 ${refreshProgress.done}/${refreshProgress.total}",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    actions = {
                        // 网格/列表改在「设置 → 外观 → 书架显示」—— 顶栏留给搜索/发现/导入，少一个占位
                        AppIconButton(onClick = onOpenSearch) {
                            Icon(MiuixIcons.Search, contentDescription = "搜索")
                        }
                        // 发现入口可在设置里关掉 —— 不看发现页的人，那个图标只是碍事
                        if (exploreEnabled) {
                            AppIconButton(onClick = onOpenExplore) {
                                // Miuix 图标里没有罗盘，用 Material 的线条版，与 Miuix 的线条图标一个笔触
                                Icon(Icons.Outlined.Explore, contentDescription = "发现")
                            }
                        }
                        // 导入从右下角的 FAB 挪上来：书架是个网格，FAB 会盖住右下角那本书
                        AppIconButton(
                            onClick = ::launchImport,
                            enabled = !importing,
                        ) {
                            if (importing) {
                                AppLoadingIndicator(
                                    color = LocalContentColor.current,
                                    size = Dimens.buttonSpinner,
                                )
                            } else {
                                Icon(MiuixIcons.Add, contentDescription = "导入本地书")
                            }
                        }
                        // 这里**没有**「显示隐藏的书」。
                        //
                        // 从前它就明晃晃写着「显示隐藏的书（1）」—— 等于把「我藏了 1 本书」
                        // 贴在脸上，隐藏功能等于没做。隐藏的入口本身也必须是隐藏的：
                        // 改为长按顶栏的「书架」标题。
                        //
                        // 这里也**不放**「收起隐藏的书」：收起的出口是隐藏状态条上的 ✕，
                        // 而状态条恰恰在 showHidden 时才出现 —— 与这个按钮的出现时机完全重合，
                        // 两个按钮干同一件事。曾经短暂加过一个，随即因重复删掉。
                        //
                        // 从前这里是个三点菜单，里头只有「书源管理」和「设置」两条 ——
                        // 而书源管理在设置里本来就有一份，等于让用户多点一下去到同一个地方。
                        // 删掉重复的那条之后，菜单只剩一条，那就不该还是菜单：直接给齿轮。
                        AppIconButton(onClick = onOpenSettings) {
                            Icon(MiuixIcons.Settings, contentDescription = "设置")
                        }
                    },
                    bottomContent = shelfHeader,
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // Room 第一帧还没到：先空着。这时 allBooks 的空不代表书架是空的，落进下面的空态
        // 就是每次启动都先闪一下「书架空空如也 / 导入本地书」。本地库通常几十毫秒就回来，转圈也只会一闪
        if (!shelfReady) return@AppScaffold
        if (showWelcome) {
            if (onboarded == false) {
                WelcomeState(
                    onImport = ::launchImport,
                    onRestore = onOpenWebDav,
                    onSkip = viewModel::finishOnboarding,
                    onOpenDisclaimer = onOpenDisclaimer,
                    modifier = Modifier.padding(padding.horizontalOnly()),
                    topInset = padding.calculateTopPadding(),
                    bottomInset = padding.calculateBottomPadding(),
                )
            }
        } else if (!showShelf) {
            EmptyState(
                icon = Icons.Outlined.AutoStories,
                title = "书架空空如也",
                hint = "导入本地 txt / EPUB / MOBI，或搜索已导入的规则",
                actionLabel = "导入本地书",
                onAction = ::launchImport,
                modifier = Modifier.padding(padding),
            )
        } else {
            // 书铺满整屏、滚到玻璃顶栏下面：外层只让开两侧；顶栏（连同钉在它下面的状态条、筛选条）的高度
            // 垫在列表开头，导航栏的高度垫在末尾 —— 书封既能滚到顶栏下面透出来，也能滚到手势条下面，
            // 而第一排、最后一排仍能完整露出来（见 AppScaffold / horizontalOnly）
            val topInset = padding.calculateTopPadding()
            val bottomInset = padding.calculateBottomPadding()
            // Miuix 的下拉刷新（HyperOS 那种把内容往下拽、顶上冒出圆圈与一行状态字）。
            // 状态字默认是英文，这里换成中文 —— 书架下拉干的是「追更」，字也照这个说
            PullToRefresh(
                isRefreshing = refreshing,
                onRefresh = { viewModel.refreshAll() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding.horizontalOnly()),
                pullToRefreshState = rememberPullToRefreshState(),
                // 它只用 top：刷新圈从顶栏下沿冒出来，而不是藏在玻璃顶栏后面
                contentPadding = PaddingValues(top = topInset),
                refreshTexts = REFRESH_TEXTS,
            ) {
                // 在 items 外面读一次：animationsEnabled() 内部会挂一个 ContentObserver，
                // 写进 items 里就是每本书挂一个
                val motionOn = animationsEnabled()
                // 动效走主题令牌（全局唯一来源）：位移用 spatial、淡入淡出用 effects。
                // 进出隐藏区时整批书凭空出现/消失，从前是硬闪 —— 看不出
                // 是多了几本书，还是整个书架换了内容。淡入淡出 + 其余书平滑挪位，
                // 才看得出「这几本是插进来的」。
                // 关了系统动画就传 null（这个 API 的「不动画」写法），而不是 tween(0) ——
                // 后者仍会走一遍动画机器，只是时长为零。
                val motion = AppTheme.motionScheme
                // 多选时收起卡片：那时人在挑书，顶上一张大卡片只是挡路，也说不清它算不算被选上。
                // 卡片在的时候，这本书就不再在下面的网格里重复一格 —— 它们本来就紧挨着，同一张封面摆两遍；
                // 卡片收起（多选）时它回到网格里，照样能选
                val hero = continueReading?.takeIf { !selectionMode }
                val shelfItems = if (hero == null) books else books.filter { it.id != hero.book.id }
                // 平时长按 = 单本操作菜单；已在多选里则长按继续勾选切换。
                // 触觉只挂在长按上 —— 点选切换不震，避免选十几本震十几下。
                fun longPress(book: BookEntity) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (selectionMode) viewModel.toggleSelect(book.id) else actionTarget = book
                }
                fun click(book: BookEntity, bounds: Rect?) {
                    if (selectionMode) viewModel.toggleSelect(book.id)
                    else {
                        // 先冻住书架顺序再进书：开书会把它顶到第一位，退出动画却要缩回这里
                        viewModel.freezeShelfOrder()
                        onOpenBook(book.id, originOf(bounds, windowSize))
                    }
                }
                when {
                    // 当前分组下没有可见的书（未分组也算一个分组）。筛选条留在上面，空态只占内容区。
                    // 隐藏区未展开时被藏起来的书也不算「有」，提示文案里同样一个字不提隐藏。
                    books.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.AutoStories,
                        title = "这个分组还没有书",
                        hint = "长按书封「设置分组」可以把书移进来，或在上方切换分组",
                        modifier = Modifier.padding(top = topInset, bottom = bottomInset),
                    )
                    layout == BookshelfLayout.GRID -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = Dimens.bookshelfGridMin),
                        // HyperOS 的回弹 + 甩到头轻震（见 pageScroll）；自带的那份回弹关掉，免得叠两层
                        modifier = Modifier
                            .fillMaxSize()
                            .pageScroll(),
                        state = gridState,
                        // 顶上垫顶栏高度、底部垫导航栏高度：书封滚到两头的玻璃下面，
                        // 而第一排、最后一排仍能滚清、不被挡住
                        contentPadding = PaddingValues(
                            start = Dimens.gapM,
                            end = Dimens.gapM,
                            top = topInset + Dimens.gapM,
                            bottom = Dimens.gapM + bottomInset,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
                        verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
                        overscrollEffect = null,
                    ) {
                        if (hero != null) {
                            item(
                                key = CONTINUE_READING_KEY,
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = CONTINUE_READING_KEY,
                            ) {
                                Box(
                                    Modifier.animateItem(
                                        fadeInSpec = if (motionOn) motion.defaultEffectsSpec() else null,
                                        placementSpec = if (motionOn) motion.defaultSpatialSpec() else null,
                                        fadeOutSpec = if (motionOn) motion.fastEffectsSpec() else null,
                                    ),
                                ) {
                                    ContinueReadingCard(
                                        entry = hero,
                                        onClick = { bounds -> click(hero.book, bounds) },
                                        onLongClick = { longPress(hero.book) },
                                    )
                                    bookMenu(hero.book)
                                }
                            }
                        }
                        items(shelfItems, key = { it.id }) { book ->
                            Box(
                                Modifier.animateItem(
                                    fadeInSpec = if (motionOn) motion.defaultEffectsSpec() else null,
                                    placementSpec = if (motionOn) motion.defaultSpatialSpec() else null,
                                    fadeOutSpec = if (motionOn) motion.fastEffectsSpec() else null,
                                ),
                            ) {
                                BookCard(
                                    book = book,
                                    selected = book.id in selected,
                                    selectionMode = selectionMode,
                                    onClick = { bounds -> click(book, bounds) },
                                    onLongClick = { longPress(book) },
                                )
                                bookMenu(book)
                            }
                        }
                    }
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .pageScroll(),
                        state = listState,
                        // 书一行一行拼成一张圆角卡（groupedCardItem 自己让开左右、画卡片底）：
                        // HyperOS 的列表是灰底上的白卡，不是通栏的行。行间不留缝，留了卡就断成一截一截
                        contentPadding = PaddingValues(
                            top = topInset + Dimens.gapS,
                            bottom = Dimens.gapS + bottomInset,
                        ),
                        overscrollEffect = null,
                    ) {
                        if (hero != null) {
                            item(key = CONTINUE_READING_KEY, contentType = CONTINUE_READING_KEY) {
                                // 卡片与书的卡同宽（左右同一档外边距）；下面再空一档，别跟书的卡粘在一起
                                Box(
                                    Modifier
                                        .animateItem(
                                            fadeInSpec = if (motionOn) motion.defaultEffectsSpec() else null,
                                            placementSpec = if (motionOn) motion.defaultSpatialSpec() else null,
                                            fadeOutSpec = if (motionOn) motion.fastEffectsSpec() else null,
                                        )
                                        .padding(horizontal = Dimens.listHorizontal)
                                        .padding(bottom = Dimens.gapM),
                                ) {
                                    ContinueReadingCard(
                                        entry = hero,
                                        onClick = { bounds -> click(hero.book, bounds) },
                                        onLongClick = { longPress(hero.book) },
                                    )
                                    bookMenu(hero.book)
                                }
                            }
                        }
                        itemsIndexed(shelfItems, key = { _, book -> book.id }) { index, book ->
                            Box(
                                Modifier
                                    .animateItem(
                                        fadeInSpec = if (motionOn) motion.defaultEffectsSpec() else null,
                                        placementSpec = if (motionOn) motion.defaultSpatialSpec() else null,
                                        fadeOutSpec = if (motionOn) motion.fastEffectsSpec() else null,
                                    )
                                    .groupedCardItem(index, shelfItems.size),
                            ) {
                                BookShelfListRow(
                                    book = book,
                                    selected = book.id in selected,
                                    selectionMode = selectionMode,
                                    position = groupPosition(index, shelfItems.size),
                                    onClick = { bounds -> click(book, bounds) },
                                    onLongClick = { longPress(book) },
                                )
                                bookMenu(book)
                            }
                        }
                    }
                }
            }
        }
    }

    groupTarget?.takeIf { it.stillVisible() }?.let { book ->
        fun dismissGroupTarget() {
            groupTarget = null
            groupInput = ""
        }
        AppAlertDialog(
            onDismissRequest = { dismissGroupTarget() },
            title = "设置分组",
            confirmText = "确定",
            onConfirm = {
                viewModel.assignGroup(book.id, groupInput)
                dismissGroupTarget()
            },
            content = {
                CompactTextField(
                    value = groupInput,
                    onValueChange = { groupInput = it },
                    placeholder = "分组名，留空则移出",
                )
                if (groups.isNotEmpty()) {
                    ChipRow(
                        options = groups,
                        selectedIndex = groups.indexOf(groupInput),
                        onSelect = { groupInput = groups[it] },
                    )
                }
            },
        )
    }

    deleteTarget?.takeIf { it.stillVisible() }?.let { book ->
        AppAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除书籍",
            text = "确定从书架删除《${book.title}》吗？本地文件与缓存将一并删除。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteBook(book.id)
                deleteTarget = null
            },
        )
    }

    if (showGroupAssign) {
        fun dismissGroupAssign() {
            showGroupAssign = false
            groupInput = ""
        }
        AppAlertDialog(
            onDismissRequest = { dismissGroupAssign() },
            title = "设置分组",
            confirmText = "确定",
            onConfirm = {
                val name = groupInput
                dismissGroupAssign()
                viewModel.assignGroupSelected(name)
            },
            content = {
                CompactTextField(
                    value = groupInput,
                    onValueChange = { groupInput = it },
                    placeholder = "分组名，留空则移出",
                )
                if (groups.isNotEmpty()) {
                    // 已有分组一键选中，省得每次手打（还容易打错，打错就多出一个组）
                    ChipRow(
                        options = groups,
                        selectedIndex = groups.indexOf(groupInput),
                        onSelect = { groupInput = groups[it] },
                    )
                }
            },
        )
    }

    if (confirmBatchDelete) {
        AppAlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = "删除书籍",
            text = "确定从书架删除选中的 ${selected.size} 本吗？本地文件与缓存将一并删除。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteSelected()
                confirmBatchDelete = false
            },
        )
    }

}

/**
 * 列表行：小封面 + 书名/作者/最新章，方便扫更新。
 * 走共享 [ContentListItem]；交互（点开、长按、多选、进书原点）与 [BookCard] 对齐。
 * 行本身透明，底色与圆角由外面的 [groupedCardItem] 画（一行一行拼成一张卡）；
 * [position] 只决定按压反馈切哪几个角，与卡的四角对齐。
 */
@Composable
private fun BookShelfListRow(
    book: BookEntity,
    selected: Boolean,
    selectionMode: Boolean,
    position: SettingGroupPosition,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cover = remember { CoverAnchor() }
    val leadingContent: @Composable () -> Unit = {
        BookShelfListLeading(
            book = book,
            selected = selected,
            selectionMode = selectionMode,
            anchor = cover,
        )
    }
    val trailingContent: (@Composable () -> Unit)? = if (selectionMode) {
        {
            // Miuix 的圆形勾选框（HyperOS 多选的样子）。纯展示：选中语义由整行的 Checkbox 角色给，
            // 它自己再报一个「复选框」读屏就成了两个焦点
            Checkbox(
                state = ToggleableState(selected),
                onClick = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    } else {
        null
    }
    val overlineContent: (@Composable () -> Unit)? = if (book.author.isNotBlank()) {
        {
            Text(book.author, style = ContentListDefaults.overlineStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        null
    }
    val latest = book.latestChapterTitle
    val supportingContent: (@Composable () -> Unit)? = if (!latest.isNullOrBlank()) {
        {
            Text(latest, style = ContentListDefaults.supportingStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        null
    }
    val headline: @Composable () -> Unit = {
        Text(book.title, style = ContentListDefaults.titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    val colors = ContentListDefaults.plainColors()
    val shape = settingGroupItemShape(position)
    if (selectionMode) {
        ContentListItem(
            checked = selected,
            onCheckedChange = { onClick(cover.boundsInWindow()) },
            onLongClick = onLongClick,
            modifier = modifier,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            contentPadding = ContentListDefaults.ComfortablePadding,
            colors = colors,
            shape = shape,
            content = headline,
        )
    } else {
        ContentListItem(
            onClick = { onClick(cover.boundsInWindow()) },
            onLongClick = onLongClick,
            modifier = modifier,
            leadingContent = leadingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            contentPadding = ContentListDefaults.ComfortablePadding,
            colors = colors,
            shape = shape,
            content = headline,
        )
    }
}

@Composable
private fun BookShelfListLeading(
    book: BookEntity,
    selected: Boolean,
    selectionMode: Boolean,
    anchor: CoverAnchor,
) {
    val coverShape = AppShapes.small
    Box(
        Modifier
            .width(Dimens.coverThumbWidth)
            .height(Dimens.coverThumbHeight),
    ) {
        BookCover(
            title = book.title,
            coverModel = book.coverPath,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (selectionMode && selected) {
                        Modifier.border(
                            width = Dimens.gapXS,
                            color = MiuixTheme.colorScheme.primary,
                            shape = coverShape,
                        )
                    } else {
                        Modifier
                    },
                )
                .onPlaced { anchor.coordinates = it },
            placeholderChars = 2,
            // 书名是这一行的标题，封面不再进读屏
            contentDescription = null,
        )
        CoverProgressOverlay(book.readingProgress(), coverShape)
        if (book.hidden) {
            Icon(
                MiuixIcons.Hide,
                contentDescription = "已隐藏",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Dimens.gapXS)
                    .size(Dimens.iconSm),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (book.newChapterCount > 0 && !selectionMode) {
            Badge(
                modifier = Modifier.align(Alignment.TopEnd)
                // 角标合并进卡片的语义后，读屏只念一个光秃秃的「3」：给它一句完整的话
                .clearAndSetSemantics { contentDescription = "${book.newChapterCount} 章更新" },
                containerColor = MiuixTheme.colorScheme.error,
                contentColor = MiuixTheme.colorScheme.onError,
            ) {
                Text(if (book.newChapterCount > 99) "99+" else "${book.newChapterCount}")
            }
        }
    }
}

/**
 * @param onClick 带上这张封面在窗口里的位置 —— 进书要从「这本书所在的位置」放大展开，
 *   得知道展开的原点在哪。书还没测量出位置时给 null，调用方退回中心展开。
 *   多选模式下调用方会忽略 bounds，只用来切换勾选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 用封面（而不是整张卡片）的位置：展开动画应该从书封长出来，标题那一行不算
    val cover = remember { CoverAnchor() }
    // 与 BookCover 的 shape 对齐 —— 选中描边若用 medium，封面却是 small，就会看出「框套框」
    val coverShape = AppShapes.small
    Column(
        // **不要在这里 clip**。从前是 `clip(shapes.medium)` 加在整个 Column 上，
        // 而 Column 装的是「封面 + 标题」—— 标题正好贴着底边，左下角那道 12dp 的圆弧
        // 就直接啃掉了书名第一个字的一角。
        //
        // 它当初是为了约束涟漪。但涟漪本来就该铺满可点区域（整张卡片），
        // 方角涟漪在网格项上完全正常；封面自己的圆角由 BookCover 负责。
        modifier
            .semantics(mergeDescendants = true) {
                // 多选时整张卡就是一个 Checkbox：读屏报「已选中/未选中」，
                // 而不是「封面装饰图标 + 可点击区」两个焦点。
                if (selectionMode) {
                    role = Role.Checkbox
                    this.selected = selected
                }
            }
            .combinedClickable(
                // 平时整张卡就是一个按钮（点开读、长按出菜单）；多选时角色由上面的 Checkbox 给
                role = if (selectionMode) null else Role.Button,
                onClick = { onClick(cover.boundsInWindow()) },
                onLongClick = onLongClick,
            )
    ) {
        // 多选态外圈留出与描边等宽的 padding：未选不画边、已选才描 primary，
        // 占位却始终在 —— 勾选不会把封面挤小一圈。
        // （以前给每本都画 outline，整架书像进了铁笼，也不合系统相册那种媒体多选：
        //  未选只靠角标，已选靠半透明遮罩 + 描边。）
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .then(if (selectionMode) Modifier.padding(Dimens.gapXS) else Modifier),
        ) {
            BookCover(
                title = book.title,
                coverModel = book.coverPath,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (selectionMode && selected) {
                            Modifier.border(
                                width = Dimens.gapXS,
                                color = MiuixTheme.colorScheme.primary,
                                shape = coverShape,
                            )
                        } else Modifier
                    )
                    .onPlaced { cover.coordinates = it },
                // 默认封面有三行可用，别把书名截半截：「女总裁的全能兵王」take(6) = 「女总裁的全能」
                placeholderChars = 14,
                // 卡片下面就印着书名，封面不再进读屏
                contentDescription = null,
            )
            // 多选时让位给描边与勾选：选中态才是那时要看的
            if (!selectionMode) CoverProgressOverlay(book.readingProgress(), coverShape)
            // 已选盖一层 primary 半透明，比给每本套灰框更轻、也更像系统相册多选
            if (selectionMode && selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(coverShape)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.20f)),
                )
            }
            // 「显示隐藏的书」开着时，得看得出哪些是隐藏的 —— 否则分不清，会重复隐藏
            if (book.hidden) {
                Icon(
                    MiuixIcons.Hide,
                    contentDescription = "已隐藏",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Dimens.gapXS)
                        .size(Dimens.iconSm),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            // 追更红点：自从你上次打开之后，新增了几章。
            // 画在封面右上角而不是占一行 —— 网格里每多一行文字，一屏就少一排书
            if (book.newChapterCount > 0 && !selectionMode) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.gapXS)
                    // 角标合并进卡片的语义后，读屏只念一个光秃秃的「3」：给它一句完整的话
                    .clearAndSetSemantics { contentDescription = "${book.newChapterCount} 章更新" },
                    containerColor = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError,
                ) {
                    Text(if (book.newChapterCount > 99) "99+" else "${book.newChapterCount}")
                }
            }
            // 角标用 Miuix 的圆形勾选框，与 HyperOS 相册多选一致；未选是一枚灰色实心圆，
            // 花封面上也看得见。纯展示：整张卡已经是一个 Checkbox 语义（见上面的 semantics），
            // 它自己再报一个复选框，读屏就成了两个焦点
            if (selectionMode) {
                Checkbox(
                    state = ToggleableState(selected),
                    onClick = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.gapXS)
                        .clearAndSetSemantics {},
                )
            }
        }
        Text(
            book.title,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Dimens.gapXS),
        )
        // 最新章节。数据一直存在 BookEntity 里，却从来没画出来过 ——
        // 于是"这本书更到哪了"只能靠点进去看
        if (!book.latestChapterTitle.isNullOrBlank()) {
            Text(
                book.latestChapterTitle,
                // 正文性小字不低于 footnote1（13sp）；footnote2 只有 11sp
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 隐藏区状态条。打开隐藏区 = 就是在看隐藏书。
 * 「下次展开需验证」直接挂在条上（进了隐藏区才能改，外人翻设置看不到）；✕ 退出。
 *
 * 形态对齐设置分组卡：`large` 圆角 + [settingsCardColor]（书架画布已与设置页同用
 * [settingsPageColor]，浅色灰底白卡 / 深色黑底浅卡）。
 * 开关行语义与 [SwitchRow] 相同：整行可点，Switch 只展示；✕ 不进开关焦点。
 *
 * 设备上没有锁屏时开不了锁（无从验证），但**已经开着的锁必须关得掉**：用户删了锁屏密码之后，
 * 偏好里还记着「需验证」，这时把开关置灰，等他哪天重新设上锁屏，锁又悄悄生效了，而他以为早关了。
 */
@Composable
private fun HiddenStatusBar(
    requireAuth: Boolean,
    biometricAvailable: Boolean,
    onToggleAuth: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 开关显示的是偏好本身；能不能拨：能验证就能开关，不能验证则只许关
    val toggleEnabled = biometricAvailable || requireAuth
    Surface(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal)
            // 与 SettingGroup 上下同一档留白
            .padding(vertical = Dimens.gapS),
        shape = AppShapes.large,
        color = settingsCardColor(),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 开关区吃掉剩余宽度；按压反馈的形用 Middle（直角），外层 Surface 裁出大圆角，
            // 避免 Alone 四角圆贴到 ✕ 左侧看起来像两块卡。
            ContentListItem(
                checked = requireAuth,
                onCheckedChange = onToggleAuth,
                modifier = Modifier.weight(1f),
                enabled = toggleEnabled,
                role = Role.Switch,
                trailingContent = {
                    Switch(
                        checked = requireAuth,
                        enabled = toggleEnabled,
                        onCheckedChange = null,
                    )
                },
                colors = ContentListDefaults.plainColors(),
                shape = settingGroupItemShape(SettingGroupPosition.Middle),
                content = {
                    Text(
                        when {
                            biometricAvailable -> "展开需验证"
                            requireAuth -> "未设锁屏，验证暂不生效"
                            else -> "无法上锁"
                        },
                        style = ContentListDefaults.titleStyle,
                        color = if (biometricAvailable) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            AppIconButton(onClick = onCollapse) {
                Icon(MiuixIcons.Close, contentDescription = "收起隐藏区")
            }
        }
    }
}

/** 换分组后等新列表最多多久再回顶（见 BookshelfScreen 里的 groupSwitchFrom） */
private const val GROUP_SWITCH_WAIT_MS = 500L

/** 下拉刷新的四段状态字：下拉中 / 过了阈值 / 刷新中 / 完成（顺序是 Miuix PullToRefresh 规定的） */
private val REFRESH_TEXTS = listOf("下拉检查更新", "松开检查更新", "正在检查更新…", "检查完成")
