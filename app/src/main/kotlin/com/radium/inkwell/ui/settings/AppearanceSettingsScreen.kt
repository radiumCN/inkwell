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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.bookshelf.BookshelfLayout
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.util.AppIconManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onOpenTheme: () -> Unit,
) {
    val context = LocalContext.current
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exploreEnabled by appPrefs.exploreEnabled.collectAsState(initial = true)
    val bookshelfLayout by appPrefs.bookshelfLayout.collectAsState(initial = BookshelfLayout.GRID)
    var appIcon by remember { mutableStateOf(AppIconManager.current(context)) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showLayoutPicker by remember { mutableStateOf(false) }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("外观", topBarScroll, onBack = onBack, containerColor = pageColor) },
        snackbarHost = { AppSnackbarHost(snackbar) },
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
                    title = "主题外观",
                    onClick = onOpenTheme,
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "应用图标",
                    value = appIcon.label,
                    onClick = { showIconPicker = true },
                    position = SettingGroupPosition.Middle,
                )
                SettingRow(
                    title = "书架显示",
                    value = bookshelfLayout.label,
                    onClick = { showLayoutPicker = true },
                    position = SettingGroupPosition.Last,
                )
            }
            SettingGroup {
                SwitchRow(
                    title = "显示「发现」入口",
                    checked = exploreEnabled,
                    onCheckedChange = { on -> scope.launch { appPrefs.setExploreEnabled(on) } },
                    position = SettingGroupPosition.Alone,
                )
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }

    if (showLayoutPicker) {
        OptionPickerSheet(
            title = "书架显示",
            options = BookshelfLayout.entries.map {
                PickerOption(
                    id = it.name,
                    label = it.label,
                    subtitle = when (it) {
                        BookshelfLayout.GRID -> "封面网格，一屏多本书"
                        BookshelfLayout.LIST -> "小封面 + 书名作者与最新章"
                    },
                )
            },
            selectedId = bookshelfLayout.name,
            onSelect = { opt ->
                showLayoutPicker = false
                runCatching { BookshelfLayout.valueOf(opt.id) }.getOrNull()?.let { picked ->
                    scope.launch { appPrefs.setBookshelfLayout(picked) }
                }
            },
            onDismiss = { showLayoutPicker = false },
        )
    }

    if (showIconPicker) {
        AppIconSheet(
            selected = appIcon,
            onSelect = { picked ->
                showIconPicker = false
                if (picked != appIcon) {
                    AppIconManager.apply(context, picked)
                    appIcon = picked
                    scope.launch {
                        snackbar.showSnackbar("已换成「${picked.label}」，桌面图标稍后刷新")
                    }
                }
            },
            onDismiss = { showIconPicker = false },
        )
    }
}
