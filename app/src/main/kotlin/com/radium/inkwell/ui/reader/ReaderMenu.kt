package com.radium.inkwell.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderMenu(
    state: ReaderUiState,
    onExit: () -> Unit,
    onGotoChapter: (Int) -> Unit,
    onSeekPercent: (Float) -> Unit,
    onUpdateSettings: (ReaderSettings) -> Unit,
    onSearchSources: () -> Unit,
    onApplySource: (com.radium.inkwell.core.source.SearchResult) -> Unit,
    onDismissSourcePanel: () -> Unit,
    onToggleCheckAuthor: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 菜单栏跟随阅读主题的纸张色。从前用 MaterialTheme.surface（白色），
    // 而正文是米色/夜间色 —— 白条压在纸上非常割裂。
    val theme = state.settings.theme
    val barColor = Color(theme.background)
    val barContent = Color(theme.textColor)

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Surface(color = barColor, contentColor = barContent, shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出阅读")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        // 次要文字也跟主题走：M3 的灰色在夜间纸张上会糊掉
                        color = barContent.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 中央区域：轻蒙层，把菜单读成「浮在正文之上的一层」，点击关闭
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(onClick = onDismiss)
        )

        // 底栏
        Surface(color = barColor, contentColor = barContent, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)) {
                // 章节进度
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onGotoChapter(state.chapterIndex - 1) },
                        enabled = state.chapterIndex > 0,
                    ) { Text("上一章") }
                    Slider(
                        value = if (state.pageCount <= 1) 0f else state.pageInChapter.toFloat() / (state.pageCount - 1),
                        onValueChange = onSeekPercent,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    TextButton(
                        onClick = { onGotoChapter(state.chapterIndex + 1) },
                        enabled = state.chapterIndex + 1 < state.chapterCount,
                    ) { Text("下一章") }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = { showToc = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Text(" 目录")
                    }
                    if (state.isNetBook) {
                        TextButton(onClick = onSearchSources) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                            Text(" 换源")
                        }
                    }
                    TextButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Text(" 设置")
                    }
                }
            }
        }
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            TocList(
                toc = state.toc,
                current = state.chapterIndex,
                // 目录跳章后收起整个菜单；上一章/下一章则留着菜单，方便连着翻
                onSelect = { showToc = false; onGotoChapter(it); onDismiss() },
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            TypographyPanel(settings = state.settings, onUpdate = onUpdateSettings)
        }
    }

    state.sourceCandidates?.let { candidates ->
        ModalBottomSheet(onDismissRequest = onDismissSourcePanel) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("换源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    // 边搜边出，让用户看得见还在搜、搜了多少，而不是干等一个转圈
                    if (state.searchingSources) {
                        Text(
                            "搜索中 ${state.sourcesDone}/${state.sourcesTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 作者匹配开关：书源返回的作者字段太脏，卡死了就一个源都换不到；
                // 拨一下就地重筛已搜到的结果，不重新发请求
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggleCheckAuthor(!state.checkAuthor) }
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("匹配作者", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (state.checkAuthor) "只显示同一作者的书" else "只认书名，不看作者",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.checkAuthor, onCheckedChange = onToggleCheckAuthor)
                }
                when {
                    state.changingSource -> Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    state.searchingSources && candidates.isEmpty() -> Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    candidates.isEmpty() -> Text(
                        if (state.checkAuthor) {
                            "其他 ${state.sourcesTotal} 个书源都没有找到这本书。" +
                                "可以关掉上面的「匹配作者」再看看 —— 不少书源的作者字段是空的或带前缀。"
                        } else {
                            "其他 ${state.sourcesTotal} 个书源都没有找到这本书"
                        },
                        Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(candidates, key = { "${it.sourceId}|${it.bookUrl}" }) { c ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onApplySource(c) }
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                            ) {
                                Text("${c.title}  ${c.author ?: ""}", maxLines = 1)
                                Text(
                                    "${c.sourceId}  ${c.latestChapter ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TocList(toc: List<TocItem>, current: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(toc, query) {
        if (query.isBlank()) toc
        else toc.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    LaunchedEffect(Unit) {
        if (current > 3) listState.scrollToItem(current - 3)
    }

    Column {
        com.radium.inkwell.ui.components.SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索章节",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (filtered.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "没有匹配的章节",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 440.dp)) {
                items(filtered, key = { it.index }) { item ->
                    Text(
                        item.title,
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item.index) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        fontWeight = if (item.index == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (item.index == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------- 阅读设置面板 ----------

private val LINE_SPACING_OPTIONS = listOf(
    "紧凑" to 1.4f,
    "标准" to 1.6f,
    "宽松" to 1.9f,
)

@Composable
private fun TypographyPanel(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        // 亮度
        SectionLabel("亮度")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.DarkMode, contentDescription = null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = settings.brightnessOverride ?: 0.5f,
                onValueChange = { v ->
                    onUpdate(settings.copy(brightnessOverride = v.coerceIn(0.01f, 1f)))
                },
                enabled = settings.brightnessOverride != null,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Icon(
                Icons.Default.LightMode, contentDescription = null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(12)
            FilterChip(
                selected = settings.brightnessOverride == null,
                onClick = {
                    onUpdate(
                        settings.copy(
                            brightnessOverride = if (settings.brightnessOverride == null) 0.5f else null,
                        )
                    )
                },
                label = { Text("系统") },
            )
        }

        // 字号
        SectionLabel("字号")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { if (settings.fontSizeSp > 12f) onUpdate(settings.copy(fontSizeSp = settings.fontSizeSp - 1)) },
                modifier = Modifier.weight(1f),
            ) { Text("A−", style = MaterialTheme.typography.titleMedium) }
            Text(
                "${settings.fontSizeSp.toInt()}",
                Modifier.width(72.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedButton(
                onClick = { if (settings.fontSizeSp < 32f) onUpdate(settings.copy(fontSizeSp = settings.fontSizeSp + 1)) },
                modifier = Modifier.weight(1f),
            ) { Text("A＋", style = MaterialTheme.typography.titleMedium) }
        }

        // 行距
        SectionLabel("行距")
        ChipRow(
            options = LINE_SPACING_OPTIONS.map { it.first },
            selectedIndex = LINE_SPACING_OPTIONS.indexOfFirst {
                kotlin.math.abs(it.second - settings.lineSpacingMult) < 0.05f
            },
            onSelect = { onUpdate(settings.copy(lineSpacingMult = LINE_SPACING_OPTIONS[it].second)) },
        )

        // 翻页方式
        SectionLabel("翻页方式")
        ChipRow(
            options = FlipAnimation.entries.map { it.label },
            selectedIndex = FlipAnimation.entries.indexOf(settings.flipAnimation),
            onSelect = { onUpdate(settings.copy(flipAnimation = FlipAnimation.entries[it])) },
        )

        // 字体
        SectionLabel("字体")
        ChipRow(
            options = ReaderSettings.FONT_PRESETS.map { it.second },
            selectedIndex = ReaderSettings.FONT_PRESETS.indexOfFirst { it.first == settings.fontId },
            onSelect = { onUpdate(settings.copy(fontId = ReaderSettings.FONT_PRESETS[it].first)) },
        )

        // 背景
        SectionLabel("背景")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReaderTheme.ALL.forEach { theme ->
                val selected = theme.id == settings.theme.id
                Box(
                    Modifier
                        .size(44.dp)
                        .background(Color(theme.background), CircleShape)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable { onUpdate(settings.copy(theme = theme)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = theme.id,
                            Modifier.size(20.dp),
                            tint = Color(theme.textColor),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChipRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun Spacer(width: Int) {
    androidx.compose.foundation.layout.Spacer(Modifier.width(width.dp))
}
