package com.radium.inkwell.ui.stats

import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import androidx.compose.foundation.layout.PaddingValues
import com.radium.inkwell.ui.components.SettingFootnote
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsCardColor
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.MiuixIcons

/**
 * 阅读记录：今天读了多久、连续几天、本周/今年/累计，近几个月的每日热力图，今年读得最多的书。
 *
 * 只给看，不打分、不设目标、不发提醒 —— 这是一本账，不是一个要你每天打卡的任务。
 */
@Composable
fun ReadingStatsScreen(
    onBack: () -> Unit,
    viewModel: ReadingStatsViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("阅读记录", topBarScroll, onBack = onBack, containerColor = pageColor) },
    ) { padding ->
        val ui = state
        when {
            ui == null -> LoadingState(Modifier.padding(padding))
            ui.summary.isEmpty -> EmptyState(
                icon = MiuixIcons.Notes,
                title = "还没有阅读记录",
                hint = "翻开一本书读几页，时长就会记在这里；配了 WebDAV 的话，各台设备的时长会合在一起",
                modifier = Modifier.padding(padding),
            )
            else -> StatsContent(ui, padding)
        }
    }
}

@Composable
private fun StatsContent(ui: ReadingStatsUi, padding: PaddingValues) {
    val s = ui.summary
    Column(
        Modifier
            .fillMaxSize()
            // 顶栏、导航栏的高度都垫在滚动内容首尾：内容能滚到玻璃顶栏和手势条下面（见 AppScaffold）
            .padding(padding.horizontalOnly())
            .pageScroll()
            .verticalScroll(rememberScrollState(), overscrollEffect = null)
            .padding(top = padding.calculateTopPadding(), bottom = Dimens.gapXL + padding.calculateBottomPadding()),
    ) {
        TodayCard(s, Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.listHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            StatTile("本周", formatDuration(s.weekMillis), Modifier.weight(1f))
            StatTile("今年", formatDuration(s.yearMillis), Modifier.weight(1f))
            StatTile("累计", formatDuration(s.totalMillis), Modifier.weight(1f))
        }

        SectionHeader("每日")
        SettingGroup {
            ReadingHeatmap(
                daily = s.daily,
                today = ui.today,
                modifier = Modifier.padding(Dimens.gapL),
            )
        }

        if (ui.topBooks.isNotEmpty()) {
            SectionHeader("今年读得最多")
            SettingGroup {
                ui.topBooks.forEachIndexed { i, book ->
                    SettingRow(
                        title = book.title,
                        subtitle = book.author.takeIf { it.isNotBlank() },
                        value = formatDuration(book.millis),
                        position = groupPosition(i, ui.topBooks.size),
                    )
                }
            }
        }

        SettingFootnote(
            "只算翻页之间的时间：在一页上停超过 3 分钟，多出来的不算（多半是人走开了）。" +
                "配了 WebDAV 的话，各台设备的时长随同步合在一起",
            modifier = Modifier.padding(top = Dimens.gapS),
        )
    }
}

/**
 * 今天这一张：唯一一块带颜色的面，跟书架上的「继续阅读」同一种 primaryContainer 底 ——
 * 页面上最先该看到的数字只有这一个。文字统一 onPrimaryContainer，层级靠字号拉开。
 */
@Composable
private fun TodayCard(s: ReadingSummary, modifier: Modifier) {
    val content = MiuixTheme.colorScheme.onPrimaryContainer
    Column(
        modifier
            .fillMaxWidth()
            .squircleSurface(MiuixTheme.colorScheme.primaryContainer, AppShapes.radiusLarge)
            .semantics(mergeDescendants = true) {}
            .padding(Dimens.gapXL),
    ) {
        Text("今天读了", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = content)
        Text(
            formatDuration(s.todayMillis),
            style = MiuixTheme.textStyles.title1,
            color = content,
            modifier = Modifier.padding(vertical = Dimens.gapXS),
        )
        Text(
            buildString {
                append(if (s.streakDays > 0) "连续读了 ${s.streakDays} 天" else "今天还没开始")
                append(" · 共 ${s.activeDays} 天在读")
            },
            style = MiuixTheme.textStyles.body2,
            color = content,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        cornerRadius = AppShapes.radiusLarge,
        colors = CardDefaults.defaultColors(color = settingsCardColor()),
    ) {
        Column(Modifier.padding(horizontal = Dimens.gapM, vertical = Dimens.gapM)) {
            Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(
                value,
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Dimens.gapXS),
            )
        }
    }
}

/**
 * 每日热力图：一列一周（周一在上），最右一列是本周，往左铺满卡片宽度能放下的周数。
 * 今天之后的格子留空；颜色深浅档见 [heatLevel]（按分钟定死，不按最大值归一化）。
 *
 * 读屏不逐格念（一百多格），整张图给一句概括。
 */
@Composable
private fun ReadingHeatmap(daily: Map<Long, Long>, today: LocalDate, modifier: Modifier) {
    val scheme = MiuixTheme.colorScheme
    val levels = listOf(
        scheme.surfaceContainerHighest,
        scheme.primary.copy(alpha = 0.30f),
        scheme.primary.copy(alpha = 0.50f),
        scheme.primary.copy(alpha = 0.75f),
        scheme.primary,
    )
    val cellShape = AppShapes.extraSmall
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = Dimens.gapXS
            val weeks = ((maxWidth + gap) / (HEAT_CELL_TARGET + gap)).toInt().coerceIn(MIN_WEEKS, MAX_WEEKS)
            val cell = (maxWidth - gap * (weeks - 1)) / weeks
            val firstMonday = today
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(weeks - 1L)
            val readDays = (0 until weeks * 7).count { offset ->
                val date = firstMonday.plusDays(offset.toLong())
                !date.isAfter(today) && heatLevel(daily[date.toEpochDay()] ?: 0L) > 0
            }
            Column(
                Modifier.clearAndSetSemantics {
                    contentDescription = "近 $weeks 周里，有 $readDays 天在读"
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (w in 0 until weeks) {
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            for (d in 0 until 7) {
                                val date = firstMonday.plusWeeks(w.toLong()).plusDays(d.toLong())
                                val color = if (date.isAfter(today)) Color.Transparent
                                else levels[heatLevel(daily[date.toEpochDay()] ?: 0L)]
                                Box(
                                    Modifier
                                        .size(cell)
                                        .clip(cellShape)
                                        .background(color),
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.gapM),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${firstMonday.monthValue} 月 ${firstMonday.dayOfMonth} 日起",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    Text("少", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                    levels.forEach { c ->
                        Spacer(Modifier.width(Dimens.gapXS))
                        Box(
                            Modifier
                                .size(Dimens.gapM)
                                .clip(cellShape)
                                .background(c),
                        )
                    }
                    Spacer(Modifier.width(Dimens.gapXS))
                    Text("多", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

private fun groupPosition(index: Int, size: Int): SettingGroupPosition = when {
    size == 1 -> SettingGroupPosition.Alone
    index == 0 -> SettingGroupPosition.First
    index == size - 1 -> SettingGroupPosition.Last
    else -> SettingGroupPosition.Middle
}

/** 格子的目标边长：窄屏铺十几周、平板铺半年多，都差不多是这个大小 */
private val HEAT_CELL_TARGET = Dimens.gapL
private const val MIN_WEEKS = 8
private const val MAX_WEEKS = 53
