package com.radium.inkwell.ui.sourceedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.radium.inkwell.ui.components.PrimaryButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.SecondaryButton
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.CompactTextField
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceEditScreen(
    sourceId: String?,
    onBack: () -> Unit,
    viewModel: SourceEditViewModel = koinViewModel { parametersOf(sourceId) },
) {
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
                title = { Text(if (sourceId == null) "新建书源" else "编辑书源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = state.jsonText,
                onValueChange = viewModel::setJson,
                label = { Text("书源规则 JSON") },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                SecondaryButton(
                    text = "校验/格式化",
                    onClick = viewModel::formatJson,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
                PrimaryButton(
                    text = "保存",
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                CompactTextField(
                    value = state.testKeyword,
                    onValueChange = viewModel::setTestKeyword,
                    placeholder = "测试关键词",
                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                        .align(androidx.compose.ui.Alignment.CenterVertically),
                )
                SecondaryButton(
                    text = "全链路测试",
                    onClick = viewModel::testSearchChain,
                    enabled = !state.testing,
                    loading = state.testing,
                    modifier = Modifier.weight(1f).padding(start = 6.dp).align(androidx.compose.ui.Alignment.CenterVertically),
                )
            }
            if (state.testReport.isNotBlank()) {
                Text(
                    state.testReport,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
