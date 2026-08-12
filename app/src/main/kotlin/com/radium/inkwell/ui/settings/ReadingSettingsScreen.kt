package com.radium.inkwell.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
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

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("阅读与规则", topBarScroll, onBack = onBack, containerColor = pageColor) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = Dimens.gapS),
        ) {
            SettingGroup {
                SettingRow(
                    title = "书源管理",
                    onClick = onOpenSources,
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "净化替换规则",
                    onClick = onOpenReplaceRules,
                    position = SettingGroupPosition.Last,
                )
            }
            SettingGroup {
                SwitchRow(
                    title = "自动换源",
                    checked = autoChangeSource,
                    onCheckedChange = { on -> scope.launch { appPrefs.setAutoChangeSource(on) } },
                    position = SettingGroupPosition.First,
                )
                SwitchRow(
                    title = "换源时匹配作者",
                    checked = checkAuthor,
                    onCheckedChange = { on -> scope.launch { appPrefs.setChangeSourceCheckAuthor(on) } },
                    position = SettingGroupPosition.Last,
                )
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }
}
