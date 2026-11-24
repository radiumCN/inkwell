package com.radium.inkwell.ui.stats

import android.app.DatePickerDialog
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.shareExportFile
import com.radium.inkwell.ui.components.CoverGlassCard
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.groupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsCardColor
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.ChronoUnit
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.MiuixIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    val latestRead by viewModel.latestRead.collectAsStateWithLifecycle()
    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val messages = remember { MessageBus() }
    var exporting by remember { mutableStateOf(false) }
    CollectMessages(messages, snackbar)
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            AppTopBar("阅读记录", topBarScroll, onBack = onBack, containerColor = pageColor) {
                val snapshot = state
                if (snapshot != null && !snapshot.rangeSummary.isEmpty) {
                    AppTextButton("分享报告", enabled = !exporting, onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val file = exportReadingReport(context, snapshot)
                                shareExportFile(context, file, "分享阅读报告", "image/png")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                messages.emit("无法分享阅读报告，请重试")
                            } finally {
                                exporting = false
                            }
                        }
                    })
                }
            }
        },
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
            else -> StatsContent(ui, latestRead, padding, viewModel::selectRange)
        }
    }
}

@Composable
private fun StatsContent(
    ui: ReadingStatsUi,
    latestRead: LatestRead?,
    padding: PaddingValues,
    onSelectRange: (ReadingDateRange?) -> Unit,
) {
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
        TodayCard(s, latestRead, Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS))
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

        SectionHeader("每日", trailing = { ReadingRangeSelect(ui.today, ui.range, onSelectRange) })
        val range = ui.range
        if (range != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
            ) {
                StatTile("区间时长", formatDuration(ui.rangeSummary.totalMillis), Modifier.weight(1f))
                StatTile("活跃天数", "${ui.rangeSummary.activeDays} 天", Modifier.weight(1f))
            }
        }
        if (ui.rangeSummary.isEmpty) {
            SettingFootnote("所选日期没有阅读记录")
        } else {
            SettingGroup {
                ReadingHeatmap(
                    daily = ui.rangeSummary.daily,
                    today = range?.end ?: ui.today,
                    from = range?.start,
                    modifier = Modifier.padding(Dimens.gapL),
                )
            }
        }

        if (ui.topBooks.isNotEmpty()) {
            SectionHeader(if (range == null) "今年读得最多" else "区间内读得最多")
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

@Composable
private fun ReadingRangeSelect(today: LocalDate, range: ReadingDateRange?, onSelect: (ReadingDateRange?) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppTextButton(
            text = range?.let { "${it.start} 至 ${it.end}" } ?: "选择日期",
            onClick = { expanded = true },
        )
        AppDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = listOf(7, 30, 90).map { days ->
                AppMenuItem("近 $days 天", onClick = { onSelect(ReadingDateRange(today.minusDays(days - 1L), today)) })
            } + listOf(
                AppMenuItem("自定义日期", onClick = {
                    pickReadingDate(context, "起始日期", range?.start ?: today.minusDays(6), maximum = today) { start ->
                        val end = (range?.end ?: today).coerceAtLeast(start)
                        pickReadingDate(context, "结束日期", end, minimum = start, maximum = today) {
                            onSelect(ReadingDateRange(start, it))
                        }
                    }
                }),
                AppMenuItem("恢复默认", onClick = { onSelect(null) }),
            ),
        )
    }
}

private fun pickReadingDate(
    context: Context,
    title: String,
    initial: LocalDate,
    minimum: LocalDate? = null,
    maximum: LocalDate,
    onSelected: (LocalDate) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    DatePickerDialog(
        context,
        { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth,
    ).apply {
        setTitle(title)
        minimum?.let { datePicker.minDate = it.atStartOfDay(zone).toInstant().toEpochMilli() }
        datePicker.maxDate = maximum.atStartOfDay(zone).toInstant().toEpochMilli()
    }.show()
}

/**
 * 今天这一张：唯一一块带颜色的面，跟书架上的「继续阅读」、设置页概览卡同一种 —— 最近在读那本书的封面毛玻璃
 * （[CoverGlassCard]；[latestRead] 不含隐藏的书，理由同 `ReadingStatsViewModel.topBooks`；null = 还在查）。
 * 页面上最先该看到的数字只有这一个。文字统一一种颜色（玻璃给的 LocalContentColor），层级靠字号拉开。
 */
@Composable
private fun TodayCard(s: ReadingSummary, latestRead: LatestRead?, modifier: Modifier) {
    CoverGlassCard(
        coverTitle = latestRead?.book?.title,
        coverModel = latestRead?.book?.coverPath,
        modifier = modifier.fillMaxWidth(),
        pending = latestRead == null,
    ) {
        val content = LocalContentColor.current
        Column(
            Modifier
                .fillMaxWidth()
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
private fun ReadingHeatmap(daily: Map<Long, Long>, today: LocalDate, from: LocalDate?, modifier: Modifier) {
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
            val startOfWeek = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
            val lastMonday = today.with(startOfWeek)
            val firstMonday = from?.with(startOfWeek) ?: lastMonday.minusWeeks(weeks - 1L)
            val weekCount = ChronoUnit.WEEKS.between(firstMonday, lastMonday).toInt() + 1
            val startDay = (from ?: firstMonday).toEpochDay()
            val endDay = today.toEpochDay()
            val readDays = daily.count { (day, time) -> day in startDay..endDay && heatLevel(time) > 0 }
            val scroller = androidx.compose.runtime.key(firstMonday, lastMonday, weeks) {
                rememberLazyListState(initialFirstVisibleItemIndex = (weekCount - weeks).coerceAtLeast(0))
            }
            Column(
                Modifier.semantics {
                    contentDescription = if (from == null) "近 $weeks 周有 $readDays 天在读" else "所选日期有 $readDays 天在读"
                },
            ) {
                LazyRow(state = scroller, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    items(weekCount) { w ->
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            for (d in 0 until 7) {
                                val date = firstMonday.plusWeeks(w.toLong()).plusDays(d.toLong())
                                val color = if (date.isAfter(today) || (from != null && date.isBefore(from))) Color.Transparent
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

/** 格子的目标边长：窄屏铺十几周、平板铺半年多，都差不多是这个大小 */
private val HEAT_CELL_TARGET = Dimens.gapL
private const val MIN_WEEKS = 8
private const val MAX_WEEKS = 53
