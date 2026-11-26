package com.radium.inkwell.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.asParagraph
import com.radium.inkwell.core.model.charLength
import com.radium.inkwell.core.model.textOrNull
import com.radium.inkwell.reader.api.ReadPosition
import com.radium.inkwell.reader.paginate.PageSpec
import com.radium.inkwell.reader.render.TextSelection
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.SegmentedChoice
import com.radium.inkwell.ui.components.SquircleCorners
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.squircleSurface
import com.radium.inkwell.ui.theme.AppShapes
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val MAX_READER_TEXT_MATCHES = 500
internal const val MAX_READER_TEXT_QUERY = 120

data class ReaderTextMatch(
    val position: ReadPosition,
    val chapterTitle: String,
    val selection: TextSelection,
    val excerpt: String,
    val excerptMatchStart: Int,
)

data class ReaderTextSearchState(
    val total: Int = 0,
    val scanned: Int = 0,
    val missing: Int = 0,
    val failed: Int = 0,
    val matches: List<ReaderTextMatch> = emptyList(),
    val active: Boolean = false,
    val limited: Boolean = false,
    val invalidated: Boolean = false,
)

internal data class ReaderTextSearchDestination(
    val match: ReaderTextMatch,
    val charOffset: Int,
    val matchNumber: Int,
    val totalMatches: Int,
) {
    fun isOnPage(page: PageSpec?): Boolean = page != null && page.chapterIndex == match.position.chapterIndex &&
        charOffset in page.startCharOffset until page.endCharOffset
}

internal fun readerTextMatches(
    content: ChapterContent,
    chapterIndex: Int,
    title: String,
    query: String,
    limit: Int,
): List<ReaderTextMatch> {
    if (query.isEmpty() || limit <= 0) return emptyList()
    val matches = ArrayList<ReaderTextMatch>()
    var offset = title.length
    content.elements.forEachIndexed { index, element ->
        val paragraph = element.asParagraph()
        val text = paragraph.textOrNull
        if (text != null) {
            var start = text.indexOf(query, ignoreCase = true)
            while (start >= 0) {
                val end = start + query.length
                val left = (start - 24).coerceAtLeast(0)
                val right = (end + 40).coerceAtMost(text.length)
                val excerpt = (if (left > 0) "…" else "") + text.substring(left, right) +
                    if (right < text.length) "…" else ""
                matches += ReaderTextMatch(
                    ReadPosition(chapterIndex, offset + start),
                    title,
                    TextSelection(index + 1, start, end, text.substring(start, end)),
                    excerpt,
                    start - left + if (left > 0) 1 else 0,
                )
                if (matches.size == limit) return matches
                start = text.indexOf(query, end, ignoreCase = true)
            }
        }
        offset += paragraph.charLength
    }
    return matches
}

@Composable
internal fun ReaderTextSearchSheet(
    visible: Boolean,
    isNetBook: Boolean,
    viewModel: ReaderViewModel,
    onDismiss: () -> Unit,
    onOpenMatch: (ReaderTextMatch) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var wholeBook by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    if (!visible) return
    val result by viewModel.textSearch.collectAsStateWithLifecycle()
    val download by viewModel.textSearchDownload.collectAsStateWithLifecycle(null)
    LaunchedEffect(isNetBook) {
        if (isNetBook) viewModel.restoreTextSearchDownload()
    }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit: () -> Unit = {
        keyboard?.hide()
        focus.clearFocus()
        viewModel.findText(query, wholeBook)
        scope.launch { listState.scrollToItem(0) }
    }
    AppBottomSheet(onDismissRequest = onDismiss, title = "查找正文") {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = cardMargin(), vertical = Dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = query,
                    onValueChange = {
                        query = it.take(MAX_READER_TEXT_QUERY)
                        viewModel.clearTextSearch()
                    },
                    placeholder = "输入要查找的文字",
                    containerColor = readerCardColor(),
                    onSearch = submit,
                    modifier = Modifier.weight(1f),
                )
                AppTextButton("查找", onClick = submit, enabled = query.isNotBlank())
            }
            SegmentedChoice(
                options = listOf("本章", if (isNetBook) "已缓存" else "整书"),
                selectedIndex = if (wholeBook) 1 else 0,
                onSelect = {
                    if (wholeBook != (it == 1)) {
                        wholeBook = it == 1
                        viewModel.clearTextSearch()
                    }
                },
            )
            val summary = when {
                result.invalidated -> "正文或目录已变化，请重新查找"
                result.total == 0 -> when {
                    !wholeBook -> "查找当前章节"
                    isNetBook -> "查找已缓存的章节"
                    else -> "查找整本书"
                }
                else -> buildString {
                    if (result.active) append("查找中 · ")
                    append("已查 ${result.scanned - result.missing - result.failed} / ${result.total} 章")
                    if (result.limited) append(" · 显示前 $MAX_READER_TEXT_MATCHES 处")
                    else append(" · ${result.matches.size} 处命中")
                    if (result.failed > 0) append(" · ${result.failed} 章读取失败")
                    if (!wholeBook && result.missing > 0) append(" · 本章未缓存")
                }
            }
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.semantics { if (!result.active) liveRegion = LiveRegionMode.Polite },
            )
            val scheme = MiuixTheme.colorScheme
            LazyColumn(
                Modifier.weight(1f, fill = false).heightIn(max = Dimens.sheetListMaxHeight)
                    .squircleSurface(readerCardColor(), SquircleCorners.all(AppShapes.radiusLarge)),
                state = listState,
            ) {
                itemsIndexed(
                    result.matches,
                    key = { _, match -> "${match.position.chapterIndex}:${match.position.charOffset}" },
                ) { index, match ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = Dimens.listHorizontal))
                    val excerpt = buildAnnotatedString {
                        val start = match.excerptMatchStart
                        val end = start + match.selection.text.length
                        append(match.excerpt.substring(0, start))
                        withStyle(SpanStyle(color = scheme.onPrimaryContainer, background = scheme.primaryContainer)) {
                            append(match.excerpt.substring(start, end))
                        }
                        append(match.excerpt.substring(end))
                    }
                    ContentListItem(onClick = { onOpenMatch(match) }) {
                        Column {
                            Text(match.chapterTitle, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            Text(excerpt, style = MiuixTheme.textStyles.body2)
                        }
                    }
                }
            }
            if (isNetBook && wholeBook) {
                val task = download
                if (task?.running == true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "正在缓存 ${task.done} / ${task.total} 章" +
                                if (task.failed > 0) " · ${task.failed} 章失败" else "",
                            Modifier.weight(1f),
                            style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary,
                        )
                        if (task.textOnly) AppTextButton("停止", onClick = viewModel::stopTextSearchDownload)
                    }
                } else if (!result.active && !result.limited && result.missing > 0) {
                    if (task?.textOnly == true) {
                        val status = task.stoppedBecause ?: if (task.failed > 0) "${task.failed} 章未缓存" else null
                        if (status != null) Text(status, style = MiuixTheme.textStyles.footnote1)
                    }
                    val resume = task?.textOnly == true && task.canResume
                    AppTextButton(
                        if (resume) "继续缓存正文" else "缓存剩余 ${result.missing} 章",
                        onClick = { viewModel.cacheTextSearchChapters(resume) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}
