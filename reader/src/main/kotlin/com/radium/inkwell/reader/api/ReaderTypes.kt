package com.radium.inkwell.reader.api

import com.radium.inkwell.core.model.ChapterContent

/** 阅读位置的真身：章节索引 + 章内字符偏移。页码只是当前排版下的投影。 */
data class ReadPosition(
    val chapterIndex: Int,
    val charOffset: Int,
)

enum class FlipDirection { FORWARD, BACKWARD }

/**
 * 引擎的上游数据供给者。本地书由 app 层包装 BookHandle 实现；
 * 网络书由 app 层包装书源引擎 + 缓存实现。
 */
interface ReaderBookSource {
    val chapterCount: Int
    fun chapterTitle(index: Int): String?
    /** 可挂起（网络章节）；抛异常 = 章节加载失败 */
    suspend fun loadChapter(index: Int): ChapterContent
    /** 图片字节；本地书走 BookHandle.loadResource，网络书按 URL 下载 */
    suspend fun loadImage(resourceId: String): ByteArray?
}

sealed interface ReaderEvent {
    data class PageTurned(val position: ReadPosition, val pageInChapter: Int, val pageCount: Int) : ReaderEvent
    data class ChapterEntered(val chapterIndex: Int, val title: String) : ReaderEvent
    data class ChapterLoadFailed(val chapterIndex: Int, val error: Throwable) : ReaderEvent
    data object BookEndReached : ReaderEvent
}
