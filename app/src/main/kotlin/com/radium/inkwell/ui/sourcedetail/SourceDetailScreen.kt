package com.radium.inkwell.ui.sourcedetail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppSnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SecondaryButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 书源详情：只读展示规则原文 + 一键全链路测试。书源不在应用内编辑，改规则请重新导入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetailScreen(
    sourceId: String,
    onBack: () -> Unit,
    viewModel: SourceDetailViewModel = koinViewModel { parametersOf(sourceId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    CollectMessages(viewModel.messages, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name.ifBlank { "书源详情" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.screenPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 一键冒烟：源好不好，跑一遍搜索→详情→目录→正文最直观
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
            ) {
                CompactTextField(
                    value = state.testKeyword,
                    onValueChange = viewModel::setTestKeyword,
                    placeholder = "测试关键词",
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
                SecondaryButton(
                    text = "全链路测试",
                    onClick = viewModel::testSearchChain,
                    enabled = !state.testing,
                    loading = state.testing,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
            }
            if (state.testReport.isNotBlank()) {
                Text(
                    state.testReport,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.padding(top = Dimens.gapM),
                )
            }

            Text(
                "规则（只读，改规则请重新导入）",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = Dimens.gapL, bottom = Dimens.gapS),
            )
            // 只读展示：可选中复制，横向滚动避免长规则折行
            SelectionContainer {
                Text(
                    state.jsonText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
