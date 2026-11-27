package com.radium.inkwell.ui.stats

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.radium.inkwell.MainActivity
import com.radium.inkwell.R
import com.radium.inkwell.data.db.dao.ReadingStatDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import java.time.LocalDate

class ReadingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val time = runCatching {
                    withTimeout(5_000) {
                        GlobalContext.get().get<ReadingStatDao>().totalForDay(LocalDate.now().toEpochDay())
                    }
                }.getOrNull()
                val openStats = Intent(app, MainActivity::class.java)
                    .setAction(OPEN_READING_STATS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val open = PendingIntent.getActivity(app, 0, openStats, PendingIntent.FLAG_IMMUTABLE)
                val views = RemoteViews(app.packageName, R.layout.reading_widget).apply {
                    setTextViewText(R.id.reading_widget_duration, time?.let(::formatDuration) ?: "暂不可用")
                    setOnClickPendingIntent(R.id.reading_widget_root, open)
                }
                manager.updateAppWidget(ids, views)
            } catch (error: RuntimeException) {
                Log.w("ReadingWidget", "Widget update failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val OPEN_READING_STATS = "com.radium.inkwell.OPEN_READING_STATS"

        internal fun refresh(context: Context) {
            try {
                val provider = ComponentName(context, ReadingWidgetProvider::class.java)
                val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(provider)
                if (ids.isEmpty()) return
                context.sendBroadcast(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .setComponent(provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            } catch (error: RuntimeException) {
                Log.w("ReadingWidget", "Widget refresh request failed", error)
            }
        }
    }
}
