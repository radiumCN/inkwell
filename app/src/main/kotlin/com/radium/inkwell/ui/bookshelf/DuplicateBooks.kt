package com.radium.inkwell.ui.bookshelf

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.CardViewport
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.CoverMosaicGlassCard
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.compactWhitespace
import com.radium.inkwell.ui.components.formatRelativeTime
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.sinkOnPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 书架上「继续阅读」下面那张提示卡：「发现 N 组重复的书」。
 *
 * 放在列表里、跟着书一起滚，不浮在书上：提示是「有空了整理一下」，不该常驻压着一排书。
 * 卡面是封面毛玻璃（[CoverMosaicGlassCard]），底下糊开的就是重复的那几本 —— 不点开也认得出大概是哪几本。
 *
 * 整张卡点开整理面板（按下整块微缩，同书架上的书）；行尾只有一个 ✕ = 这一批不再提示，以后出现**新的**重复才再冒出来
 * （见 BookshelfViewModel.dismissDuplicateHint）。想一直不提示，在「设置 → 外观 → 书架」里关掉。
 */
@Composable
internal fun DuplicateHintCard(
    groups: List<DuplicateGroup>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    val covers = remember(groups) {
        groups.asSequence().flatMap { it.books }.take(HINT_COVERS).map { it.title to it.coverPath }.toList()
    }
    CoverMosaicGlassCard(covers = covers, modifier = modifier.fillMaxWidth().sinkOnPress(press)) {
        val content = LocalContentColor.current
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = press,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onOpen,
                )
                .padding(start = Dimens.listHorizontal, end = Dimens.gapXS, top = Dimens.gapM, bottom = Dimens.gapM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MiuixIcons.Copy, contentDescription = null, tint = content, modifier = Modifier.size(Dimens.iconMd))
            // 玻璃上的字统一一种颜色（见 CoverMosaicGlassCard），层级靠字号拉开
            Column(Modifier.weight(1f).padding(horizontal = Dimens.gapM)) {
                Text(
                    "发现 ${groups.size} 组重复的书",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = content,
                )
                Text(
                    "同一本书加了不止一份，点这里挑一份留下",
                    style = MiuixTheme.textStyles.footnote1,
                    color = content,
                )
            }
            AppIconButton(onClick = onDismiss, contentColor = content) {
                Icon(MiuixIcons.Close, contentDescription = "不再提示这几组重复的书")
            }
        }
    }
}

/**
 * 整理重复的书：每组一张卡，小标题「书名 · 作者」，行是这本书的各份（书源名做主行，同换源面板），行尾勾「留这一份」。
 *
 * - 默认留读得最远的那份（[findDuplicates] 排好的第一本）；没被留下的那几行副行末尾红字「将删除」、封面变淡。
 * - 小标题右边「都保留」：认错了、或者本来就想留两份。这一组跳过，记下来（[keptIds]），以后不再提示、再打开面板也还是跳过；
 *   「整理这组」撤回。
 * - 主按钮写清数量「删除 N 本」；要删的里有本地书先确认一次（书文件删了找不回）。删的走长按「从书架删除」同一条路。
 *
 * 「包含隐藏的书」只在隐藏区展开时出现（[includeHidden] 非 null）：平时这里一个字不提隐藏，同书架长按菜单、多选操作条。
 * 组里有隐藏的书时卡下写一句「留下的那份也会隐藏」（见 BookRepository.mergeDuplicates）。
 *
 * @param keptIds 「都保留」过的书；一组的书全在里面，这组就是跳过的
 * @param sourceNames 书源 id → 名称（网络书的主行）
 * @param onKeepBoth 点「都保留」/「整理这组」
 * @param onApply 点「删除 N 本」，面板收起之后回调
 */
