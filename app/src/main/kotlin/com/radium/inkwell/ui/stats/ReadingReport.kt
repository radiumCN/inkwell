package com.radium.inkwell.ui.stats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.radium.inkwell.ui.components.writeExportFile
import java.io.File

internal suspend fun exportReadingReport(context: Context, ui: ReadingStatsUi): File =
    writeExportFile(context, "inkwell-reading-report.png") { file ->
        val range = ui.range ?: ReadingDateRange(ui.today.minusDays(29), ui.today)
        val from = range.start.toEpochDay()
        val to = range.end.toEpochDay()
        val data = ui.summary.daily.filterKeys { it in from..to }
        val days = to - from + 1
        val bucketSize = (days + 31) / 32
        val buckets = LongArray(((days + bucketSize - 1) / bucketSize).toInt())
        data.forEach { (day, millis) -> buckets[((day - from) / bucketSize).toInt()] += millis }
        val maxBucket = (buckets.maxOrNull() ?: 0L).coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val ink = Color.rgb(48, 36, 24)
            val secondary = Color.rgb(103, 78, 50)
            canvas.drawColor(Color.rgb(247, 238, 217))
            fun text(label: String, left: Float, baseline: Float, size: Float, color: Int, bold: Boolean = false) {
                paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.textSize = size
                paint.color = color
                canvas.drawText(label, left, baseline, paint)
            }
            text("Inkwell", 56f, 62f, 27f, secondary, bold = true)
            text("阅读报告", 56f, 128f, 48f, ink, bold = true)
            text("${range.start} 至 ${range.end}", 56f, 174f, 28f, secondary)
            text("阅读时长", 56f, 236f, 27f, secondary)
            text("活跃天数", 510f, 236f, 27f, secondary)
            text(formatDuration(data.values.sum()), 56f, 291f, 39f, ink, bold = true)
            text("${data.count { it.value >= ACTIVE_DAY_MILLIS }} 天", 510f, 291f, 39f, ink, bold = true)
            val caption = if (bucketSize == 1L) "每日阅读时长" else "阅读时长 · 每 $bucketSize 天一格"
            text(caption, 56f, 357f, 25f, secondary)
            paint.color = Color.rgb(220, 203, 172)
            canvas.drawRect(56f, 485f, 904f, 487f, paint)
            val slot = 848f / buckets.size
            paint.color = Color.rgb(133, 82, 45)
            buckets.forEachIndexed { index, millis ->
                if (millis > 0) {
                    val left = 56f + index * slot + slot * 0.15f
                    val height = (millis.toFloat() / maxBucket * 100f).coerceAtLeast(3f)
                    canvas.drawRoundRect(left, 485f - height, left + slot * 0.7f, 485f, 4f, 4f, paint)
                }
            }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
