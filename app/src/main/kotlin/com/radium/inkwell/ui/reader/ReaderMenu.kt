package com.radium.inkwell.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
    LaunchedEffect(Unit) {
        if (current > 3) listState.scrollToItem(current - 3)
    }
    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
        items(toc, key = { it.index }) { item ->
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

@Composable
private fun TypographyPanel(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        StepperRow(
            label = "字号",
            value = "${settings.fontSizeSp.toInt()}",
            onMinus = { if (settings.fontSizeSp > 12f) onUpdate(settings.copy(fontSizeSp = settings.fontSizeSp - 1)) },
            onPlus = { if (settings.fontSizeSp < 32f) onUpdate(settings.copy(fontSizeSp = settings.fontSizeSp + 1)) },
        )
        StepperRow(
            label = "行距",
            value = String.format("%.1f", settings.lineSpacingMult),
            onMinus = { if (settings.lineSpacingMult > 1.2f) onUpdate(settings.copy(lineSpacingMult = settings.lineSpacingMult - 0.1f)) },
            onPlus = { if (settings.lineSpacingMult < 2.4f) onUpdate(settings.copy(lineSpacingMult = settings.lineSpacingMult + 0.1f)) },
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("翻页", Modifier.padding(end = 16.dp))
            FlipAnimation.entries.forEach { anim ->
                val selected = settings.flipAnimation == anim
                TextButton(onClick = { onUpdate(settings.copy(flipAnimation = anim)) }) {
                    Text(
                        anim.label,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("字体", Modifier.padding(end = 16.dp))
            ReaderSettings.FONT_PRESETS.forEach { (id, label) ->
                val selected = settings.fontId == id
                TextButton(onClick = { onUpdate(settings.copy(fontId = id)) }) {
                    Text(
                        label,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("主题", Modifier.padding(end = 16.dp))
            ReaderTheme.ALL.forEach { theme ->
                Box(
                    Modifier
                        .padding(end = 12.dp)
                        .background(Color(theme.background), MaterialTheme.shapes.small)
                        .clickable { onUpdate(settings.copy(theme = theme)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (theme.id == settings.theme.id) "✓" else "文",
                        color = Color(theme.textColor),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("亮度", Modifier.padding(end = 16.dp))
            Slider(
                value = settings.brightnessOverride ?: -0.05f,
                onValueChange = { v ->
                    onUpdate(settings.copy(brightnessOverride = if (v < 0f) null else v.coerceIn(0.01f, 1f)))
                },
                valueRange = -0.05f..1f,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (settings.brightnessOverride == null) "系统" else "${(settings.brightnessOverride!! * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−") }
        Text(value, Modifier.padding(horizontal = 12.dp))
        TextButton(onClick = onPlus) { Text("＋") }
    }
}
