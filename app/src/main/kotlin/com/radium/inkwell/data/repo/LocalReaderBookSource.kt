package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.reader.api.ReaderBookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本地书的阅读数据源：包装 BookHandle，IO 线程加载 */
class LocalReaderBookSource(private val handle: BookHandle) : ReaderBookSource, AutoCloseable {

    val toc get() = handle.toc
    internal val hasNestedToc = handle.toc.any { it.level > 0 }

    override val chapterCount: Int get() = handle.chapters.size

    override fun chapterTitle(index: Int): String? =
        handle.chapters.getOrNull(index)?.title

    override suspend fun loadChapter(index: Int): ChapterContent = withContext(Dispatchers.IO) {
        handle.loadChapter(index)
    }

    override suspend fun loadImage(resourceId: String): ByteArray? = withContext(Dispatchers.IO) {
        handle.loadResource(resourceId)?.data
    }

    /**
     * 本地书没有可预热的缓存：默认实现（= loadChapter）会把后面几章完整解析一遍（EPUB 解压 + Jsoup）
     * 再整个扔掉。阅读页在每章首尾几页都会发一轮预取 —— 正是代码里说的「翻页时 GC 停顿掉帧」那种分配。
     * 本地读取本来就快，用到时再解析即可。
     */
    override suspend fun prefetchChapter(index: Int) = Unit

    override fun close() = handle.close()
}
