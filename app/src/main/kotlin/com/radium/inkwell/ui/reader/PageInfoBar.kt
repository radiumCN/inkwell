package com.radium.inkwell.ui.reader

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radium.inkwell.reader.paginate.LayoutSpec
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 页眉章节名 + 页脚（页码/时间/电量），自绘区域之外的信息层 */
@Composable
fun PageInfoBar(state: ReaderUiState, spec: LayoutSpec) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val footerColor = Color(state.settings.theme.footerColor)

    var time by remember { mutableStateOf(currentTime()) }
    var battery by remember { mutableIntStateOf(readBattery(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            time = currentTime()
            battery = readBattery(context)
            delay(30_000)
        }
    }

    val marginH = with(density) { spec.marginLeftPx.toDp() }

    Box(Modifier.fillMaxSize().padding(horizontal = marginH, vertical = 6.dp)) {
        Text(
            state.chapterTitle,
            Modifier.align(Alignment.TopStart),
            color = footerColor,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Text(
            "${state.pageInChapter + 1}/${state.pageCount}",
            Modifier.align(Alignment.BottomStart),
            color = footerColor,
            fontSize = 11.sp,
        )
        Text(
            "$time  ${battery}%",
            Modifier.align(Alignment.BottomEnd),
            color = footerColor,
            fontSize = 11.sp,
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
