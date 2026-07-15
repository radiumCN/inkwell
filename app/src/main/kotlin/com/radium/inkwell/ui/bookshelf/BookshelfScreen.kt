package com.radium.inkwell.ui.bookshelf

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
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val requireAuth by viewModel.hiddenRequireAuth.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val hideBooksEnabled by viewModel.hideBooksEnabled.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity
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
                            if (panelOpen || showHidden) {
                                DropdownMenuItem(
                                    text = { Text("收起隐藏的书") },
                                    onClick = { overflowOpen = false; viewModel.collapseHiddenAll() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("书源管理") },
                                onClick = { overflowOpen = false; onOpenSourceManage() },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
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
                // 隐藏区的"控制台"。**只在已经展开时出现** —— 这个开关的存在本身就是线索，
                // 所以它只能长在你已经进来之后的地方。设置页里一个字都不提隐藏书籍。
                AnimatedVisibility(
                    visible = panelOpen,
                    enter = expandEnter(),
                    exit = expandExit(),
                ) {
                    HiddenAreaBar(
                        requireAuth = requireAuth,
                        biometricAvailable = biometricAvailable,
                        hideBooksEnabled = hideBooksEnabled,
                        onToggleAuth = { viewModel.setHiddenRequireAuth(it) },
                        onToggleHideBooks = { viewModel.setHideBooksEnabled(it) },
                        showHidden = showHidden,
                        onToggleShowHidden = { viewModel.setShowHidden(it) },
                        onCollapse = { viewModel.closeHiddenPanel() },
                    )
                }

                // 只有真的分了组才显示筛选条 —— 没分组的人不该被一排"全部"占掉一行屏幕
                if (groups.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Dimens.gapM),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                    ) {
                        FilterChip(
                            selected = group == null,
                            onClick = { viewModel.setGroup(null) },
                            label = { Text("全部") },
                        )
                        groups.forEach { g ->
                            FilterChip(
                                selected = group == g,
                                onClick = { viewModel.setGroup(g) },
                                label = { Text(g) },
                            )
                        }
                        FilterChip(
                            selected = group == BookshelfViewModel.UNGROUPED,
                            onClick = { viewModel.setGroup(BookshelfViewModel.UNGROUPED) },
                            label = { Text("未分组") },
                        )
                    }
                }
                // 顶部渐隐 + 下拉刷新。书封滚到顶栏边缘时被「雾化」淡进纸背景，
                // 而不是一刀切地硬消失 —— 澎湃 OS / iOS 那种「从栏底下缓缓浮现」的过渡质感。
                // 用纸背景色做遮罩渐变：不落投影、不上真模糊（同色纸面投影=脏灰线，本 App 一贯回避；
                // 真模糊要离屏合成，在这块用户反馈过掉帧的网格上不划算），最轻也最贴暖纸风格。
                //
                // 只保留顶部：底部原来那条渐隐拖着导航栏一起雾化，末排书封等于被一层灰纸糊住，
                // 廉价且突兀 —— 直接去掉，末排 edge-to-edge 滚到导航条下方即可。
                val topFade = Dimens.gapXXL // 渐隐带高度（=顶部留白），拉长过渡跑道
                Box(Modifier.fillMaxSize()) {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { viewModel.refreshAll() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 96.dp),
                            modifier = Modifier.fillMaxSize(),
                            // 顶部留白与渐隐带同高（topFade）：首排静止时正好落在渐隐带下沿、不被雾化；
                            // 底部多留一个导航栏的高度：书封滚到导航条下方，最后一排仍能滚清不被挡住
                            contentPadding = PaddingValues(
                                start = Dimens.gapM,
                                end = Dimens.gapM,
                                top = topFade,
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
                                )
                            }
                        }
                    }
                    // 遮罩层只画渐变、不接触摸事件（无 pointerInput），滚动/点击照穿到下面的网格。
                    // 纸→透明，但**不是**两档线性：两档在 16dp 上等于一条硬线把书封拦腰切断，廉价。
                    // 改用缓入的多档 alpha —— 靠栏一侧几乎全不透明（书封干净没入栏下），越往内
                    // 透明得越慢、尾巴越长，纸面上根本看不出渐变的下边界，才有「缓缓浮现」的高级质感。
                    val fadeColor = MaterialTheme.colorScheme.background
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(topFade)
                            .background(
                                Brush.verticalGradient(
                                    0f to fadeColor,
                                    0.4f to fadeColor.copy(alpha = 0.9f),
                                    0.68f to fadeColor.copy(alpha = 0.55f),
                                    0.86f to fadeColor.copy(alpha = 0.22f),
                                    1f to Color.Transparent,
                                )
                            )
                    )
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        // **不要在这里 clip**。从前是 `clip(shapes.medium)` 加在整个 Column 上，
        // 而 Column 装的是「封面 + 标题」—— 标题正好贴着底边，左下角那道 12dp 的圆弧
        // 就直接啃掉了书名第一个字的一角。
        //
        // 它当初是为了约束涟漪。但涟漪本来就该铺满可点区域（整张卡片），
        // 方角涟漪在网格项上完全正常；封面自己的圆角由 BookCover 负责。
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box {
            BookCover(
                title = book.title,
                coverModel = book.coverPath,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
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
@Composable
private fun HiddenAreaBar(
    requireAuth: Boolean,
    biometricAvailable: Boolean,
    hideBooksEnabled: Boolean,
    showHidden: Boolean,
    onToggleAuth: (Boolean) -> Unit,
    onToggleHideBooks: (Boolean) -> Unit,
    onToggleShowHidden: (Boolean) -> Unit,
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
        Column(
            Modifier.padding(
                start = Dimens.gapL,
                end = Dimens.gapS,
                top = Dimens.gapM,
                bottom = Dimens.gapS,
            )
        ) {
            Text(
                "隐藏的书",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = Dimens.gapS),
            )

            // 这一条才是「书现在显不显」。从前它没有开关 —— 显隐跟面板绑死，
            // 于是「收起」一按，面板关掉的同时把书也藏回去了。你只是不想看那块面板而已。
            SwitchLine(
                title = "在书架上显示",
                subtitle = if (showHidden) {
                    "隐藏的书正混在书架里（角上有标记）"
                } else {
                    "隐藏的书不在书架上"
                },
                checked = showHidden,
                enabled = true,
                onCheckedChange = onToggleShowHidden,
            )

            SwitchLine(
                title = "允许隐藏书籍",
                subtitle = if (hideBooksEnabled) {
                    "长按书籍时会出现「从书架隐藏」"
                } else {
                    "长按书籍时不出现隐藏选项 —— 别人看不出这个功能存在"
                },
                checked = hideBooksEnabled,
                enabled = true,
                onCheckedChange = onToggleHideBooks,
            )

            SwitchLine(
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

            // 只关这块面板，不动书的显隐 —— 那是上面那个开关的事
            TextButton(onClick = onCollapse, modifier = Modifier.align(Alignment.End)) {
                Text("收起面板")
            }
        }
    }
}

/** 隐藏区里的开关行。用不了 SwitchRow —— 那个是给整宽设置页用的，塞进这张卡里左右会顶到边 */
@Composable
private fun SwitchLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Dimens.gapXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Dimens.gapS)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
