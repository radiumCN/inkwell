package com.radium.inkwell.ui.bookshelf

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import com.radium.inkwell.util.BiometricAuth
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    viewModel: BookshelfViewModel,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
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
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }
    val haptic = LocalHapticFeedback.current

    /**
     * 展开隐藏书籍。开了验证就先过一遍系统的指纹/面容/设备密码。
     * 收起不需要验证 —— 关灯不用钥匙。
     */
    fun revealHidden() {
        if (!requireAuth || activity == null) {
            viewModel.openHiddenPanel()
            return
        }
        scope.launch {
            when (val r = BiometricAuth.authenticate(activity, "查看隐藏的书")) {
                BiometricAuth.Result.Success -> viewModel.openHiddenPanel()
                // 用户自己按了取消，别再弹个错误教育他
                BiometricAuth.Result.Cancelled -> Unit
                is BiometricAuth.Result.Failed -> viewModel.messages.emit("验证失败: ${r.message}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importBooks(uris) }

    // 与设置页同一套画布：浅色灰底、深色黑底（见 settingsPageColor），进出设置不再闪白。
    val pageColor = settingsPageColor()
    val topBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = pageColor,
        scrolledContainerColor = pageColor,
    )
    Scaffold(
        // 这两条顶栏刻意留经典窄栏，不换 AppTopBar 的两段式 Flexible：一是进出多选会在两种
        // 栏之间切，高度不同就会跳；二是标题位是长按入口、副标题还要播追更进度，两段式会把它
        // 摊到大标题的位置上。下面还压着隐藏区与下拉刷新，再叠一层折叠手势也容易互相抢。
        // 顶栏与内容区同色，避免默认 surface / scrolled surfaceContainer 顶出一道色缝。
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
                            onClick = {
                                importLauncher.launch(
                                    arrayOf(
                                        "text/plain", "application/epub+zip",
                                        "application/octet-stream", "application/x-mobipocket-ebook",
                                    )
                                )
                            },
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
        if (allBooks.isNotEmpty() && books.isEmpty() && hiddenCount > 0 && !showHidden) {
            // 书全被隐藏了。这里**不能**写「N 本书已隐藏」—— 那等于把秘密写在最显眼的地方。
            // 就显示一个和真正空书架一模一样的空态：别人看不出区别，而你知道长按标题能回来。
            EmptyState(
                icon = Icons.Default.AutoStories,
                title = "书架空空如也",
                hint = "导入本地 txt / EPUB / MOBI，或搜索已导入的规则",
                modifier = Modifier.padding(padding),
            )
        } else if (allBooks.isEmpty()) {
            EmptyState(
                icon = Icons.Default.AutoStories,
                title = "书架空空如也",
                hint = "导入本地 txt / EPUB / MOBI，或搜索已导入的规则",
                actionLabel = "导入本地书",
                onAction = {
                    importLauncher.launch(
                        arrayOf(
                            "text/plain", "application/epub+zip",
                            "application/octet-stream", "application/x-mobipocket-ebook",
                        )
                    )
                },
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
                            viewModel.setGroup(
                                when (i) {
                                    0 -> null
                                    chipOptions.lastIndex -> BookshelfViewModel.UNGROUPED
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
                when (layout) {
                    BookshelfLayout.GRID -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = Dimens.bookshelfGridMin),
                        modifier = Modifier.fillMaxSize(),
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
                                    else onOpenBook(book.id, originOf(bounds, windowSize))
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
                    BookshelfLayout.LIST -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
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
                                    else onOpenBook(book.id, originOf(bounds, windowSize))
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

    actionTarget?.let { book ->
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
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
                        actionTarget = null
                        onOpenDetail(id)
                    },
                )
                SettingRow(
                    title = "设置分组",
                    subtitle = book.groupName.ifBlank { "未分组" },
                    onClick = {
                        groupInput = book.groupName
                        groupTarget = book
                        actionTarget = null
                    },
                )
                SettingRow(
                    title = "多选",
                    // 「隐藏」在多选里也是隐藏区专属（顶栏 ⋮ 那两项同样裹在 showHidden 里）。
                    // 副标题不跟着分情况写的话，平时这行就把藏书功能写在了脸上，
                    // 而进了多选又根本找不到它 —— 既泄了底，又是句空头承诺。
                    subtitle = if (showHidden) "批量删除、分组或隐藏" else "批量删除或分组",
                    onClick = {
                        viewModel.startSelection(book.id)
                        actionTarget = null
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
                            viewModel.setHidden(book.id, !book.hidden)
                            actionTarget = null
                        },
                    )
                }
                SettingRow(
                    title = "从书架删除",
                    subtitle = "本地文件与缓存将一并删除",
                    onClick = {
                        deleteTarget = book
                        actionTarget = null
                    },
                )
            }
        }
    }

    groupTarget?.let { book ->
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

    deleteTarget?.let { book ->
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
    var coverBounds by remember { mutableStateOf<Rect?>(null) }
    val leadingContent: @Composable () -> Unit = {
        BookShelfListLeading(
            book = book,
            selected = selected,
            selectionMode = selectionMode,
            onBounds = { coverBounds = it },
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
            onCheckedChange = { onClick(coverBounds) },
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
            onClick = { onClick(coverBounds) },
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
    onBounds: (Rect) -> Unit,
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
                .onGloballyPositioned { onBounds(it.boundsInWindow()) },
            placeholderChars = 2,
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
                modifier = Modifier.align(Alignment.TopEnd),
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
    var coverBounds by remember { mutableStateOf<Rect?>(null) }
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
            .combinedClickable(onClick = { onClick(coverBounds) }, onLongClick = onLongClick)
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
                    .onGloballyPositioned { coverBounds = it.boundsInWindow() },
                // 默认封面有三行可用，别把书名截半截：「女总裁的全能兵王」take(6) = 「女总裁的全能」
                placeholderChars = 14,
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
                    modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.gapXS),
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
                style = MaterialTheme.typography.labelSmall,
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
 */
@Composable
private fun HiddenStatusBar(
    requireAuth: Boolean,
    biometricAvailable: Boolean,
    onToggleAuth: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val authOn = requireAuth && biometricAvailable
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Surface(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal)
            .padding(vertical = Dimens.gapM / 2),
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
                checked = authOn,
                onCheckedChange = onToggleAuth,
                modifier = Modifier.weight(1f),
                enabled = biometricAvailable,
                trailingContent = {
                    Switch(
                        checked = authOn,
                        enabled = biometricAvailable,
                        onCheckedChange = null,
                    )
                },
                colors = ContentListDefaults.groupedColors(pressed = pressed),
                shapes = ContentListDefaults.groupedShapes(SettingGroupPosition.Middle),
                interactionSource = interactionSource,
                content = {
                    Text(
                        if (biometricAvailable) "展开需验证" else "无法上锁",
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
