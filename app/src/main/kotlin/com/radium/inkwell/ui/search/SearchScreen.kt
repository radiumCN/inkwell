package com.radium.inkwell.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.BookListRow
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.core.source.SearchResult
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, viewModel: SearchViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text("书名 / 作者") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.searching) {
                LinearProgressIndicator(
                    progress = {
                        if (state.sourceCount == 0) 0f
                        else state.doneCount.toFloat() / state.sourceCount
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.results.isEmpty() && !state.searching) {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = "搜索全网书源",
                    hint = "输入书名或作者，从启用的书源中并发搜索",
                )
            } else {
                LazyColumn {
                    items(state.results, key = { "${it.sourceId}|${it.bookUrl}" }) { result ->
                        BookListRow(
                            title = result.title,
                            subtitle = listOfNotNull(result.author, result.latestChapter)
                                .joinToString(" · "),
                            caption = "来源: ${result.sourceId}",
                            coverModel = result.coverUrl,
                            trailingLabel = "加入",
                            trailingLoading = state.addingUrl == result.bookUrl,
                            onTrailing = { viewModel.addToShelf(result) },
                        )
                    }
                }
            }
        }
    }
}
