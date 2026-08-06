package com.radium.inkwell.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsScreen(
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenReplaceRules: () -> Unit,
) {
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val checkAuthor by appPrefs.changeSourceCheckAuthor.collectAsState(initial = true)
    val autoChangeSource by appPrefs.autoChangeSource.collectAsState(initial = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读与规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingRow(
                title = "书源管理",
                subtitle = "导入、启用、校验与删除",
                onClick = onOpenSources,
            )
            SettingRow(
                title = "净化替换规则",
                subtitle = "删掉正文里的广告、水印与防盗段落",
                onClick = onOpenReplaceRules,
            )
            SwitchRow(
                title = "自动换源",
                subtitle = if (autoChangeSource) {
                    "正文读不出来或超过 15 秒没回来时，自动找一个能读出这一章的源。换完会提示，可撤销"
                } else {
                    "已关闭。读不出来时停在错误页，由你手动换源"
                },
                checked = autoChangeSource,
                onCheckedChange = { on -> scope.launch { appPrefs.setAutoChangeSource(on) } },
            )
            SwitchRow(
                title = "换源时匹配作者",
                subtitle = if (checkAuthor) {
                    "只换到同一作者的书。书源返回的作者常带前缀或干脆为空，卡太死会换不到源"
                } else {
                    "只认书名。可能换到同名不同作者的书"
                },
                checked = checkAuthor,
                onCheckedChange = { on -> scope.launch { appPrefs.setChangeSourceCheckAuthor(on) } },
            )
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }
}
