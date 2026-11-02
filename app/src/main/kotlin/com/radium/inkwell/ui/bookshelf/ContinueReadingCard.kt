package com.radium.inkwell.ui.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.ReadingProgressMeter
import com.radium.inkwell.ui.components.formatRelativeTime

/**
 * 书架顶上的「继续阅读」：最近在读的那本放大摆出来，点一下回到上次的位置。
 *
 * 书架本身按最近阅读排序，第一本其实就是它 —— 但在三列网格里，第一格和别的格子一样大、一样不起眼，
 * 打开 App 十次有九次是要接着读这一本，却每次都得在一排封面里先认出它。卡片里多给的东西
 * （读到哪一章、读了多少、多久之前）也正是决定「接着读还是换一本」时要看的。
 *
 * 用 primaryContainer 铺底：它是书架上唯一一块带颜色的面，一眼就能落上去；文字统一 onPrimaryContainer，
 * 层级靠字号拉开 —— 这块底色上没有对应的「次要文字」令牌，硬套 onSurfaceVariant 对比度不保证。
 *
 * 交互与网格里的书一致：点开进书（从这张封面展开）、长按出同一个操作面板。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContinueReadingCard(
    entry: ContinueReading,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = entry.book
    val cover = remember { CoverAnchor() }
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    val progress = book.readingProgress()
    val chapterLine = entry.chapterTitle?.takeIf { it.isNotBlank() }
        ?: "第 ${book.readChapterIndex + 1} 章"
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            // 整张卡一个焦点：读屏顺着念「继续阅读、书名、章节、进度、多久前」，而不是拆成五段
            .semantics(mergeDescendants = true) {}
            .combinedClickable(
                role = Role.Button,
                onClick = { onClick(cover.boundsInWindow()) },
                onLongClick = onLongClick,
            )
            .padding(Dimens.gapL),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapL),
    ) {
        Box(Modifier.size(Dimens.coverDetailWidth, Dimens.coverDetailHeight)) {
            BookCover(
                title = book.title,
                coverModel = book.coverPath,
                modifier = Modifier
                    .matchParentSize()
                    .onPlaced { cover.coordinates = it },
                placeholderChars = 14,
                // 书名就在旁边，封面不再进读屏
                contentDescription = null,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .height(Dimens.coverDetailHeight),
        ) {
            Text("继续阅读", style = MaterialTheme.typography.labelLarge, color = content)
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                color = content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Dimens.gapXS),
            )
            Text(
                chapterLine,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.weight(1f))
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReadingProgressMeter(
                        progress = progress,
                        color = content,
                        trackColor = content.copy(alpha = 0.16f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                        modifier = Modifier.padding(start = Dimens.gapS),
                    )
                }
            }
            Text(
                "上次阅读 · ${formatRelativeTime(book.lastActiveAt())}",
                style = MaterialTheme.typography.bodySmall,
                color = content,
                maxLines = 1,
                modifier = Modifier.padding(top = Dimens.gapXS),
            )
        }
    }
}

/**
 * 封面底边的阅读进度（YouTube 看过多少那种）。贴着封面底边画、不占一行：网格里每多一行，一屏就少一排书；
 * 也不能只给有进度的书留这一行 —— 同一排里有的书标题被往下推、有的不推，书名就对不齐了。
 */
@Composable
internal fun BoxScope.CoverProgressOverlay(progress: Float?, shape: Shape) {
    if (progress == null) return
    Box(
        Modifier
            .matchParentSize()
            .clip(shape),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ReadingProgressMeter(progress = progress)
    }
}
