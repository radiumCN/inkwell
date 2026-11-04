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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.ui.components.Dimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页眉章节名 + 页脚信息层。
 * 左下：章内页码；右下：全书进度百分比 · 时间 ·（可选）电池图标。
 */
@Composable
fun PageInfoBar(pageUi: ReaderPageUi, session: ReaderSessionUi, spec: LayoutSpec) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val footerColor = remember(session.settings.theme.footerColor) {
        Color(session.settings.theme.footerColor)
    }
    val footerStyle = pageInfoTextStyle()
    val footerNumStyle = remember(footerStyle) { footerStyle.copy(fontFeatureSettings = "tnum") }
    val showBattery = session.settings.showBattery

    var time by remember { mutableStateOf(currentTime()) }
    // 关掉电量时连 sticky BATTERY_CHANGED 都不读，少一次系统广播。
    var battery by remember { mutableIntStateOf(if (showBattery) readBattery(context) else 100) }

    // 整点/每分钟系统会发 TIME_TICK，不必自己 30s 轮询。
    DisposableEffect(context) {
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
        battery = batteryPercent(sticky) ?: battery
        onDispose { context.unregisterReceiver(receiver) }
    }

    val bookPercent = remember(pageUi.chapterIndex, pageUi.pageInChapter, pageUi.pageCount, session.chapterCount) {
        bookProgressPercent(pageUi, session.chapterCount)
    }

    val marginH = with(density) { spec.marginLeftPx.toDp() }
    // 页眉/页脚对齐正文预留的那两条带 —— marginTop/Bottom 已经把挖孔折进去了，所以既清开
    // 摄像头、又不会像从前那样（固定 8dp）把页眉顶到屏幕最边、跟正文之间空出一大截。
    // 页眉小标题落在正文上边距那条带的顶端 —— marginTopPx 现在就是「挖孔 + 头部留白」，
    // 所以缩小头部留白，页眉和正文一起上移，正文多出空间
    val headerTop = with(density) { spec.marginTopPx.toDp() }
    val footerBottom = with(density) { spec.marginBottomPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        // 页眉：章节名，落在正文上方的页眉带里
        Text(
            pageUi.chapterTitle,
            Modifier
                .align(Alignment.TopStart)
                .padding(start = marginH, end = marginH, top = headerTop),
            color = footerColor,
            style = footerStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // 页脚：左页码、右「进度 · 时间 · 电量」，同一条基线
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = marginH, end = marginH, bottom = footerBottom),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${pageUi.pageInChapter + 1} / ${pageUi.pageCount}",
                color = footerColor,
                style = footerNumStyle,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${"%.1f".format(bookPercent)}%",
                color = footerColor,
                style = footerNumStyle,
            )
            Text(
                "  ·  $time",
                color = footerColor,
                style = footerNumStyle,
            )
            if (showBattery) {
                Spacer(Modifier.width(Dimens.gapS))
                BatteryIndicator(level = battery, color = footerColor)
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

/** 小电池图标：描边外框 + 正极触点 + 按电量填充 */
@Composable
private fun BatteryIndicator(level: Int, color: Color) {
    Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
        val strokeWidth = 1.dp.toPx()
        val tipWidth = 2.dp.toPx()
        val bodyWidth = size.width - tipWidth - strokeWidth
        val corner = CornerRadius(2.dp.toPx())
        // 外框
        drawRoundRect(
            color = color,
            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
            size = Size(bodyWidth - strokeWidth, size.height - strokeWidth),
            cornerRadius = corner,
            style = Stroke(strokeWidth),
        )
        // 正极
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyWidth + strokeWidth / 2, size.height * 0.3f),
            size = Size(tipWidth, size.height * 0.4f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        // 电量填充
        val inset = strokeWidth * 1.8f
        val fillMax = bodyWidth - strokeWidth - inset * 2 + strokeWidth
        drawRoundRect(
            color = color,
            topLeft = Offset(strokeWidth / 2 + inset, strokeWidth / 2 + inset),
            size = Size(
                (fillMax * level.coerceIn(0, 100) / 100f).coerceAtLeast(1f),
                size.height - strokeWidth - inset * 2,
            ),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}

private fun currentTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun readBattery(context: Context): Int {
    val intent = ContextCompat.registerReceiver(
        context,
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    return batteryPercent(intent) ?: 100
}

private fun batteryPercent(intent: Intent?): Int? {
    intent ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
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
