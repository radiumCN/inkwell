package com.radium.inkwell.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.repo.ReadingStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class TopBook(val title: String, val author: String, val millis: Long)

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
    val state: StateFlow<ReadingStatsUi?> = stats.observeAll()
        .map { rows ->
            val today = LocalDate.now()
            val summary = summarize(rows, today, topN = Int.MAX_VALUE)
            ReadingStatsUi(today, summary, topBooks(summary))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 「今年读得最多」。**隐藏的书不进这张榜**：隐藏区的存在本身就是秘密（设置页里一个字都不提），
     * 榜上冒出一本书架上找不到的书，就等于把它说出来了。它的时长仍算在总数里 —— 总数看不出是哪本书。
     * 删掉的书照常上榜：从书架删掉不等于没读过。书行都查不到的（试读完没加入书架）跳过。
     */
    private suspend fun topBooks(summary: ReadingSummary): List<TopBook> {
        val result = ArrayList<TopBook>(TOP_BOOKS)
        for ((id, millis) in summary.topBooks) {
            if (result.size >= TOP_BOOKS) break
            val book = bookDao.getById(id) ?: continue
            if (!book.hidden) result += TopBook(book.title, book.author, millis)
        }
        return result
    }

    private companion object {
        const val TOP_BOOKS = 5
    }
}
