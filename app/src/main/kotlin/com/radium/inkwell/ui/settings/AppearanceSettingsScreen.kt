package com.radium.inkwell.ui.settings

import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.SectionHeader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.bookshelf.BookshelfLayout
import com.radium.inkwell.ui.bookshelf.ShelfColumns
import com.radium.inkwell.ui.components.SegmentedChoice
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.expandEnter
import com.radium.inkwell.ui.components.expandExit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SpinnerSettingRow
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.util.AppIconManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onOpenTheme: () -> Unit,
) {
    val context = LocalContext.current
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 提示走 MessageBus：新提示替换正在显示的那条，而不是排在它后面等 4 秒
    val messages = remember { MessageBus() }
    CollectMessages(messages, snackbar)
    // 读预热好的那份，第一帧就是真值（见 AppPrefs 的「设置页读的预热版」）
    val exploreEnabled = appPrefs.exploreEnabledState.collectAsStateWithLifecycle().value ?: true
    val rssEntryEnabled = appPrefs.rssEntryEnabledState.collectAsStateWithLifecycle().value ?: true
    val duplicateHint = appPrefs.duplicateHintEnabledState.collectAsStateWithLifecycle().value ?: true
    val bookshelfLayout = appPrefs.bookshelfLayoutState.collectAsStateWithLifecycle().value ?: BookshelfLayout.GRID
    val bookshelfColumns = appPrefs.bookshelfColumnsState.collectAsStateWithLifecycle().value ?: ShelfColumns.DEFAULT
    var appIcon by remember { mutableStateOf(AppIconManager.current(context)) }
    var showIconPicker by remember { mutableStateOf(false) }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("外观", topBarScroll, onBack = onBack, containerColor = pageColor) },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                // 顶栏、导航栏的高度都垫在滚动内容首尾：内容能滚到玻璃顶栏和手势条下面（见 AppScaffold）
                .padding(padding.horizontalOnly())
                .pageScroll()
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(top = padding.calculateTopPadding() + Dimens.gapS, bottom = padding.calculateBottomPadding()),
        ) {
            // 按「改的是哪儿」分两组：整个 App 的样子 / 书架这一页。「发现」入口长在书架顶栏上，
            // 从前却单独一组挂在最后，跟同样管书架的「书架显示」隔开了
            SectionHeader("应用")
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
                    position = SettingGroupPosition.Last,
                )
            }
            SectionHeader("书架")
            SettingGroup {
                SpinnerSettingRow(
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
                        runCatching { BookshelfLayout.valueOf(opt.id) }.getOrNull()?.let { picked ->
                            scope.launch { appPrefs.setBookshelfLayout(picked) }
                        }
                    },
                    position = SettingGroupPosition.First,
                )
                // 每行几本只对网格有意义：切到列表就收起
                AnimatedVisibility(
                    visible = bookshelfLayout == BookshelfLayout.GRID,
                    enter = expandEnter(),
                    exit = expandExit(),
                ) {
                    BookshelfColumnsBlock(
                        columns = bookshelfColumns,
                        onSelect = { picked -> scope.launch { appPrefs.setBookshelfColumns(picked) } },
                    )
                }
                SwitchRow(
                    title = "显示「发现」入口",
                    checked = exploreEnabled,
                    onCheckedChange = { on -> scope.launch { appPrefs.setExploreEnabled(on) } },
                    position = SettingGroupPosition.Middle,
                )
                // 开着也要有开着的订阅源才出现：说明里写清楚，不然开了开关书架上没有，像是坏了
                SwitchRow(
                    title = "显示「订阅」入口",
                    subtitle = "有开启的订阅源时，书架顶栏出现订阅按钮",
                    checked = rssEntryEnabled,
                    onCheckedChange = { on -> scope.launch { appPrefs.setRssEntryEnabled(on) } },
                    position = SettingGroupPosition.Middle,
                )
                // 关掉只是书架上不再出那张提示卡；「设置 → 同步与存储 → 整理重复的书」照样能进去
                SwitchRow(
                    title = "提示重复的书",
                    subtitle = "同一本书在书架上有好几份时，书架顶上提示整理",
                    checked = duplicateHint,
                    onCheckedChange = { on -> scope.launch { appPrefs.setDuplicateHintEnabled(on) } },
                    position = SettingGroupPosition.Last,
                )
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }

    if (showIconPicker) {
        AppIconSheet(
            selected = appIcon,
            onSelect = { picked ->
                showIconPicker = false
                if (picked != appIcon) {
                    AppIconManager.apply(context, picked)
                    appIcon = picked
                    messages.emit("已换成「${picked.label}」，桌面图标稍后刷新")
                }
            },
            onDismiss = { showIconPicker = false },
        )
    }
}

/**
 * 「每行本数」：标题 + 说明 + 3 本 / 4 本 / 5 本的分段。只有三档、固定不变，正是 [SegmentedChoice] 的用法，
 * 点一下就换，不用再弹列表。
 *
 * 标题和说明照 Miuix 设置行（BasicComponent）的字号写，上下 16dp 也同那些行 —— 夹在一串设置行中间，
 * 这一块的标题要和上下几行的标题看着是一排的。说明里点出书架上能捏合：手势没人告诉就没人知道。
 */
@Composable
private fun BookshelfColumnsBlock(columns: Int, onSelect: (Int) -> Unit) {
    SettingBlock(Modifier.padding(vertical = Dimens.gapXS)) {
        Column {
            Text(
                "每行本数",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "书架上双指捏合也能换；平板、横屏会多排几本",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        SegmentedChoice(
            options = ShelfColumns.OPTIONS.map { "$it 本" },
            selectedIndex = ShelfColumns.OPTIONS.indexOf(columns),
            onSelect = { onSelect(ShelfColumns.OPTIONS[it]) },
        )
    }
}
