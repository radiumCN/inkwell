package com.radium.inkwell.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppSnackbarHost
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(bookId: String, onRead: () -> Unit, onBack: () -> Unit) {
    val bookRepo = koinInject<BookRepository>()
    val netBookRepo = koinInject<NetBookRepository>()
    val sourceRepo = koinInject<BookSourceRepository>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 刷新完要把新的章节数显示出来，所以 book 得能被重新赋值
    var reloadKey by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    // 区分"还在查库"和"查完了没这本书"：前者转圈，后者给空态 —— 不再是加载中一片空白
    var loaded by remember(bookId, reloadKey) { mutableStateOf(false) }
    val book by produceState<BookEntity?>(initialValue = null, bookId, reloadKey) {
        value = bookRepo.getBook(bookId)
        loaded = true
    }

    /**
     * 追更。**这是全 App 唯一能拿到新章节的途径** —— 在此之前 refreshToc 写好了却没有任何
     * 入口能触发它：网文天天更新，而你的目录停在加书那天，读到最后一章就再也没有下文，
     * 除非把书删了重加。
     */
    fun refreshToc() {
        val b = book ?: return
        if (refreshing) return
        refreshing = true
        scope.launch {
            val rule = b.sourceId?.let { sourceRepo.getRule(it) }
            if (rule == null) {
                refreshing = false
                snackbar.showSnackbar("书源不存在，先换源")
                return@launch
            }
            val before = b.totalChapters
            netBookRepo.refreshToc(b, rule)
                .onSuccess { total ->
                    reloadKey++
                    val added = total - before
                    snackbar.showSnackbar(
                        if (added > 0) "更新了 $added 章" else "已经是最新的了"
                    )
                }
                .onFailure { snackbar.showSnackbar("刷新失败: ${it.message}") }
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书籍详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 本地书没有"追更"可言
                    if (book?.type == BookType.NET) {
                        IconButton(onClick = ::refreshToc, enabled = !refreshing) {
                            if (refreshing) {
                                CircularProgressIndicator(
                                    Modifier.size(Dimens.buttonSpinner),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新目录（追更）")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        val b = book
        if (b == null) {
            if (loaded) {
                EmptyState(
                    icon = Icons.Default.Warning,
                    title = "书籍不存在",
                    hint = "它可能已被删除",
                    actionLabel = "返回",
                    onAction = onBack,
                    modifier = Modifier.padding(padding),
                )
            } else {
                LoadingState(Modifier.padding(padding))
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapL),
        ) {
            Row {
                // 从前这里手搓了一份封面（Surface + AsyncImage + take(4) 占位），
                // 而 components 里早就有 BookCover —— 圆角还硬编码成 8.dp。
                BookCover(
                    title = b.title,
                    coverModel = b.coverPath,
                    modifier = Modifier.size(width = 96.dp, height = 128.dp),
                    placeholderChars = 4,
                )
                Column(Modifier.padding(start = Dimens.gapL).align(Alignment.CenterVertically)) {
                    Text(b.title, style = MaterialTheme.typography.titleLarge)
                    if (b.author.isNotBlank()) {
                        Text(
                            b.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "共 ${b.totalChapters} 章",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PrimaryButton(
                text = if (b.readAt > 0) "继续阅读" else "开始阅读",
                onClick = onRead,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!b.intro.isNullOrBlank()) {
                Text("简介", style = MaterialTheme.typography.titleMedium)
                Text(b.intro, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }
}
