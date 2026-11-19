package com.radium.inkwell.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.StatsBook
import com.radium.inkwell.data.repo.ReadingStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class TopBook(val title: String, val author: String, val millis: Long)

/**
 * 「最近在读、没藏起来的那一本」（[BookDao.observeLatestRead]）查回来的结果：阅读记录「今天」卡与设置页概览卡
 * 拿它的封面铺底。外面再包一层，是为了和「还没查回来」分开 —— 流的初始值 null 是还在查，[book] 为 null
 * 才是查过了、一本都没有（见 CoverGlassCard 的 pending）。
 */
data class LatestRead(val book: BookEntity?)

data class ReadingStatsUi(
    val today: LocalDate,
    val summary: ReadingSummary,
    val topBooks: List<TopBook>,
)

class ReadingStatsViewModel(
    stats: ReadingStatsRepository,
    private val bookDao: BookDao,
) : ViewModel() {

    /** null = 还没读到第一份数据（与「读到了，但是空的」分开，别先闪一下空态） */
    val state: StateFlow<ReadingStatsUi?> = combine(
        stats.observeAll(), bookDao.observeStatsBooks().distinctUntilChanged(),
    ) { rows, books ->
        val today = LocalDate.now()
        val summary = summarize(rows, today, topN = Int.MAX_VALUE)
        ReadingStatsUi(today, summary, topBooks(summary, books))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 「今天」卡铺底的封面：最近在读、没藏起来的那一本（隐藏的书不露面，理由同 [topBooks]）。null = 还在查 */
    val latestRead: StateFlow<LatestRead?> = bookDao.observeLatestRead()
        .map(::LatestRead)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * 今年读得最多的五本。隐藏书不露名但仍计入总时长，已删除的书保留历史书名，缺书行的跳过。
 * 元数据一次查回，在内存选前五，不再为每条候选单独查库。
 */
internal fun topBooks(summary: ReadingSummary, books: List<StatsBook>): List<TopBook> {
    val byId = books.associateBy { it.id }
    return summary.topBooks.asSequence().mapNotNull { (id, millis) ->
        byId[id]?.takeUnless { it.hidden }?.let { TopBook(it.title, it.author, millis) }
    }.take(5).toList()
}
