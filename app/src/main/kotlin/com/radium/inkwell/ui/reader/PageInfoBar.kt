package com.radium.inkwell.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.radium.inkwell.reader.api.BatteryDisplayStyle
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.theme.AppThemes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页眉章节名 + 页脚信息层。
 * 左下：章内页码。右下：全书进度百分比、时间和电量。
 * 页眉的位置跟随已经应用到正文的分页结果，系统栏临时出现时收起上移的页眉。
 */
@Composable
fun PageInfoBar(
    pageUi: ReaderPageUi,
    session: ReaderSessionUi,
    spec: LayoutSpec,
    showRaisedHeader: Boolean,
    menuProgress: State<Float>,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val footerColor = remember(session.settings.theme.footerColor) {
        Color(session.settings.theme.footerColor)
    }
    val footerStyle = pageInfoTextStyle()
    val footerNumStyle = remember(footerStyle) { footerStyle.copy(fontFeatureSettings = "tnum") }
    val raisedHeader = spec.raisedHeader
    val showHeader = if (raisedHeader == null) spec.headerHeightPx > 0f else showRaisedHeader
    val showFooter = session.settings.showFooter
    val batteryStyle = session.settings.batteryDisplayStyle
    // 页脚关了，电量也就不看了：连 BATTERY_CHANGED 都不注册
    val showBattery = showFooter && session.settings.showBattery

    var time by remember { mutableStateOf(currentTime()) }
    var battery by remember(context, showBattery) { mutableStateOf<Int?>(null) }

    // 整点/每分钟系统会发 TIME_TICK，不必自己 30s 轮询。页脚关了就不听（时间只画在页脚上）
    DisposableEffect(context, showFooter) {
        if (!showFooter) return@DisposableEffect onDispose {}
        time = currentTime()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                time = currentTime()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIME_TICK),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    DisposableEffect(context, showBattery) {
        if (!showBattery) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                battery = batteryPercent(intent) ?: battery
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        battery = batteryPercent(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val bookPercent = remember(pageUi.chapterIndex, pageUi.pageInChapter, pageUi.pageCount, session.chapterCount) {
        bookProgressPercent(pageUi, session.chapterCount)
    }

    val marginH = with(density) { spec.marginLeftPx.toDp() }
    val footerBottom = with(density) { spec.marginBottomPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        if (showHeader) {
            val left = with(density) { (raisedHeader?.leftInsetPx ?: spec.marginLeftPx).toDp() }
            val right = with(density) { (raisedHeader?.rightInsetPx ?: spec.marginRightPx).toDp() }
            val top = with(density) { (raisedHeader?.topPx ?: spec.marginTopPx).toDp() }
            val chapterFade = if (raisedHeader == null) Modifier else Modifier.graphicsLayer {
                // 页眉先淡出，章名再在菜单栏里淡入。
                alpha = (1f - 2f * menuProgress.value).coerceIn(0f, 1f)
            }
            Text(
                pageUi.chapterTitle,
                Modifier
                    .align(Alignment.TopStart)
                    .absolutePadding(left = left, right = right, top = top)
                    .then(chapterFade),
                color = footerColor,
                style = footerStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 页脚：左页码、右「进度 · 时间 · 电量」，同一条基线
        if (showFooter) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = marginH, end = marginH, bottom = footerBottom),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${pageUi.pageInChapter + 1} / ${pageUi.pageCount}",
                    Modifier.weight(1f).padding(end = Dimens.gapXS),
                    color = footerColor,
                    style = footerNumStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${"%.1f".format(bookPercent)}%",
                    color = footerColor,
                    style = footerNumStyle,
                    maxLines = 1,
                )
                Text(
                    "  ·  $time",
                    color = footerColor,
                    style = footerNumStyle,
                    maxLines = 1,
                )
                battery?.takeIf { showBattery }?.let { level ->
                    Spacer(Modifier.width(Dimens.gapS))
                    BatteryIndicator(
                        level = level,
                        style = batteryStyle,
                        color = footerColor,
                        background = Color(session.settings.theme.background),
                    )
                }
            }
        }
    }
}

/**
 * 页眉/页脚小字的样式：排版角色 footnote1（13sp），正文性小字的下限。
 *
 * 从前裸写 11.sp，低于规范的下限，也不跟排版令牌走。分页给页眉页脚预留的那两条带
 * （ReaderScreen 的 buildLayoutSpec）按这里的行高兜底 —— 两边必须读同一个，否则调大系统字号后
 * 页脚的字就压进正文里了。
 *
 * 行高必须是 **sp**：Miuix 的 footnote1 不带行高（Unspecified），buildLayoutSpec 读到非 sp 就只按 dp 兜底，
 * 系统字号放大后两条带不再跟着撑开。这里补一个 18sp —— 正好等于默认的页脚带高 18dp
 * （页眉带 18 + gapS = 26dp 也与默认值相等），默认字号下排版一个像素都不变。
 */
@Composable
@ReadOnlyComposable
internal fun pageInfoTextStyle(): TextStyle = MiuixTheme.textStyles.footnote1.copy(lineHeight = 18.sp)

