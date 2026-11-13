package com.radium.inkwell.ui.reader

import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.data.db.entity.TocRow
import com.radium.inkwell.data.repo.TitleMatch
import com.radium.inkwell.reader.api.ReadPosition

/**
 * 阅读中目录被换掉（后台追更）之后，把旧目录里的章号换算成新目录里的章号。
 *
 * 先认章节 URL —— 站点在前面插一章公告、删一章防盗章，同一章的地址并不会变；URL 认不出
 * （站点改了地址规则、本章没有 URL）再按标题就近对齐，与追更对齐进度用的是同一套标准
 * （[TitleMatch.alignChapter]），两边才不会对出两个不同的结果。
 *
 * 序号一律是**列表下标**：阅读数据源按下标取章（见 NetReaderBookSource），这里必须是同一套。
 */
internal class TocRemap(
    private val old: List<ChapterEntity>,
    private val new: List<ChapterEntity>,
) {
    /** url → 它在新目录里的下标。个别站点同一个 URL 挂两次，留着全部，取离旧序号最近的那个 */
    private val byUrl: Map<String, List<Int>> by lazy {
        val m = HashMap<String, MutableList<Int>>()
        new.forEachIndexed { i, c -> c.url?.let { m.getOrPut(it) { ArrayList(1) } += i } }
        m
    }

    /** 旧目录第 [oldIndex] 章在新目录里的下标；URL 和标题都对不上返回 null */
    fun map(oldIndex: Int): Int? {
        val chapter = old.getOrNull(oldIndex) ?: return null
        chapter.url?.let { url ->
            byUrl[url]?.minByOrNull { kotlin.math.abs(it - oldIndex) }?.let { return it }
        }
        return TitleMatch.alignChapter(
            chapter.title, oldIndex, new.indices.toList(),
            titleOf = { new[it].title },
            indexOf = { it },
        )
    }

    /**
     * 阅读位置换算。认出了是哪一章，章内偏移照旧有效；认不出（这一章从目录里没了）只能按序号夹进新目录、
     * 偏移归零 —— 与追更对齐进度（NetBookRepository.alignProgress）的兜底一致。
     */
    fun map(position: ReadPosition): ReadPosition =
        map(position.chapterIndex)?.let { position.copy(chapterIndex = it) }
            ?: ReadPosition(position.chapterIndex.coerceIn(0, (new.size - 1).coerceAtLeast(0)), 0)
}

/**
 * 两份目录在阅读数据源眼里是不是同一份：只比取章要用的字段（地址、标题、目录阶段存下的变量）。
 * isCached 这类状态位边读边变，预取每落一章都会让目录流再发一次 —— 那些不算目录变了。
 */
internal fun sameReadingToc(a: List<ChapterEntity>, b: List<ChapterEntity>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        if (x.url != y.url || x.title != y.title || x.variable != y.variable) return false
    }
    return true
}

/** 同上，拿会话手里的目录和库里读回的轻量投影比：一样就不必把整行读出来（见 ReaderViewModel.observeToc） */
@JvmName("sameReadingTocRows") // 和上面那个擦除后签名相同
internal fun sameReadingToc(a: List<ChapterEntity>, b: List<TocRow>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        if (x.url != y.url || x.title != y.title || x.variable != y.variable) return false
    }
    return true
}
