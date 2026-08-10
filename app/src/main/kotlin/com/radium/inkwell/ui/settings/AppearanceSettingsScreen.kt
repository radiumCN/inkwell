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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观") },
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
                .verticalScroll(rememberScrollState()),
        ) {
            SettingRow(
                title = "主题外观",
                subtitle = "日间/夜间模式与自定义配色",
                onClick = onOpenTheme,
            )
            SettingRow(
                title = "应用图标",
                subtitle = "${appIcon.label} · ${appIcon.description}",
                onClick = { showIconPicker = true },
            )
            SettingRow(
                title = "书架显示",
                subtitle = bookshelfLayout.label + when (bookshelfLayout) {
                    BookshelfLayout.GRID -> " · 封面优先"
                    BookshelfLayout.LIST -> " · 一行一书，方便扫最新章"
                },
                onClick = { showLayoutPicker = true },
            )
            SwitchRow(
                title = "显示「发现」入口",
                subtitle = if (exploreEnabled) {
                    "书架顶栏显示发现按钮（需已导入带发现规则的来源）"
                } else {
                    "已隐藏。仍可从搜索找书"
                },
                checked = exploreEnabled,
                onCheckedChange = { on -> scope.launch { appPrefs.setExploreEnabled(on) } },
            )
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
