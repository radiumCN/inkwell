package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.reader.api.ReaderBookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本地书的阅读数据源：包装 BookHandle，IO 线程加载 */
class LocalReaderBookSource(private val handle: BookHandle) : ReaderBookSource, AutoCloseable {

    override val chapterCount: Int get() = handle.chapters.size

    override fun chapterTitle(index: Int): String? =
        handle.chapters.getOrNull(index)?.title

    override suspend fun loadChapter(index: Int): ChapterContent = withContext(Dispatchers.IO) {
        handle.loadChapter(index)
    }

    override suspend fun loadImage(resourceId: String): ByteArray? = withContext(Dispatchers.IO) {
        handle.loadResource(resourceId)?.data
    }

    override fun close() = handle.close()
}
