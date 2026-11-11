package com.radium.inkwell.ui.rss

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.RssSourceEntity
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.radium.inkwell.ui.components.InlineTag

@Composable
fun RssSourceScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    /** 单网页源（阅读的 singleUrl）：点开是网页页，不是文章列表 */
    onOpenWebSource: (String) -> Unit,
    viewModel: RssSourceViewModel,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val webSourceIds by viewModel.webSourceIds.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)

    var showImportMenu by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RssSourceEntity?>(null) }
    // 长按哪个源，就从哪一行上弹出它的菜单（同书架长按一本书）
    var menuTarget by remember { mutableStateOf<String?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = {
            AppTopBar("订阅", topBarScroll, onBack = onBack, containerColor = pageColor, actions = {
                Box {
                    // 导入进行中（粘地址要联网抓 feed）换成转圈：从前这个状态 VM 里有、页面从没读过，点了像没点上
                    if (importing) {
                        // 转圈没有名字，读屏只会念「按钮，已停用」：补一句它在干什么
                        AppIconButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.semantics { contentDescription = "正在导入订阅源" },
                        ) {
                            AppLoadingIndicator(size = Dimens.buttonSpinner)
                        }
                    } else {
                        AppIconButton(onClick = { showImportMenu = true }) {
                            Icon(MiuixIcons.Add, contentDescription = "添加订阅源")
                        }
                    }
                    AppDropdownMenu(
                        expanded = showImportMenu,
                        onDismissRequest = { showImportMenu = false },
                        items = listOf(
                            AppMenuItem("粘贴地址或订阅源", onClick = {
                                showImportMenu = false
                                importText = ""
                                showUrlImport = true
                            }),
                            AppMenuItem("从剪贴板导入", onClick = {
                                showImportMenu = false
                                viewModel.importFromClipboard()
                            }),
                            AppMenuItem("从文件导入", onClick = {
                                showImportMenu = false
                                fileLauncher.launch(
                                    arrayOf("application/json", "text/plain", "application/octet-stream")
                                )
                            }),
                        ),
                    )
                }
            })
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // null = 库还没读回来：先空着，别闪一下「还没有订阅源」
        val list = sources ?: return@AppScaffold
        if (list.isEmpty() && importing) {
            LoadingState(Modifier.padding(padding), label = "正在导入订阅源…")
        } else if (list.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.RssFeed,
                title = "还没有订阅源",
                hint = "直接粘一个 RSS/Atom 地址就能订阅，也支持导入 Legado 订阅源",
                actionLabel = "添加订阅源",
                onAction = { importText = ""; showUrlImport = true },
                modifier = Modifier.padding(padding),
            )
        } else {
            // 只让开两侧：顶栏高度垫在列表开头（列表滚到毛玻璃顶栏下面），导航栏高度垫在末尾（见 AppScaffold）
            LazyColumn(
                Modifier.fillMaxSize().padding(padding.horizontalOnly()).pageScroll(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    bottom = padding.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                // 一张卡装下整个列表（HyperOS 的灰底白卡），行与行之间不留缝。
                // 行尾只有开关（HyperOS 的列表行尾就是一个开关或一个 ›）；删除收进长按菜单 —— 从前行尾是
                // 「删除图标 + 开关」，48dp 的垃圾桶紧挨着开关，挤，也容易手滑点到
                itemsIndexed(list, key = { _, it -> it.id }) { index, source ->
                    // 菜单和行放进同一个 Box，从这一行上弹出来（见 AppDropdownMenu）
                    val isWeb = source.id in webSourceIds
                    Box(Modifier.groupedCardItem(index, list.size)) {
                        ContentListItem(
                            onClick = { if (isWeb) onOpenWebSource(source.id) else onOpenSource(source.id) },
                            onLongClick = { menuTarget = source.id },
                            enabled = source.enabled,
                            trailingContent = {
                                Switch(
                                    checked = source.enabled,
                                    onCheckedChange = { viewModel.setEnabled(source.id, it) },
                                    // 行尾开关自己是一个焦点，得有名字：只念「开关，已开启」听不出开的是哪个源
                                    modifier = Modifier.semantics {
                                        contentDescription = "启用「${source.name}」"
                                    },
                                )
                            },
                            supportingContent = {
                                Text(
                                    source.id,
                                    style = ContentListDefaults.supportingStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            content = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        source.name,
                                        style = ContentListDefaults.titleStyle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // 点开是网页、不是文章列表：灰标签，和来源管理的「仅发现」一个样子
                                    if (isWeb) InlineTag("网页", neutral = true)
                                }
                            },
                        )
                        AppDropdownMenu(
                            expanded = menuTarget == source.id,
                            onDismissRequest = { menuTarget = null },
                            items = listOf(
                                AppMenuItem("删除", onClick = {
                                    menuTarget = null
                                    deleteTarget = source
                                }),
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showUrlImport) {
        fun dismissUrlImport() {
            showUrlImport = false
            importText = ""
        }
        AppAlertDialog(
            onDismissRequest = { dismissUrlImport() },
            title = "添加订阅源",
            confirmText = "添加",
            onConfirm = {
                val text = importText
                dismissUrlImport()
                viewModel.importFromText(text)
            },
            confirmEnabled = importText.isNotBlank(),
            content = {
                CompactTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = "地址或订阅源 JSON",
                )
                Text(
                    "RSS/Atom 地址、源仓库里订阅源合集的地址、阅读的一键导入链接，或者整段订阅源 JSON 都行。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
        )
    }

    deleteTarget?.let { source ->
        AppAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除订阅源",
            text = "确定删除「${source.name}」吗？",
            confirmText = "删除",
            onConfirm = {
                viewModel.delete(source.id)
                deleteTarget = null
            },
        )
    }
}
