package com.radium.inkwell.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.asParagraph
import com.radium.inkwell.core.model.charLength
import com.radium.inkwell.core.model.textOrNull
import com.radium.inkwell.reader.api.ReadPosition
import com.radium.inkwell.reader.render.TextSelection
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.SegmentedChoice
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val MAX_READER_TEXT_MATCHES = 500
internal const val MAX_READER_TEXT_QUERY = 120

data class ReaderTextMatch(
    val position: ReadPosition,
    val chapterTitle: String,
    val selection: TextSelection,
    val excerpt: String,
)

data class ReaderTextSearchState(
    val total: Int = 0,
    val scanned: Int = 0,
    val unavailable: Int = 0,
    val matches: List<ReaderTextMatch> = emptyList(),
    val active: Boolean = false,
)

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
    state: StateFlow<ReaderTextSearchState>,
    onFind: (String, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    onOpenMatch: (ReaderTextMatch) -> Unit,
) {
    val result by state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var wholeBook by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit = {
        keyboard?.hide()
        focus.clearFocus()
        onFind(query, wholeBook)
    }
    AppBottomSheet(onDismissRequest = onDismiss, title = "查找正文") {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = query,
                    onValueChange = {
                        query = it.take(MAX_READER_TEXT_QUERY)
                        onReset()
                    },
                    placeholder = "输入要查找的文字",
                    onSearch = submit,
                    modifier = Modifier.weight(1f),
                )
                AppTextButton("查找", onClick = submit, enabled = query.isNotBlank())
            }
            SegmentedChoice(
                options = listOf("本章", "整书"),
                selectedIndex = if (wholeBook) 1 else 0,
                onSelect = {
                    if (wholeBook != (it == 1)) {
                        wholeBook = it == 1
                        onReset()
                    }
                },
            )
            Text(
                when {
                    result.total == 0 -> if (wholeBook) "查找本地或已缓存的正文" else "查找当前章节"
                    result.active -> "查找中 · ${result.scanned} / ${result.total} 章"
                    result.matches.size == MAX_READER_TEXT_MATCHES -> "显示前 $MAX_READER_TEXT_MATCHES 处"
                    else -> "${result.matches.size} 处 · 已查 ${result.scanned - result.unavailable} 章" +
                        if (result.unavailable > 0) " · ${result.unavailable} 章不可用" else ""
                },
                style = MiuixTheme.textStyles.footnote1,
            )
            LazyColumn(Modifier.heightIn(max = Dimens.sheetListMaxHeight)) {
                items(result.matches, key = { "${it.position.chapterIndex}:${it.position.charOffset}" }) { match ->
                    ContentListItem(
                        onClick = { onOpenMatch(match) },
                        supportingContent = { Text(match.chapterTitle, style = MiuixTheme.textStyles.footnote1) },
                    ) { Text(match.excerpt, style = MiuixTheme.textStyles.body2) }
                }
            }
        }
    }
}
