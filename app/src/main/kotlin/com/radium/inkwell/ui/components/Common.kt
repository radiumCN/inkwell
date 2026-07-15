package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** 空状态：图标 + 标题 + 提示 + 可选动作，全应用统一形态 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(Dimens.gapXXL), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(Dimens.iconXL),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                title,
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (hint != null) {
                Text(
                    hint,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, Modifier.padding(top = 8.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * 加载态。与 [EmptyState]/[ErrorState] 三态对称，全应用统一形态。
 *
 * 从前每个页面各写各的 `Box(center){ CircularProgressIndicator() }`：有的裸 40dp、有的 24dp，
 * 有的配一行说明、有的没有 —— 同样是"正在加载"，跨页面长得不一样。收敛成一处。
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(modifier.fillMaxSize().padding(Dimens.gapXXL), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            if (label != null) {
                Text(
                    label,
                    Modifier.padding(top = Dimens.gapM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 书封缩略图。
 *
 * 默认封面**永远垫在底层**，图片加载成功就把它盖住。从前只有 `coverModel == null` 才画占位，
 * 于是「有封面地址、但抓不下来」这种最常见的情况（书源的图床挂了、防盗链、超时）
 * 落进 AsyncImage 的失败分支后就是一整块空白灰方 —— 连书名都没有，用户根本认不出是哪本书。
 * 垫在底层就不必去猜 Coil 的加载状态：成功即遮住，失败即透出。
 */
@Composable
fun BookCover(
    title: String,
    coverModel: Any?,
    modifier: Modifier = Modifier,
    placeholderChars: Int = 4,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Box {
            DefaultCover(title, placeholderChars)
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 没有封面时长这样：一块带书脊的素封面，印着书名。
 *
 * 底色由**书名决定**（同一本书永远同一个色），而不是随机或一律灰 ——
 * 一屏灰方块里找书，等于逐个读字；有了颜色，位置和色块本身就成了记忆线索。
 * 色板全部取自 App 的墨/纸语汇，且都足够深，白字压得住（不必逐个算对比度）。
 */
@Composable
private fun DefaultCover(title: String, maxChars: Int) {
    val base = remember(title) { COVER_PALETTE[colorIndex(title)] }
    Box(Modifier.fillMaxSize().background(base)) {
        // 书脊：左侧一道压深的窄条。没有它，纯色块看着像色卡而不像书
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.07f)
                .background(Color.Black.copy(alpha = 0.16f))
        )
        Text(
            title.take(maxChars),
            Modifier
                .align(Alignment.Center)
                .padding(start = 12.dp, end = 6.dp),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 墨/纸语汇里的深色调；都够深，白字直接压得住 */
private val COVER_PALETTE = listOf(
    Color(0xFF92400E), // 赭石（品牌主色）
    Color(0xFF2B3A55), // 墨蓝
    Color(0xFF3E6B4F), // 竹绿
    Color(0xFF7C4A21), // 牛皮
    Color(0xFF5B4B7A), // 紫檀
    Color(0xFF6B3A3A), // 绛
    Color(0xFF44615E), // 苍
    Color(0xFF7A5C2E), // 秋香
)

/** 按书名取色。hashCode 在 JVM 上是规范定义的，同一本书跨设备、跨启动都是同一个色 */
internal fun colorIndex(title: String): Int {
    val h = title.hashCode()
    // Int.MIN_VALUE 取绝对值还是它自己（负数），直接 % 会得到负下标
    return ((h % COVER_PALETTE.size) + COVER_PALETTE.size) % COVER_PALETTE.size
}

/** 书籍列表行：封面 + 标题/副标题/来源 + 尾部动作，搜索与发现共用；onClick 非空时整行可点 */
@Composable
fun BookListRow(
    title: String,
    subtitle: String?,
    caption: String?,
    coverModel: Any?,
    trailingLabel: String,
    trailingLoading: Boolean,
    onTrailing: () -> Unit,
    /** false 时尾部按钮置灰不可点（如"已加入"）—— 书已在架就不该再让人点"加入" */
    trailingEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            title = title,
            coverModel = coverModel,
            modifier = Modifier.size(width = Dimens.coverThumbWidth, height = Dimens.coverThumbHeight),
            placeholderChars = 2,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!caption.isNullOrBlank()) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 转圈叠在按钮上，而不是替换它 —— 24dp 的转圈换掉 48dp 的按钮，整行高度会跳一下。
        // 这正是 AppButtons 里已经修好的那个坑，这里又犯了一遍
        Box(contentAlignment = Alignment.Center) {
            TextButton(onClick = onTrailing, enabled = trailingEnabled && !trailingLoading) {
                Text(
                    trailingLabel,
                    color = if (trailingLoading) Color.Transparent else LocalContentColor.current,
                )
            }
            if (trailingLoading) {
                CircularProgressIndicator(
                    Modifier.size(Dimens.buttonSpinner),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
