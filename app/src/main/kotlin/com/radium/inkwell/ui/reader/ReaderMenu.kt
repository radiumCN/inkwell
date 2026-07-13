package com.radium.inkwell.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
    onDismiss: () -> Unit,
) {
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出阅读")
                }
                Column(Modifier.weight(1f)) {
                    Text(state.bookTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        state.chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        // 中央空白区域点击关闭菜单
        Box(Modifier.weight(1f).fillMaxWidth().clickable(onClick = onDismiss))

        // 底栏
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
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
                onSelect = { showToc = false; onGotoChapter(it) },
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
                Text(
                    "换源",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
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
                        "其他书源没有找到这本书",
                        Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索章节") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
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
