package com.radium.inkwell.ui.bookshelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.CollectMessages
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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importBooks(uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = onOpenExplore) {
                        Icon(Icons.Default.Explore, contentDescription = "发现")
                    }
                    IconButton(onClick = onOpenSourceManage) {
                        Icon(Icons.Default.Source, contentDescription = "书源")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                importLauncher.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream", "application/x-mobipocket-ebook"))
            }) {
                // FAB 是固定尺寸的，转圈也得钉住 24dp —— 默认的 40dp 会把图标位撑变形
                if (importing) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = "导入本地书")
                }
            }
        },
    ) { padding ->
        if (allBooks.isEmpty()) {
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
            Column(Modifier.fillMaxSize().padding(padding)) {
                // 只有真的分了组才显示筛选条 —— 没分组的人不该被一排"全部"占掉一行屏幕
                if (groups.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
        }
    }

    actionTarget?.let { book ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    book.title,
                    Modifier.padding(horizontal = Dimens.rowHorizontal, vertical = 8.dp),
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
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        BookCover(
            title = book.title,
            coverModel = book.coverPath,
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
            placeholderChars = 6,
        )
        Text(
            book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
