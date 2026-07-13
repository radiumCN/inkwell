package com.radium.inkwell.core.model

import java.io.Closeable
import java.io.File

data class BookMetadata(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val language: String? = null,
    val identifier: String? = null,
    val cover: ImageBlob? = null,
)

class ImageBlob(
    val data: ByteArray,
    val mimeType: String? = null,
)

/** 逻辑目录条目；chapterIndex 指向物理章节列表下标 */
data class TocEntry(
    val title: String,
    val chapterIndex: Int,
    val anchor: String? = null,
    val level: Int = 0,
)

/** 物理章节（EPUB spine item / txt 正则切片 / MOBI filepos 切片） */
data class ChapterRef(
    val index: Int,
    val title: String,
)

sealed interface ContentElement {
    data class Paragraph(val text: String) : ContentElement
    data class Heading(val level: Int, val text: String) : ContentElement
    /** resourceId 对本地书是解析器内部标识，对网络书是图片绝对 URL */
    data class Image(val resourceId: String, val alt: String? = null) : ContentElement
    data object Divider : ContentElement
}

val ContentElement.charLength: Int
    get() = when (this) {
        is ContentElement.Paragraph -> text.length
        is ContentElement.Heading -> text.length
        is ContentElement.Image -> 1
        ContentElement.Divider -> 1
    }

data class ChapterContent(
    val elements: List<ContentElement>,
)

/** 已打开的书；章节内容惰性加载 */
interface BookHandle : Closeable {
    val metadata: BookMetadata
    val chapters: List<ChapterRef>
    val toc: List<TocEntry>
    fun loadChapter(index: Int): ChapterContent
    fun loadResource(resourceId: String): ImageBlob?
}

interface BookParser {
    /** 通过魔数/结构判断（不只靠扩展名） */
    fun sniff(file: File): Boolean
    @Throws(BookParseException::class)
    fun open(file: File): BookHandle
}

sealed class BookParseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class DrmProtected(message: String) : BookParseException(message)
    class UnsupportedFeature(message: String) : BookParseException(message)
    class Corrupted(message: String, cause: Throwable? = null) : BookParseException(message, cause)
}
