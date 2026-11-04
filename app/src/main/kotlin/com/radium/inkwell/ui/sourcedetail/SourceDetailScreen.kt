package com.radium.inkwell.ui.sourcedetail

import com.radium.inkwell.ui.components.exceptBottom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SecondaryButton
import androidx.compose.material.icons.filled.Warning
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.settingsPageColor
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 书源详情：只读展示规则原文 + 一键全链路测试。书源不在应用内编辑，改规则请重新导入。
 */
@Composable
fun SourceDetailScreen(
    sourceId: String,
    onBack: () -> Unit,
    viewModel: SourceDetailViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    CollectMessages(viewModel.messages, snackbar)

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = {
            AppTopBar(
                state.name.ifBlank { "书源详情" },
                topBarScroll,
                onBack = onBack,
                containerColor = pageColor,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        if (state.missing) {
            EmptyState(
                icon = Icons.Default.Warning,
                title = "书源不存在",
                hint = "它可能已被删除",
                actionLabel = "返回",
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                // 内容滚到手势条下面，导航栏高度垫在末尾（见 exceptBottom）
                .padding(padding.exceptBottom())
                // padding 里已含导航栏高度：先把这份 inset 标成已消费，下面的 imePadding 才不会把导航栏那截再算一遍（键盘上方多一道空隙）
                .consumeWindowInsets(padding)
                // 顶上有测试关键词输入框：edge-to-edge 下键盘弹起会盖住下面的测试报告与规则
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            // 控件装进卡里（设置类页面的骨架）：输入框的底是卡上的那一档，直接铺在灰底上几乎看不出框
            SectionHeader("全链路测试")
            SettingGroup {
                SettingBlock {
                    // 一键冒烟：源好不好，跑一遍搜索→详情→目录→正文最直观
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
                    ) {
                        CompactTextField(
                            value = state.testKeyword,
                            onValueChange = viewModel::setTestKeyword,
                            placeholder = if (state.searchable) "测试关键词" else "书的网址",
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
                            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }

            SectionHeader("规则（只读，要改请重新导入或重新新建）")
            SettingGroup {
                SettingBlock {
                    // 只读展示：可选中复制，横向滚动避免长规则折行
                    SelectionContainer {
                        Text(
                            state.jsonText,
                            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}
