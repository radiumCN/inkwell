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

    /**
     * 往后预取用。默认等于 [loadChapter]。
     * 网络书覆盖成「只走静态 HTTP」：预取开 WebView 会把主线程让给 Chromium，翻页跟手被抢走。
     */
    suspend fun prefetchChapter(index: Int): Unit {
        loadChapter(index)
    }
}
