package com.radium.inkwell.ui.bookshelf

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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppSnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.settingsCardColor
import com.radium.inkwell.ui.components.settingsPageColor
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalLayoutDirection
import com.radium.inkwell.util.BiometricAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.radium.inkwell.ui.components.Motion
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
private class CoverAnchor {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookshelfScreen(
    onOpenBook: (String, TransformOrigin) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSettings: () -> Unit,
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

    // 隐藏区收起时（✕、长按收摊、切后台自动收起），还开着的长按面板 / 分组 / 删除弹窗若指着一本
    // 隐藏书，一并关掉 —— 否则切回来书架已经收好，面板上却还摊着那本隐藏书的书名。
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

    // 与设置页同一套画布：浅色灰底、深色黑底（见 settingsPageColor），进出设置不再闪白。
    val pageColor = settingsPageColor()
    // 纯 MD3：实心 tonal 顶栏（container / scrolled 同色，避免滚动时顶出色缝）。
    // 不做磨砂玻璃、也不做底边 scrim 渐变 —— MD3 没有这些组件。
    val topBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = pageColor,
        scrolledContainerColor = pageColor,
    )
    Scaffold(
        // 这两条顶栏刻意留经典窄栏，不换 AppTopBar 的两段式 Flexible：一是进出多选会在两种
        // 栏之间切，高度不同就会跳；二是标题位是长按入口、副标题还要播追更进度，两段式会把它
        // 摊到大标题的位置上。下面还压着隐藏区与下拉刷新，再叠一层折叠手势也容易互相抢。
        containerColor = pageColor,
        topBar = {
            if (selectionMode) {
                // 批量操作栏：跟书源管理同一套 —— 高频动作留成图标，低频的收进溢出菜单。
                var overflowOpen by remember { mutableStateOf(false) }
                TopAppBar(
                    title = {
                        Text(
                            "已选 ${selected.size} 本",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        AppIconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        AppIconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }
                        AppIconButton(onClick = { confirmBatchDelete = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        Box {
                            AppIconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("设置分组") },
                                    onClick = {
                                        overflowOpen = false
                                        groupInput = ""
                                        showGroupAssign = true
                                    },
                                )
                                // 隐藏相关只在隐藏区露面 —— 平时菜单里出现「隐藏」等于把功能写在脸上
                                if (showHidden) {
                                    DropdownMenuItem(
                                        text = { Text("从书架隐藏") },
                                        onClick = {
                                            overflowOpen = false
                                            viewModel.setHiddenSelected(true)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("取消隐藏") },
                                        onClick = {
                                            overflowOpen = false
                                            viewModel.setHiddenSelected(false)
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = topBarColors,
                )
            } else {
                TopAppBar(
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
                            Text("书架", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (refreshProgress.running) {
                                Text(
                                    "更新中 ${refreshProgress.done}/${refreshProgress.total}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    actions = {
                        // 网格/列表改在「设置 → 外观 → 书架显示」—— 顶栏留给搜索/发现/导入，少一个占位
                        AppIconButton(onClick = onOpenSearch) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        // 发现入口可在设置里关掉 —— 不看发现页的人，那个图标只是碍事
                        if (exploreEnabled) {
                            AppIconButton(onClick = onOpenExplore) {
                                Icon(Icons.Default.Explore, contentDescription = "发现")
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
                                Icon(Icons.Default.Add, contentDescription = "导入本地书")
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
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                    colors = topBarColors,
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // Room 第一帧还没到：先空着。这时 allBooks 的空不代表书架是空的，落进下面的空态
        // 就是每次启动都先闪一下「书架空空如也 / 导入本地书」。本地库通常几十毫秒就回来，转圈也只会一闪
        if (!shelfReady) return@Scaffold
        // 「全部隐藏」要按**整个书架**判，不能拿分组筛选后的 books 判：选中一个恰好没有可见书的
        // 分组时 books 也是空的，若在这里整页换成空态，筛选条跟着一起消失，用户就没法切回「全部」了 ——
        // 分组为空另有下面 ChipRow 之下的提示。
        //
        // 书全被隐藏了也走这同一个空态。这里**不能**写「N 本书已隐藏」—— 那等于把秘密写在最显眼的地方。
        // 必须和真正的空书架一模一样（连「导入本地书」按钮也在）：别人看不出区别，而你知道长按标题能回来。
        // 从前是两段各写一份，全隐藏那份漏了导入按钮，一眼就和真空架对不上 —— 所以合成一个分支。
        val allHidden = allBooks.isNotEmpty() && hiddenCount == allBooks.size && !showHidden
        if (allBooks.isEmpty() || allHidden) {
            EmptyState(
                icon = Icons.Default.AutoStories,
                title = "书架空空如也",
                hint = "导入本地 txt / EPUB / MOBI，或搜索已导入的规则",
                actionLabel = "导入本地书",
                onAction = ::launchImport,
                modifier = Modifier.padding(padding),
            )
        } else {
            // 沉浸式底部：只吃顶栏/两侧的 inset，**不吃底部导航栏的**——底部让给下面的网格，
            // 由它用 contentPadding 把导航栏高度让出来，于是书封能滚到系统导航条下面（edge-to-edge）
            val layoutDirection = LocalLayoutDirection.current
            Column(
                Modifier.fillMaxSize().padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                )
            ) {
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
                val pullRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { viewModel.refreshAll() },
                    modifier = Modifier.fillMaxSize(),
                    state = pullRefreshState,
                    indicator = {
                        // Expressive 形变指示，替代默认圆形 PullToRefreshDefaults.Indicator
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullRefreshState,
                            isRefreshing = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                ) {
                    // 在 items 外面读一次：animationsEnabled() 内部会挂一个 ContentObserver，
                    // 写进 items 里就是每本书挂一个
                    val motionOn = animationsEnabled()
                    // 动效走主题令牌（全局唯一来源）：位移用 spatial、淡入淡出用 effects
                    val motion = MaterialTheme.motionScheme
                    when {
                        // 当前分组下没有可见的书（未分组也算一个分组）。筛选条留在上面，空态只占内容区。
                        // 隐藏区未展开时被藏起来的书也不算「有」，提示文案里同样一个字不提隐藏。
                        books.isEmpty() -> EmptyState(
                            icon = Icons.Default.AutoStories,
                            title = "这个分组还没有书",
                            hint = "长按书封「设置分组」可以把书移进来，或在上方切换分组",
                        )
                        layout == BookshelfLayout.GRID -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = Dimens.bookshelfGridMin),
                            modifier = Modifier.fillMaxSize(),
                            state = gridState,
                            // 底部多留一个导航栏的高度：网格铺到屏幕最底边、书封滚到导航条下方，
                            // 而最后一排仍能滚清导航条不被挡住
                            contentPadding = PaddingValues(
                                start = Dimens.gapM,
                                end = Dimens.gapM,
                                top = Dimens.gapM,
                                bottom = Dimens.gapM + padding.calculateBottomPadding(),
                            ),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
                            verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
                        ) {
                            items(books, key = { it.id }) { book ->
                                BookCard(
                                    book = book,
                                    selected = book.id in selected,
                                    selectionMode = selectionMode,
                                    onClick = { bounds ->
                                        if (selectionMode) viewModel.toggleSelect(book.id)
                                        else {
                                            // 先冻住书架顺序再进书：开书会把它顶到第一位，退出动画却要缩回这里
                                            viewModel.freezeShelfOrder()
                                            onOpenBook(book.id, originOf(bounds, windowSize))
                                        }
                                    },
                                    // 方案 A：平时长按 = 单本操作面板；已在多选里则长按继续勾选切换。
                                    // 触觉只挂在长按上 —— 点选切换不震，避免选十几本震十几下。
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (selectionMode) viewModel.toggleSelect(book.id)
                                        else actionTarget = book
                                    },
                                    // 进出隐藏区时整批书凭空出现/消失，从前是硬闪 —— 看不出
                                    // 是多了几本书，还是整个书架换了内容。淡入淡出 + 其余书平滑挪位，
                                    // 才看得出「这几本是插进来的」。
                                    // 关了系统动画就传 null（这个 API 的「不动画」写法），而不是 tween(0) ——
                                    // 后者仍会走一遍动画机器，只是时长为零。
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = if (motionOn) motion.defaultEffectsSpec() else null,
                                        placementSpec = if (motionOn) motion.defaultSpatialSpec() else null,
                                        fadeOutSpec = if (motionOn) motion.fastEffectsSpec() else null,
                                    ),
                                )
                            }
                        }
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            // 左右内缩 + 行距：给 Expressive ListItem 的圆角容器留出背景露出，
                            // 否则贴边铺满时形状变化几乎看不见，主题的 surface 层级也读不出来。
                            contentPadding = ContentListDefaults.listContentPadding(
                                bottom = padding.calculateBottomPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
                        ) {
                            items(books, key = { it.id }) { book ->
                                BookShelfListRow(
                                    book = book,
                                    selected = book.id in selected,
                                    selectionMode = selectionMode,
                                    onClick = { bounds ->
                                        if (selectionMode) viewModel.toggleSelect(book.id)
                                        else {
                                            // 先冻住书架顺序再进书：开书会把它顶到第一位，退出动画却要缩回这里
                                            viewModel.freezeShelfOrder()
                                            onOpenBook(book.id, originOf(bounds, windowSize))
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (selectionMode) viewModel.toggleSelect(book.id)
                                        else actionTarget = book
                                    },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = if (motionOn) motion.defaultEffectsSpec() else null,
                                        placementSpec = if (motionOn) motion.defaultSpatialSpec() else null,
                                        fadeOutSpec = if (motionOn) motion.fastEffectsSpec() else null,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 指着隐藏书的面板/弹窗，在隐藏区收起的那一帧就别画了（状态由上面的 LaunchedEffect 清，它晚一帧）
    fun BookEntity.stillVisible() = showHidden || !hidden

    actionTarget?.takeIf { it.stillVisible() }?.let { book ->
        // 点了选项先把面板收下去再办事。从前直接把 actionTarget 置空：面板当场从组合树摘掉，
        // 退场动画被掐断、一帧之内消失（同 OptionPickerSheet 里的那条）
        val sheetState = rememberModalBottomSheetState()
        val sheetScope = rememberCoroutineScope()
        fun dismissThen(action: () -> Unit) {
            sheetScope.launch {
                sheetState.hide()
                actionTarget = null
                action()
            }
        }
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = sheetState,
            // Sheet 用 surface；SettingRow 卡片读 surfaceContainerLow。
            // 默认两者都走 Low 时选项会糊进底色，看起来像没有卡片（见 ChangeSourceSheet）。
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = Dimens.gapXL)) {
                Text(
                    book.title,
                    Modifier.padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.gapS),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SettingRow(
                    title = "书籍详情",
                    subtitle = "简介、目录、刷新目录",
                    onClick = {
                        val id = book.id
                        dismissThen { onOpenDetail(id) }
                    },
                )
                SettingRow(
                    title = "设置分组",
                    subtitle = book.groupName.ifBlank { "未分组" },
                    onClick = {
                        dismissThen {
                            groupInput = book.groupName
                            groupTarget = book
                        }
                    },
                )
                SettingRow(
                    title = "多选",
                    // 「隐藏」在多选里也是隐藏区专属（顶栏 ⋮ 那两项同样裹在 showHidden 里）。
                    // 副标题不跟着分情况写的话，平时这行就把藏书功能写在了脸上，
                    // 而进了多选又根本找不到它 —— 既泄了底，又是句空头承诺。
                    subtitle = if (showHidden) "批量删除、分组或隐藏" else "批量删除或分组",
                    onClick = {
                        dismissThen { viewModel.startSelection(book.id) }
                    },
                )
                // 「从书架隐藏」只在隐藏区打开时出现 —— 平时长按看不到，别人想不到能藏书。
                if (showHidden) {
                    SettingRow(
                        title = if (book.hidden) "取消隐藏" else "从书架隐藏",
                        subtitle = if (book.hidden) {
                            "重新显示在书架上"
                        } else {
                            "书、进度、缓存都还在，只是列表里不显示"
                        },
                        onClick = {
                            dismissThen { viewModel.setHidden(book.id, !book.hidden) }
                        },
                    )
                }
                SettingRow(
                    title = "从书架删除",
                    subtitle = "本地文件与缓存将一并删除",
                    onClick = {
                        dismissThen { deleteTarget = book }
                    },
                )
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
 */
@Composable
private fun BookShelfListRow(
    book: BookEntity,
    selected: Boolean,
    selectionMode: Boolean,
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
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconMd),
            )
        }
    } else {
        null
    }
    val overlineContent: (@Composable () -> Unit)? = if (book.author.isNotBlank()) {
        {
            Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        null
    }
    val latest = book.latestChapterTitle
    val supportingContent: (@Composable () -> Unit)? = if (!latest.isNullOrBlank()) {
        {
            Text(latest, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        null
    }
    val headline: @Composable () -> Unit = {
        Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

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
    val coverShape = MaterialTheme.shapes.small
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
                            color = MaterialTheme.colorScheme.primary,
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
        if (book.hidden) {
            Icon(
                Icons.Default.VisibilityOff,
                contentDescription = "已隐藏",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Dimens.gapXS)
                    .size(Dimens.iconSm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (book.newChapterCount > 0 && !selectionMode) {
            Badge(
                modifier = Modifier.align(Alignment.TopEnd)
                // 角标合并进卡片的语义后，读屏只念一个光秃秃的「3」：给它一句完整的话
                .clearAndSetSemantics { contentDescription = "${book.newChapterCount} 章更新" },
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
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
    val coverShape = MaterialTheme.shapes.small
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
                // 平时整张卡就是一个按钮（点开读、长按出面板）；多选时角色由上面的 Checkbox 给
                role = if (selectionMode) null else Role.Button,
                onClick = { onClick(cover.boundsInWindow()) },
                onLongClick = onLongClick,
            )
    ) {
        // 多选态外圈留出与描边等宽的 padding：未选不画边、已选才描 primary，
        // 占位却始终在 —— 勾选不会把封面挤小一圈。
        // （以前给每本都画 outline，整架书像进了铁笼，也不合 MD3 媒体多选：
        //  未选只靠角标，已选靠 tonal 遮罩 + 描边。）
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
                                color = MaterialTheme.colorScheme.primary,
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
            // MD3 tonal：已选盖一层 primary 半透明，比给每本套灰框更轻、也更像系统相册多选
            if (selectionMode && selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(coverShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                )
            }
            // 「显示隐藏的书」开着时，得看得出哪些是隐藏的 —— 否则分不清，会重复隐藏
            if (book.hidden) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = "已隐藏",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Dimens.gapXS)
                        .size(Dimens.iconSm),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 追更红点：自从你上次打开之后，新增了几章。
            // 画在封面右上角而不是占一行 —— 网格里每多一行文字，一屏就少一排书
            if (book.newChapterCount > 0 && !selectionMode) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.gapXS)
                    // 角标合并进卡片的语义后，读屏只念一个光秃秃的「3」：给它一句完整的话
                    .clearAndSetSemantics { contentDescription = "${book.newChapterCount} 章更新" },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) {
                    Text(if (book.newChapterCount > 99) "99+" else "${book.newChapterCount}")
                }
            }
            // 角标用成对的圆形图标（CheckCircle / 空心圆），与相册多选一致；
            // 未选仍垫 surface 圆底 —— 花封面上看得见空心圈。
            if (selectionMode) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.gapXS)
                        .size(Dimens.iconMd)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.iconMd),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Dimens.gapXS),
        )
        // 最新章节。数据一直存在 BookEntity 里，却从来没画出来过 ——
        // 于是"这本书更到哪了"只能靠点进去看
        if (!book.latestChapterTitle.isNullOrBlank()) {
            Text(
                book.latestChapterTitle,
                // 正文性小字不低于 bodySmall（12sp）；labelSmall 只有 11sp
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Surface(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal)
            // 与 SettingGroup 上下同一档留白
            .padding(vertical = Dimens.gapS),
        shape = MaterialTheme.shapes.large,
        color = settingsCardColor(),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 开关区吃掉剩余宽度；形用 Middle（直角），外层 Surface 裁出大圆角，
            // 避免 Alone 四角圆贴到 ✕ 左侧看起来像两块卡。
            ContentListItem(
                checked = requireAuth,
                onCheckedChange = onToggleAuth,
                modifier = Modifier.weight(1f),
                enabled = toggleEnabled,
                trailingContent = {
                    Switch(
                        checked = requireAuth,
                        enabled = toggleEnabled,
                        onCheckedChange = null,
                    )
                },
                colors = ContentListDefaults.groupedColors(pressed = pressed),
                shapes = ContentListDefaults.groupedShapes(SettingGroupPosition.Middle),
                interactionSource = interactionSource,
                content = {
                    Text(
                        when {
                            biometricAvailable -> "展开需验证"
                            requireAuth -> "未设锁屏，验证暂不生效"
                            else -> "无法上锁"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (biometricAvailable) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            AppIconButton(onClick = onCollapse) {
                Icon(Icons.Default.Close, contentDescription = "收起隐藏区")
            }
        }
    }
}

/** 换分组后等新列表最多多久再回顶（见 BookshelfScreen 里的 groupSwitchFrom） */
private const val GROUP_SWITCH_WAIT_MS = 500L
