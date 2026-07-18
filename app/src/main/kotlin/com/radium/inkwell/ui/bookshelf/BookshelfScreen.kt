package com.radium.inkwell.ui.bookshelf

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppSnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.Motion
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.heightIn
import com.radium.inkwell.ui.components.SwitchRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import com.radium.inkwell.util.BiometricAuth
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.bookOpenContainer
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.expandEnter
import com.radium.inkwell.ui.components.expandExit
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onOpenBook: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSourceManage: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: BookshelfViewModel = koinViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val allBooks by viewModel.allBooks.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    var actionTarget by remember { mutableStateOf<BookEntity?>(null) }
    var groupTarget by remember { mutableStateOf<BookEntity?>(null) }
    var groupInput by remember { mutableStateOf("") }
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    val exploreEnabled by viewModel.exploreEnabled.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val panelOpen by viewModel.hiddenPanelOpen.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenCount.collectAsStateWithLifecycle()
    var overflowOpen by remember { mutableStateOf(false) }
    // 隐藏区的两个持久设置（允许隐藏 / 展开需验证）收进这个底部小 sheet，
    // 顶部状态条只管「本次会话显不显」这一个瞬时开关
    var hiddenSettingsOpen by remember { mutableStateOf(false) }
    val requireAuth by viewModel.hiddenRequireAuth.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val hideBooksEnabled by viewModel.hideBooksEnabled.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? androidx.fragment.app.FragmentActivity
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 长按标题 = 隐藏书籍的入口。它本身不可见、不可猜 ——
                    // 一个写在菜单里的「显示隐藏的书」，等于告诉所有人这里藏了东西。
                    Text(
                        "书架",
                        Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            // 面板开着时再长按 = 一键收摊（面板关掉、书也藏回去）—— 有人走过来时你需要这一下
                            onLongClick = { if (panelOpen) viewModel.collapseHiddenAll() else revealHidden() },
                        ),
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    // 发现入口可在设置里关掉 —— 不看发现页的人，那个图标只是碍事
                    if (exploreEnabled) {
                        IconButton(onClick = onOpenExplore) {
                            Icon(Icons.Default.Explore, contentDescription = "发现")
                        }
                    }
                    // 导入从右下角的 FAB 挪上来：书架是个网格，FAB 会盖住右下角那本书
                    IconButton(
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
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                        } else {
                            Icon(Icons.Default.Add, contentDescription = "导入本地书")
                        }
                    }
                    // IconButton 与菜单必须包在同一个 Box 里：DropdownMenu 锚定的是**它自己**
                    // 在布局里的位置，而它直接摆在 Row 里时占的是零宽的一格 ——
                    // 于是菜单飘到按钮左边老远的地方去了。包一层，它才贴着按钮下方弹出。
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            // 这里**不放**「显示隐藏的书」。
                            //
                            // 从前它就明晃晃写着「显示隐藏的书（1）」—— 等于把「我藏了 1 本书」
                            // 贴在脸上，隐藏功能等于没做。隐藏的入口本身也必须是隐藏的：
                            // 改为长按顶栏的「书架」标题。
                            //
                            // 已经展开时才给一个「收起」—— 那时书角本来就带着标记，藏不住了，
                            // 而用户需要一条明确的路把它收回去。
                            // 每条都配前导图标。纯文本条目没有视觉锚点，眼睛只能逐字读，
                            // 两三条就显得又空又没做完 —— 图标让人扫一眼就能定位。
                            // contentDescription 一律 null：旁边的文字已经是它的名字了，
                            // 给图标再起一个，读屏会把每条念两遍。
                            if (panelOpen || showHidden) {
                                DropdownMenuItem(
                                    text = { Text("收起隐藏的书") },
                                    leadingIcon = {
                                        Icon(Icons.Default.VisibilityOff, contentDescription = null)
                                    },
                                    onClick = { overflowOpen = false; viewModel.collapseHiddenAll() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("书源管理") },
                                leadingIcon = {
                                    Icon(Icons.Default.Source, contentDescription = null)
                                },
                                onClick = { overflowOpen = false; onOpenSourceManage() },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = { overflowOpen = false; onOpenSettings() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (allBooks.isNotEmpty() && books.isEmpty() && hiddenCount > 0 && !showHidden) {
            // 书全被隐藏了。这里**不能**写「N 本书已隐藏」—— 那等于把秘密写在最显眼的地方。
            // 就显示一个和真正空书架一模一样的空态：别人看不出区别，而你知道长按标题能回来。
            EmptyState(
                icon = Icons.Default.AutoStories,
                title = "书架空空如也",
                hint = "导入本地 txt / EPUB / MOBI，或从书源搜索添加",
                modifier = Modifier.padding(padding),
            )
        } else if (allBooks.isEmpty()) {
            EmptyState(
                icon = Icons.Default.AutoStories,
                title = "书架空空如也",
                hint = "导入本地 txt / EPUB / MOBI，或从书源搜索添加",
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
                // 只留「本次会话显不显」这个瞬时开关（切换时下面的书网格当场可见效果），
                // 两个持久设置收进 ⚙ 打开的小 sheet。
                AnimatedVisibility(
                    visible = panelOpen,
                    enter = expandEnter(),
                    exit = expandExit(),
                ) {
                    HiddenStatusBar(
                        showHidden = showHidden,
                        onToggleShowHidden = { viewModel.setShowHidden(it) },
                        onOpenSettings = { hiddenSettingsOpen = true },
                        // ✕ = 退出整个隐藏区（面板收起 + 书藏回去），与长按标题一键收摊等价
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
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { viewModel.refreshAll() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                // 在 items 外面读一次：animationsEnabled() 内部会挂一个 ContentObserver，
                // 写进 items 里就是每本书挂一个
                val motionOn = animationsEnabled()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
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
                            onClick = { onOpenBook(book.id) },
                            // 长按从前直接弹删除 —— 一个误触就把书删了。改成先出动作面板
                            onLongClick = { actionTarget = book },
                            // 切「显示隐藏的书」时整批书凭空出现/消失，从前是硬闪 —— 看不出
                            // 是多了几本书，还是整个书架换了内容。淡入淡出 + 其余书平滑挪位，
                            // 才看得出「这几本是插进来的」。
                            // 关了系统动画就传 null（这个 API 的「不动画」写法），而不是 tween(0) ——
                            // 后者仍会走一遍动画机器，只是时长为零。
                            modifier = Modifier.animateItem(
                                fadeInSpec = if (motionOn) Motion.enterSpec() else null,
                                placementSpec = if (motionOn) Motion.enterSpec() else null,
                                fadeOutSpec = if (motionOn) Motion.exitSpec() else null,
                            ),
                        )
                    }
                }
                }
            }
        }
    }

    actionTarget?.let { book ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
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
                    subtitle = "简介、目录、刷新追更",
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
                // 「从书架隐藏」默认**不出现**。它一旦出现在长按面板里，任何人随手长按一本书
                // 就知道了「这个 App 能藏书」，进而知道该去找藏起来的东西 ——
                // 而隐藏的价值恰恰在于别人想不到去找。开关在隐藏区内部（长按书架标题进去）。
                //
                // 「取消隐藏」不受开关管：它只在隐藏区里才可能被点到（书都藏起来了，
                // 平时根本长按不到），而且关掉开关就再也解不开隐藏，等于把书锁死。
                if (hideBooksEnabled || book.hidden) {
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
        AlertDialog(
            onDismissRequest = { groupTarget = null },
            title = { Text("设置分组") },
            text = {
                Column {
                    OutlinedTextField(
                        value = groupInput,
                        onValueChange = { groupInput = it },
                        label = { Text("分组名") },
                        placeholder = { Text("留空则移出分组") },
                        singleLine = true,
                    )
                    if (groups.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = Dimens.gapS),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                        ) {
                            // 已有分组一键选中，省得每次手打（还容易打错，打错就多出一个组）
                            groups.forEach { g ->
                                FilterChip(
                                    selected = groupInput == g,
                                    onClick = { groupInput = g },
                                    label = { Text(g) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.assignGroup(book.id, groupInput)
                    groupTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { groupTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除书籍") },
            text = { Text("确定从书架删除《${book.title}》吗？本地文件与缓存将一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(book.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    if (hiddenSettingsOpen) {
        HiddenSettingsSheet(
            hideBooksEnabled = hideBooksEnabled,
            requireAuth = requireAuth,
            biometricAvailable = biometricAvailable,
            onToggleHideBooks = { viewModel.setHideBooksEnabled(it) },
            onToggleAuth = { viewModel.setHiddenRequireAuth(it) },
            onDismiss = { hiddenSettingsOpen = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // **不要在这里 clip**。从前是 `clip(shapes.medium)` 加在整个 Column 上，
        // 而 Column 装的是「封面 + 标题」—— 标题正好贴着底边，左下角那道 12dp 的圆弧
        // 就直接啃掉了书名第一个字的一角。
        //
        // 它当初是为了约束涟漪。但涟漪本来就该铺满可点区域（整张卡片），
        // 方角涟漪在网格项上完全正常；封面自己的圆角由 BookCover 负责。
        modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box {
            BookCover(
                title = book.title,
                coverModel = book.coverPath,
                // 开书变换的「源」：点下去时这张封面就地放大、morph 成整页阅读器（另一端在
                // ReaderScreen 的根布局）。书被滚出屏幕没参与组合时自然配不上对，转场退回兜底，不会出错。
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .bookOpenContainer(book.id, isCover = true),
                // 默认封面有三行可用，别把书名截半截：「女总裁的全能兵王」take(6) = 「女总裁的全能」
                placeholderChars = 14,
            )
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
            if (book.newChapterCount > 0) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.gapXS),
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) {
                    Text(if (book.newChapterCount > 99) "99+" else "${book.newChapterCount}")
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
 * 隐藏区的控制台。只有已经通过长按标题（并验证）进来的人才看得到。
 *
 * 「查看时需要验证」这个开关从前住在设置 → 隐私里，副标题还写着「长按书架标题后先验证指纹」——
 * 等于把「这个 App 能藏书」和暗号一起印在了任何人都能翻到的地方。一个只有你知道的东西，
 * 它的开关也只能长在你已经进去之后的地方。
 */
/**
 * 隐藏区状态条。一条轻量的横条，压在书网格上方：
 * - 左侧眼睛图标 + 「本次会话显不显」的文字状态；
 * - 中间的开关就是那个瞬时显隐（切换时下面网格当场可见效果，所以不塞进会盖住网格的弹层）；
 * - ⚙ 打开两个持久设置的小 sheet；✕ 一键退出整个隐藏区。
 *
 * 从前是一张占屏三分之一的大卡把三个开关堆在一起，浏览隐藏书时一直杵在书上方；
 * 且瞬时显隐与持久设置混在一处，概念糊。这里把两者拆开。
 */
@Composable
private fun HiddenStatusBar(
    showHidden: Boolean,
    onToggleShowHidden: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier
                .padding(start = Dimens.gapL, end = Dimens.gapXS)
                .heightIn(min = Dimens.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = null,
                Modifier.size(Dimens.iconSm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (showHidden) "隐藏的书显示中" else "隐藏的书已收起",
                Modifier
                    .weight(1f)
                    .padding(start = Dimens.gapS, end = Dimens.gapS),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Switch(checked = showHidden, onCheckedChange = onToggleShowHidden)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Tune, contentDescription = "隐藏设置")
            }
            IconButton(onClick = onCollapse) {
                Icon(Icons.Default.Close, contentDescription = "收起隐藏区")
            }
        }
    }
}

/** 隐藏区的两个持久设置：从状态条的 ⚙ 弹出。瞬时显隐不在这里（它在状态条上、要网格可见） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenSettingsSheet(
    hideBooksEnabled: Boolean,
    requireAuth: Boolean,
    biometricAvailable: Boolean,
    onToggleHideBooks: (Boolean) -> Unit,
    onToggleAuth: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.gapXL)) {
            Text(
                "隐藏设置",
                Modifier.padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapS),
                style = MaterialTheme.typography.titleMedium,
            )
            SwitchRow(
                title = "允许隐藏书籍",
                subtitle = if (hideBooksEnabled) {
                    "长按书籍时会出现「从书架隐藏」"
                } else {
                    "长按书籍时不出现隐藏选项 —— 别人看不出这个功能存在"
                },
                checked = hideBooksEnabled,
                onCheckedChange = onToggleHideBooks,
            )
            SwitchRow(
                title = "展开时需要验证",
                subtitle = when {
                    !biometricAvailable -> "这台设备还没设过指纹/面容或锁屏密码，无法上锁"
                    requireAuth -> "下次长按书架标题时，先验证指纹/面容"
                    else -> "任何人长按书架标题都能展开"
                },
                checked = requireAuth && biometricAvailable,
                // 设备上一把锁都没有时开了它，就是把自己锁在门外 —— 这道锁没有找回途径
                enabled = biometricAvailable,
                onCheckedChange = onToggleAuth,
            )
        }
    }
}
