package com.radium.inkwell.ui.rss

import android.content.ClipData
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.ui.components.AppSurface
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.theme.AppShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 订阅文章里的三种块：代码、列表项、引用（解析见 core 的 HtmlToElements，keepBlocks）。
 * 只在文章页用；书的阅读器不排它们。
 */

/**
 * 代码块：灰底圆角框（输入框那一档灰，和页面、卡片都分得开），等宽字体、比正文小一号。
 * **原样换行、保留缩进，长行不折** —— 折了缩进就乱，命令也看不出哪是一行；框里左右划，右边一道淡出提示后面还有。
 * 顶上一行：左边语言（网页上标了才有），右边「复制」。看技术文章最常做的就是把命令抄走。
 *
 * 复制后按钮字换成「已复制」一会儿。不另弹提示：Android 13 起系统自己会在屏幕下方弹一下剪贴板的确认（minSdk 33），
 * 再弹一个就是两份。
 */
@Composable
internal fun ArticleCodeBlock(code: ContentElement.Code, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MS)
            copied = false
        }
    }
    val background = MiuixTheme.colorScheme.secondaryContainer
    AppSurface(
        cornerRadius = AppShapes.radiusMedium,
        modifier = modifier.padding(vertical = Dimens.gapS).fillMaxWidth(),
        color = background,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = Dimens.gapM), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    code.language.orEmpty(),
                    Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                AppTextButton(
                    if (copied) "已复制" else "复制",
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code.text)))
                            copied = true
                        }
                    },
                )
            }
            val scroll = rememberScrollState()
            Text(
                code.text,
                Modifier
                    .fadeEndWhenScrollable(scroll, background)
                    .horizontalScroll(scroll)
                    .padding(start = Dimens.gapM, end = Dimens.gapM, bottom = Dimens.gapM),
                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace, lineHeight = CODE_LINE_HEIGHT),
                color = MiuixTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }
}

/** 右边还有没划出来的字时，在框的右边缘叠一道淡出（只在绘制里读滚动位置，划动不重组） */
private fun Modifier.fadeEndWhenScrollable(scroll: ScrollState, background: Color): Modifier = drawWithContent {
    drawContent()
    if (scroll.canScrollForward) {
        val fade = Dimens.gapXL.toPx()
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, background), startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f),
            size = Size(fade, size.height),
        )
    }
}

/**
 * 列表的一项：圆点（有序列表是 1. 2. 3.）在左边一格，字折行时和第一行的字对齐（悬挂缩进）；
 * 嵌套的一层往里缩一格。项与项之间比段落挨得近 —— 它们是一组。圆点用强调色，序号和字同色。
 */
@Composable
internal fun ArticleListItem(item: ContentElement.ListItem, style: TextStyle, modifier: Modifier = Modifier) {
    val bullet = !item.marker.endsWith(".")
    Row(modifier.padding(start = Dimens.gapL * item.depth, top = Dimens.gapXS, bottom = Dimens.gapXS)) {
        Text(
            item.marker,
            Modifier.widthIn(min = Dimens.gapXL),
            style = style,
            color = if (bullet) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(Dimens.gapXS))
        Text(item.text, Modifier.weight(1f), style = style)
    }
}

/** 引用：左边一道淡强调色竖线，字用说明色（对比度在 AppThemesTest 里钉过）—— 一眼分出是引的话，不是作者说的 */
@Composable
internal fun ArticleQuote(quote: ContentElement.Quote, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = Dimens.gapS).height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(Dimens.strokeThick)
                .fillMaxHeight()
                .background(MiuixTheme.colorScheme.primary.copy(alpha = QUOTE_BAR_ALPHA)),
        )
        Text(
            quote.text,
            Modifier.padding(start = Dimens.gapM),
            style = style,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 「已复制」停留多久再变回「复制」 */
private const val COPIED_FEEDBACK_MS = 2_000L

/** 代码的行高：等宽小字比正文密，1.5 倍够分清行，又不至于稀到看不出是一段代码 */
private val CODE_LINE_HEIGHT = 1.5.em

/** 引用竖线的强调色浓度：实色太抢，比正文的字还显眼 */
private const val QUOTE_BAR_ALPHA = 0.4f