@Composable
internal fun BatteryIndicator(level: Int, style: BatteryDisplayStyle, color: Color, background: Color) {
    val percent = "$level%"
    val textStyle = pageInfoTextStyle().copy(fontFeatureSettings = "tnum")
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "电量 $percent" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (style != BatteryDisplayStyle.PERCENT_ONLY) {
            BatteryIcon(level, style == BatteryDisplayStyle.PERCENT_INSIDE, color, background, textStyle)
        }
        if (style == BatteryDisplayStyle.PERCENT_ONLY || style == BatteryDisplayStyle.PERCENT_OUTSIDE) {
            Text(
                percent,
                modifier = if (style == BatteryDisplayStyle.PERCENT_OUTSIDE) Modifier.padding(start = Dimens.gapXS) else Modifier,
                color = color,
                style = textStyle,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BatteryIcon(level: Int, showNumber: Boolean, color: Color, background: Color, textStyle: TextStyle) {
    val numberStyle = remember(textStyle) {
        textStyle.copy(fontSize = 10.sp, lineHeight = TextUnit.Unspecified, fontWeight = FontWeight.SemiBold)
    }
    val measurer = rememberTextMeasurer()
    // 三种样式都按满电数字预留空间，电量变化时图标宽度不跳，放大系统字号时也一起撑开。
    val fullNumber = remember(measurer, numberStyle) {
        measurer.measure("100", style = numberStyle, maxLines = 1, softWrap = false)
    }
    val number = if (showNumber) {
        remember(measurer, level, numberStyle, fullNumber) {
            if (level == 100) fullNumber
            else measurer.measure(level.toString(), style = numberStyle, maxLines = 1, softWrap = false)
        }
    } else {
        null
    }
    val density = LocalDensity.current
    val width = maxOf(
        Dimens.readerBatteryIconWidth,
        with(density) { fullNumber.size.width.toDp() } + Dimens.gapXS + Dimens.strokeThick,
    )
    val height = maxOf(Dimens.readerBatteryIconHeight, with(density) { fullNumber.size.height.toDp() })
    val numberColor = if (showNumber) {
        remember(background, color) { AppThemes.readableOn(background.copy(alpha = 1f), color) }
    } else {
        Color.Unspecified
    }

    Canvas(Modifier.size(width = width, height = height)) {
        val bodyWidth = size.width - batteryTipWidth()
        val bodyRadius = 3.dp.toPx()
        if (number != null && bodyWidth >= number.size.width && size.height >= number.size.height) {
            drawRoundRect(color, size = Size(bodyWidth, size.height), cornerRadius = CornerRadius(bodyRadius))
            drawText(
                textLayoutResult = number,
                color = numberColor,
                topLeft = Offset((bodyWidth - number.size.width) / 2f, (size.height - number.size.height) / 2f),
            )
        } else {
            val stroke = Dimens.strokeThin.roundToPx().coerceAtLeast(1).toFloat()
            val fillInset = 2 * stroke
            if (bodyWidth <= fillInset * 2 || size.height <= fillInset * 2) return@Canvas
            // 描边沿路径居中，内缩半个线宽让外沿正好贴住画布边。
            drawRoundRect(
                color = color,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(bodyWidth - stroke, size.height - stroke),
                cornerRadius = CornerRadius(bodyRadius - stroke / 2),
                style = Stroke(stroke),
            )
            // 填充与外框同心，满电时四边留同样宽的缝。
            if (level > 0) {
                val fillMax = bodyWidth - fillInset * 2
                drawRoundRect(
                    color = color,
                    topLeft = Offset(fillInset, fillInset),
                    size = Size((fillMax * level / 100f).coerceAtLeast(1f), size.height - fillInset * 2),
                    cornerRadius = CornerRadius((bodyRadius - fillInset).coerceAtLeast(0f)),
                )
            }
        }
        drawBatteryTip(color, bodyWidth)
    }
}

private fun DrawScope.batteryTipWidth(): Float = 1.5.dp.roundToPx().coerceAtLeast(1).toFloat()

/** 正极紧贴外框，只圆右边两个角，避免接缝处留空。 */
private fun DrawScope.drawBatteryTip(color: Color, bodyWidth: Float) {
    val tipInset = ((size.height - Dimens.gapXS.toPx()) / 2).roundToInt().coerceAtLeast(0).toFloat()
    val tipRadius = CornerRadius(minOf(size.width - bodyWidth, (size.height - tipInset * 2) / 2))
    drawPath(
        Path().apply {
            addRoundRect(
                RoundRect(
                    left = bodyWidth,
                    top = tipInset,
                    right = size.width,
                    bottom = size.height - tipInset,
                    topRightCornerRadius = tipRadius,
                    bottomRightCornerRadius = tipRadius,
                ),
            )
        },
        color = color,
    )
}

private fun currentTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun batteryPercent(intent: Intent?): Int? {
    intent ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) (level.coerceAtMost(scale) * 100L / scale).toInt() else null
}

/**
 * 全书阅读进度（0..100）：章节 + 章内页比例。页脚与阅读菜单的进度行共用一个算法 —— 两处各算一遍的话，
 * 菜单里写着 32.0%、底下页脚写着 32.1%。
 */
internal fun bookProgressPercent(page: ReaderPageUi, chapterCount: Int): Float {
    if (chapterCount <= 0) return 0f
    val pageFraction = if (page.pageCount > 0) (page.pageInChapter + 1f) / page.pageCount else 0f
    return ((page.chapterIndex + pageFraction) / chapterCount * 100f).coerceIn(0f, 100f)
}
