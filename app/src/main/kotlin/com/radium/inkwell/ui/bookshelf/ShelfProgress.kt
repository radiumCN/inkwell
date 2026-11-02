package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.data.db.entity.BookEntity

/**
 * 书读到哪了（0..1），按章算。没打开过、或目录还没有章数时给 null ——
 * 那种书不画进度条，而不是画一条空轨：一架子没读过的书底下全是灰线，看着像一排坏掉的进度。
 *
 * 只按章不按字：书行里只有章内字符偏移，没有每章的长度，硬折算出来的「34.7%」精度是假的。
 * 读到第 1 章也算读了一点（+1），免得刚开的书显示 0%。
 */
internal fun BookEntity.readingProgress(): Float? {
    if (lastActiveAt() <= 0L || totalChapters <= 0) return null
    return ((readChapterIndex + 1).toFloat() / totalChapters).coerceIn(0f, 1f)
}

/** 最近一次「在读」的时间：打开或翻动过。与书架排序（`MAX(readAt, openedAt)`）同一个口径 */
internal fun BookEntity.lastActiveAt(): Long = maxOf(readAt, openedAt)

/** 「继续阅读」卡片要的东西。章节名单独查（书行里只有章号） */
data class ContinueReading(
    val book: BookEntity,
    val chapterTitle: String?,
)
