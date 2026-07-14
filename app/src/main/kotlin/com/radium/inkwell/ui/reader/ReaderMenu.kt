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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import com.radium.inkwell.reader.api.ChineseConvert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import com.radium.inkwell.ui.components.SlimSlider
import androidx.compose.animation.AnimatedVisibility
import com.radium.inkwell.ui.components.bottomBarEnter
import com.radium.inkwell.ui.components.bottomBarExit
import com.radium.inkwell.ui.components.scrimEnter
import com.radium.inkwell.ui.components.scrimExit
import com.radium.inkwell.ui.components.topBarEnter
import com.radium.inkwell.ui.components.topBarExit
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import com.radium.inkwell.ui.components.SwitchRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderMenu(
    visible: Boolean,
    state: ReaderUiState,
    onExit: () -> Unit,
    onGotoChapter: (Int) -> Unit,
    onSeekPercent: (Float) -> Unit,
    onUpdateSettings: (ReaderSettings) -> Unit,
    onSearchSources: () -> Unit,
    onToggleAutoFlip: () -> Unit,
    onOpenSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 菜单现在常驻组合（为了能播退场动画），所以关菜单时得把面板一起收掉 ——
    // 否则下次呼出菜单，上回没关的目录会自己冒出来
    LaunchedEffect(visible) {
        if (!visible) {
            showToc = false
            showSettings = false
        }
    }

    // 菜单栏跟随阅读主题的纸张色。从前用 MaterialTheme.surface（白色），
    // 而正文是米色/夜间色 —— 白条压在纸上非常割裂。
    val theme = state.settings.theme
    val barColor = Color(theme.background)
    val barContent = Color(theme.textColor)

    // 菜单从前是硬出硬消（直接 if (visible) 组合/移除）。现在顶栏从上滑入、底栏从下滑入、
    // 中间蒙层淡入 —— 让人看得出这是「盖上来的一层」，而不是页面突然换了个样子。
    // 退场比入场快（Motion.EXIT_MS）：用户已经决定关掉了，界面还慢悠悠淡出会像没反应。
    AnimatedVisibility(
        visible = visible,
        enter = scrimEnter(),
        exit = scrimExit(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏从上滑入，底栏从下滑入 —— 用 animateEnterExit 而不是再套一层
            // AnimatedVisibility：套进来的那层 visible 恒为 true，根本不会播。
            Surface(
                color = barColor,
                contentColor = barContent,
                shadowElevation = 4.dp,
                modifier = Modifier.animateEnterExit(topBarEnter(), topBarExit()),
            ) {
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
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "全书搜索", tint = barContent)
                    }
                }
            }

            // 中央区域：轻蒙层，把菜单读成「浮在正文之上的一层」，点击关闭
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.18f))
                    // 无涟漪：这是块半屏的空白，点它只是"关掉菜单"。带 ripple 的话
                    // 一点下去整个屏幕中央泛起一道全屏涟漪，非常吓人
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
            )

            // 底栏
            Surface(
                color = barColor,
                contentColor = barContent,
                shadowElevation = 8.dp,
                modifier = Modifier.animateEnterExit(bottomBarEnter(), bottomBarExit()),
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)) {
                    // 章节进度
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onGotoChapter(state.chapterIndex - 1) },
                            enabled = state.chapterIndex > 0,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("上一章", style = MaterialTheme.typography.labelLarge) }

                        // 细滑块 + 页码。M3 默认的 Slider thumb 是一根竖条，把这条本该最不起眼的
                        // 进度撑得比按钮还厚 —— 章节进度是"瞄一眼"的东西，不该抢地方
                        SlimSlider(
                            value = if (state.pageCount <= 1) 0f
                            else state.pageInChapter.toFloat() / (state.pageCount - 1),
                            onValueChange = onSeekPercent,
                            enabled = state.pageCount > 1,
                            activeColor = barContent,
                            inactiveColor = barContent.copy(alpha = 0.2f),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(
                            "${state.pageInChapter + 1}/${state.pageCount.coerceAtLeast(1)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = barContent.copy(alpha = 0.65f),
                        )

                        TextButton(
                            onClick = { onGotoChapter(state.chapterIndex + 1) },
                            enabled = state.chapterIndex + 1 < state.chapterCount,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("下一章", style = MaterialTheme.typography.labelLarge) }
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
                        // 滚动模式下没有"页"，自动翻页无从谈起
                        if (state.settings.flipAnimation != FlipAnimation.SCROLL) {
                            TextButton(onClick = onToggleAutoFlip) {
                                Icon(
                                    if (state.autoFlipping) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                )
                                Text(if (state.autoFlipping) " 停止" else " 自动")
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
                            .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
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

/**
 * 阅读设置面板。
 *
 * 分三页，而不是把九组「标签 + chip 行」直着堆下去 —— 那样面板长得要滚半天，
 * 而且每组长得都一样（一个小标题压一排 chip），眼睛根本抓不住重点。
 * 分页之后每页只有三四组，一屏就看完了。
 *
 * 分法按「改的频率」而不是按「技术类别」：
 * - 排版：字号、行距、字体、背景、亮度 —— 最常调的
 * - 翻页：翻页方式、自动翻页间隔、音量键翻页
 * - 更多：设一次基本不再动的（简繁、预加载、屏幕常亮）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypographyPanel(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        TabRow(selectedTabIndex = tab) {
            SETTINGS_TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding)) {
            when (tab) {
                0 -> LayoutTab(settings, onUpdate)
                1 -> FlipTab(settings, onUpdate)
                else -> MoreTab(settings, onUpdate)
            }
        }
    }
}

@Composable
private fun LayoutTab(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    // 字号：加减按钮 + 当前值，一行搞定
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

    SectionLabel("行距")
    ChipRow(
        options = LINE_SPACING_OPTIONS.map { it.first },
        selectedIndex = LINE_SPACING_OPTIONS.indexOfFirst {
            kotlin.math.abs(it.second - settings.lineSpacingMult) < 0.05f
        },
        onSelect = { onUpdate(settings.copy(lineSpacingMult = LINE_SPACING_OPTIONS[it].second)) },
    )

    SectionLabel("字体")
    ChipRow(
        options = ReaderSettings.FONT_PRESETS.map { it.second },
        selectedIndex = ReaderSettings.FONT_PRESETS.indexOfFirst { it.first == settings.fontId },
        onSelect = { onUpdate(settings.copy(fontId = ReaderSettings.FONT_PRESETS[it].first)) },
    )

    SectionLabel("背景")
    ThemeSwatches(settings, onUpdate)

    SectionLabel("亮度")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.DarkMode, contentDescription = null,
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SlimSlider(
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
}

@Composable
private fun FlipTab(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    SectionLabel("翻页方式")
    ChipRow(
        options = FlipAnimation.entries.map { it.label },
        selectedIndex = FlipAnimation.entries.indexOf(settings.flipAnimation),
        onSelect = { onUpdate(settings.copy(flipAnimation = FlipAnimation.entries[it])) },
    )

    // 滚动模式没有"页"，这两项无从谈起 —— 与其留在那儿让人调了没反应，不如藏起来
    if (settings.flipAnimation != FlipAnimation.SCROLL) {
        SectionLabel("自动翻页间隔")
        ChipRow(
            options = AUTO_FLIP_OPTIONS.map { "${it}s" },
            selectedIndex = AUTO_FLIP_OPTIONS.indexOf(settings.autoFlipSeconds),
            onSelect = { onUpdate(settings.copy(autoFlipSeconds = AUTO_FLIP_OPTIONS[it])) },
        )

        SwitchRow(
            title = "音量键翻页",
            checked = settings.volumeKeyFlip,
            onCheckedChange = { onUpdate(settings.copy(volumeKeyFlip = it)) },
        )
    }
}

@Composable
private fun MoreTab(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    SectionLabel("简繁转换")
    ChipRow(
        options = ChineseConvert.entries.map { it.label },
        selectedIndex = ChineseConvert.entries.indexOf(settings.chineseConvert),
        onSelect = { onUpdate(settings.copy(chineseConvert = ChineseConvert.entries[it])) },
    )

    SectionLabel("预加载章节")
    ChipRow(
        options = PRELOAD_OPTIONS.map { if (it == 0) "关闭" else "$it 章" },
        selectedIndex = PRELOAD_OPTIONS.indexOf(settings.preloadChapters),
        onSelect = { onUpdate(settings.copy(preloadChapters = PRELOAD_OPTIONS[it])) },
    )

    // 这两个设置一直存在于 ReaderSettings 里，却**从来没有 UI 能改**
    SwitchRow(
        title = "阅读时保持屏幕常亮",
        checked = settings.keepScreenOn,
        onCheckedChange = { onUpdate(settings.copy(keepScreenOn = it)) },
    )
}

@Composable
private fun ThemeSwatches(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapL),
    ) {
        ReaderTheme.ALL.forEach { theme ->
            val selected = theme.id == settings.theme.id
            Box(
                Modifier
                    // 触控目标 48dp；clip 必须在 clickable 之前 ——
                    // 否则涟漪是方的，会溢出这个圆形色块的边界
                    .size(Dimens.touchTarget)
                    .clip(CircleShape)
                    .background(Color(theme.background))
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

private val SETTINGS_TABS = listOf("排版", "翻页", "更多")

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
private fun Spacer(width: Int) {
    androidx.compose.foundation.layout.Spacer(Modifier.width(width.dp))
}

/** 自动翻页可选间隔（秒）。太快来不及读，太慢不如自己翻 */
private val AUTO_FLIP_OPTIONS = listOf(5, 10, 15, 20, 30, 45, 60)

/** 预取多少章正文。给多了只是白耗流量：读者不会一口气跳着读十章 */
private val PRELOAD_OPTIONS = listOf(0, 1, 3, 5, 10)