@Composable
internal fun DuplicateBooksSheet(
    groups: List<DuplicateGroup>,
    keptIds: Set<String>,
    sourceNames: Map<String, String>,
    includeHidden: Boolean?,
    onIncludeHiddenChange: (Boolean) -> Unit,
    onKeepBoth: (DuplicateGroup, Boolean) -> Unit,
    onApply: (List<DuplicateMerge>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每组挑的那一份，按组的 key 记（key 不含书 id：拨「包含隐藏的书」一组多进来一本，已经挑好的照旧，见 DuplicateGroup.key）
    val keep = remember { mutableStateMapOf<String, String>() }
    var confirmLocal by remember { mutableStateOf(false) }
    val skipped = remember(groups, keptIds) {
        groups.filter { g -> g.books.all { it.id in keptIds } }.mapTo(HashSet()) { it.key }
    }
    val merges = remember(groups, skipped, keep.toMap()) { planMerges(groups, keep, skipped) }
    val removing = merges.sumOf { it.removeIds.size }
    val removingLocal = remember(merges, groups) {
        val ids = merges.flatMapTo(HashSet()) { it.removeIds }
        groups.sumOf { g -> g.books.count { it.id in ids && it.type != BookType.NET } }
    }
    AppBottomSheet(onDismissRequest = onDismiss, title = "整理重复的书") {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        // 组数是定的（不边搜边出），列表按内容长、到上限才滚：只有一两组时面板不必空出一大截。
        // 拨「包含隐藏的书」会多出 / 少掉几组、面板跟着变高变矮 —— 那是用户自己拨的，看得出为什么
        val listMax = (windowHeight * LIST_HEIGHT_FRACTION).coerceAtMost(Dimens.sheetListMaxHeight)
        Column(Modifier.fillMaxWidth()) {
            Text(
                if (groups.isEmpty()) "书架上没有重复的书" else "${groups.size} 组 · 每组留一份，其余从书架删除",
                Modifier.fillMaxWidth().padding(horizontal = cardMargin()),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            if (includeHidden != null) {
                SettingGroup(Modifier.padding(top = Dimens.gapS)) {
                    SwitchRow(
                        title = "包含隐藏的书",
                        subtitle = "隐藏的书也一起比对",
                        checked = includeHidden,
                        onCheckedChange = onIncludeHiddenChange,
                        position = SettingGroupPosition.Alone,
                    )
                }
            }
            if (groups.isEmpty()) {
                EmptyState(
                    icon = MiuixIcons.Copy,
                    title = "没有重复的书",
                    modifier = Modifier.heightIn(max = listMax),
                )
            } else {
                // 视口裁成卡的圆上角：一滚，顶上切过的是卡中间的行，不裁的话卡顶是一刀直线（见 CardViewport）
                CardViewport(Modifier.padding(top = Dimens.gapS).heightIn(max = listMax)) {
                    LazyColumn(
                        modifier = Modifier.pageScroll(),
                        overscrollEffect = null,
                    ) {
                        groups.forEach { g ->
                            val isSkipped = g.key in skipped
                            val keepId = keep[g.key]?.takeIf { id -> g.books.any { it.id == id } } ?: g.books.first().id
                            item(key = "h:${g.key}") {
                                SectionHeader(
                                    if (g.author.isBlank()) g.title else "${g.title} · ${g.author}",
                                    trailing = {
                                        AppTextButton(
                                            if (isSkipped) "整理这组" else "都保留",
                                            onClick = { onKeepBoth(g, !isSkipped) },
                                        )
                                    },
                                )
                            }
                            itemsIndexed(g.books, key = { _, b -> "b:${g.key}:${b.id}" }) { i, book ->
                                DuplicateCopyRow(
                                    book = book,
                                    group = g,
                                    sourceName = sourceName(book, sourceNames),
                                    kept = book.id == keepId,
                                    skipped = isSkipped,
                                    onKeep = { keep[g.key] = book.id },
                                    modifier = Modifier.groupedCardItem(i, g.books.size),
                                )
                            }
                            if (!isSkipped && g.books.any { it.hidden }) {
                                item(key = "f:${g.key}") { SettingFootnote("这组有隐藏的书，留下的那份也会隐藏") }
                            }
                        }
                        item(key = "foot") {
                            SettingFootnote(
                                "默认留下读得最远的那份。删掉的和长按「从书架删除」一样：进度、缓存一起清，别的设备同步后也删。",
                                Modifier.padding(top = Dimens.gapS, bottom = Dimens.gapM),
                            )
                        }
                    }
                }
            }
            PrimaryButton(
                text = if (removing > 0) "删除 $removing 本" else "删除",
                onClick = { if (removingLocal > 0) confirmLocal = true else dismiss { onApply(merges) } },
                enabled = removing > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cardMargin())
                    .padding(top = Dimens.gapM, bottom = Dimens.gapL),
            )
        }
        if (confirmLocal) {
            AppAlertDialog(
                onDismissRequest = { confirmLocal = false },
                title = "删除 $removing 本重复的书",
                text = "其中 $removingLocal 本是本地导入的书，书文件会一起删掉，删了找不回来。留下的那份不受影响。",
                confirmText = "删除",
                onConfirm = {
                    confirmLocal = false
                    dismiss { onApply(merges) }
                },
            )
        }
    }
}

/**
 * 一份：书源名（主行，隐藏的挂个图标）→ 只在书名作者和小标题对不上时多一行 → 读到哪、多久前读过、共几章。
 * 整行单选（「留这一份」），行尾的勾只是展示；没被留下的封面变淡、末尾红字「将删除」。
 */
@Composable
private fun DuplicateCopyRow(
    book: BookEntity,
    group: DuplicateGroup,
    sourceName: String,
    kept: Boolean,
    skipped: Boolean,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val removing = !kept && !skipped
    val mismatch = remember(book, group) { mismatchLine(book, group) }
    ContentListItem(
        selected = kept && !skipped,
        onClick = onKeep,
        enabled = !skipped,
        modifier = modifier,
        contentPadding = ContentListDefaults.MediaPadding,
        // 选中不铺底：状态只看行尾的勾（同多选行）
        colors = ContentListDefaults.plainColors(),
        leadingContent = {
            BookCover(
                title = book.title,
                coverModel = book.coverPath,
                modifier = Modifier
                    .size(Dimens.coverThumbWidth, Dimens.coverThumbHeight)
                    .graphicsLayer { alpha = if (removing) REMOVING_COVER_ALPHA else 1f },
                contentDescription = null,
            )
        },
        trailingContent = {
            Checkbox(
                state = ToggleableState(kept && !skipped),
                onClick = null,
                enabled = !skipped,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        supportingContent = {
            Column {
                mismatch?.let {
                    Text(it, style = ContentListDefaults.supportingStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row {
                    Text(
                        statusLine(book),
                        Modifier.weight(1f, fill = false),
                        style = ContentListDefaults.supportingStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (removing) {
                        Text(
                            " · 将删除",
                            style = ContentListDefaults.supportingStyle,
                            color = MiuixTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
            Text(
                sourceName,
                Modifier.weight(1f, fill = false),
                style = ContentListDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 同书架封面左上角那个：隐藏区开着时才会有隐藏的书进到这里
            if (book.hidden) {
                Icon(
                    MiuixIcons.Hide,
                    contentDescription = "已隐藏",
                    modifier = Modifier.size(Dimens.iconSm),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 设置页「整理重复的书」那一行要的：没隐藏的书里有几组重复。设置页一个字不提隐藏，所以从这里进来永远只比没隐藏的书。
 * null = 还没算出来
 */
@Composable
internal fun rememberVisibleDuplicateCount(): Int? {
    val bookRepo = koinInject<BookRepository>()
    val count by remember(bookRepo) {
        bookRepo.books.map { shelfDuplicates(it, includeHidden = false).size }.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(initialValue = null)
    return count
}

/**
 * 从设置页进来的整理面板：自己取书架、书源名、「都保留」记录，删完在调用页的 [messages] 里说一声。
 * 只比没隐藏的书（见 [rememberVisibleDuplicateCount]），没有「包含隐藏的书」开关。
 */
@Composable
internal fun StandaloneDuplicateBooksSheet(messages: MessageBus, onDismiss: () -> Unit) {
    val bookRepo = koinInject<BookRepository>()
    val sourceRepo = koinInject<BookSourceRepository>()
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val books by bookRepo.books.collectAsStateWithLifecycle(initialValue = null)
    val groups = remember(books) { books?.let { shelfDuplicates(it, includeHidden = false) } }
    val keptIds by appPrefs.duplicateKeptIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val sourceNames by remember(sourceRepo) { sourceRepo.sources.map { list -> list.associate { it.id to it.name } } }
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    // 书架那一读还没回来（几十毫秒）：先不出面板，免得先闪一下「没有重复的书」
    val ready = groups ?: return
    DuplicateBooksSheet(
        groups = ready,
        keptIds = keptIds,
        sourceNames = sourceNames,
        includeHidden = null,
        onIncludeHiddenChange = {},
        onKeepBoth = { g, keepBoth ->
            scope.launch {
                val ids = g.books.map { it.id }
                if (keepBoth) appPrefs.addDuplicateKept(ids, books.orEmpty().mapTo(HashSet()) { it.id })
                else appPrefs.removeDuplicateKept(ids)
            }
        },
        onApply = { merges ->
            scope.launch {
                val n = bookRepo.mergeDuplicates(merges)
                messages.emit(if (n > 0) "已删除 $n 本重复的书" else "这些书已经不在书架上了")
            }
        },
        onDismiss = onDismiss,
    )
}

/** 主行：网络书写书源名（没填名称时从地址里抠主机名，别把整段 URL 甩上去），本地书写格式 */
private fun sourceName(book: BookEntity, names: Map<String, String>): String = when (book.type) {
    BookType.NET -> {
        val id = book.sourceId.orEmpty()
        names[id]?.takeIf { it.isNotBlank() }
            ?: id.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('#')
                .substringBefore('?').ifBlank { "网络书" }
    }
    BookType.LOCAL_EPUB -> "本地 EPUB"
    BookType.LOCAL_MOBI -> "本地 MOBI"
    else -> "本地 TXT"
}

/**
 * 只在对不上时才写的那一行（同换源面板）：书名带了书名号、作者带了「著」—— 正是这些写法让「已在书架」没认出来。
 * 一样的不写，免得每行都把书名作者原样重复一遍。
 */
private fun mismatchLine(book: BookEntity, group: DuplicateGroup): String? {
    val parts = buildList {
        if (book.title.trim() != group.title) add("书名：${book.title.compactWhitespace()}")
        val author = book.author.trim()
        if (author.isNotEmpty() && author != group.author) add("作者：${author.compactWhitespace()}")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 「读到第 41 章 · 3 天前 · 共 120 章」；没打开过写「没读过」 */
private fun statusLine(book: BookEntity): String {
    val parts = buildList {
        if (book.lastActiveAt() > 0L) {
            add("读到第 ${book.readChapterIndex + 1} 章")
            add(formatRelativeTime(book.lastActiveAt()))
        } else {
            add("没读过")
        }
        if (book.totalChapters > 0) add("共 ${book.totalChapters} 章")
    }
    return parts.joinToString(" · ")
}

/** 提示卡底下铺几张封面：再多糊开也分不出来 */
private const val HINT_COVERS = 4

/** 面板里列表最多占窗口多高（同换源面板） */
private const val LIST_HEIGHT_FRACTION = 0.5f

/** 「将删除」那几份的封面淡到多少：看得出是哪本，又和留下的那份分得开 */
private const val REMOVING_COVER_ALPHA = 0.45f
