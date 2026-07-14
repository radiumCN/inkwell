package com.radium.inkwell.ui.reader

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.radium.inkwell.reader.paginate.LayoutSpec
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页眉章节名 + 页脚信息层。
 * 左下：章内页码；右下：全书进度百分比 · 时间 · 电池图标。
 */
@Composable
fun PageInfoBar(state: ReaderUiState, spec: LayoutSpec) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val footerColor = Color(state.settings.theme.footerColor)
    val footerNumStyle = remember { TextStyle(fontSize = FOOTER_SP, fontFeatureSettings = "tnum") }

    var time by remember { mutableStateOf(currentTime()) }
    var battery by remember { mutableIntStateOf(readBattery(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            time = currentTime()
            battery = readBattery(context)
            delay(30_000)
        }
    }

    // 全书阅读进度：章节 + 章内页比例
    val bookPercent = remember(state.chapterIndex, state.pageInChapter, state.pageCount, state.chapterCount) {
        if (state.chapterCount <= 0) 0f
        else {
            val pageFraction = if (state.pageCount > 0) {
                (state.pageInChapter + 1f) / state.pageCount
            } else 0f
            ((state.chapterIndex + pageFraction) / state.chapterCount * 100f).coerceIn(0f, 100f)
        }
    }

    val marginH = with(density) { spec.marginLeftPx.toDp() }
    // 页眉/页脚对齐正文预留的那两条带 —— marginTop/Bottom 已经把挖孔折进去了，所以既清开
    // 摄像头、又不会像从前那样（固定 8dp）把页眉顶到屏幕最边、跟正文之间空出一大截。
    // 头部小标题的顶部留白独立于正文边距（= 挖孔 + 用户设的头部留白）
    val headerTop = with(density) { spec.headerTopPaddingPx.toDp() }
    val footerBottom = with(density) { spec.marginBottomPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        // 页眉：章节名，落在正文上方的页眉带里
        Text(
            state.chapterTitle,
            Modifier
                .align(Alignment.TopStart)
                .padding(start = marginH, end = marginH, top = headerTop),
            color = footerColor,
            fontSize = FOOTER_SP,
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
                "${state.pageInChapter + 1} / ${state.pageCount}",
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
            Spacer(Modifier.width(8.dp))
            BatteryIndicator(level = battery, color = footerColor)
        }
    }
}

private val FOOTER_SP = 11.sp

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

private fun readBattery(context: android.content.Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return 100
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else 100
}
