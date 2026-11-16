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

    // 下面三种只有订阅文章有（HtmlToElements 的 keepBlocks）。书（EPUB、书源正文）不产出它们，
    // 阅读器分页、章节缓存这些只认段落的地方经 [asParagraph] 退成一段字兜底，不至于整块丢掉。

    /** 代码块：原样的换行与缩进。[language] 是网页上标的语言（bash、kotlin），没标为 null */
    data class Code(val text: String, val language: String? = null) : ContentElement

    /** 列表的一项。[marker] 是「•」或「3.」；[depth] 是嵌套层数，0 起 */
    data class ListItem(val text: String, val marker: String, val depth: Int = 0) : ContentElement

    /** 引用。原文分了几段的，段与段之间是换行 */
    data class Quote(val text: String) : ContentElement
}

/**
 * 这一块在章内字符流里占几个偏移（阅读进度按它记）。图占 0 个：书有好几个版本不收图，
 * 那时存下的进度是在只有字的字符流上算的，图要是占位，升级后进度会往前错（见 reader 的 Paginator）
 */
val ContentElement.charLength: Int
    get() = when (this) {
        is ContentElement.Paragraph -> text.length
        is ContentElement.Heading -> text.length
        is ContentElement.Image -> 0
        ContentElement.Divider -> 1
        is ContentElement.Code -> text.length
        is ContentElement.ListItem -> text.length
        is ContentElement.Quote -> text.length
    }

/** 这一块的字（算字数、找摘要用）；图、分隔线没有字，为 null */
val ContentElement.textOrNull: String?
    get() = when (this) {
        is ContentElement.Paragraph -> text
        is ContentElement.Heading -> text
        is ContentElement.Code -> text
        is ContentElement.ListItem -> text
        is ContentElement.Quote -> text
        is ContentElement.Image, ContentElement.Divider -> null
    }

/**
 * 只认「段落、小标题、图、分隔线」的地方用：订阅文章才有的三种块退成一个普通段落（列表项带上圆点），其余原样。
 * 段落里不该有换行（阅读器按段排、章节缓存一行一段），一并换成空格。
 */
fun ContentElement.asParagraph(): ContentElement = when (this) {
    is ContentElement.Code -> ContentElement.Paragraph(text.replace(LINE_BREAKS, " ").trim())
    is ContentElement.ListItem -> ContentElement.Paragraph("$marker $text")
    is ContentElement.Quote -> ContentElement.Paragraph(text.replace(LINE_BREAKS, " ").trim())
    else -> this
}

private val LINE_BREAKS = Regex("\\s*\\n\\s*")

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

    /** 删掉这个文件留下的解析缓存（删书时调）。没有缓存的解析器不必实现 */
    fun evictCache(file: File) {}
}

sealed class BookParseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class DrmProtected(message: String) : BookParseException(message)
    class UnsupportedFeature(message: String) : BookParseException(message)
    class Corrupted(message: String, cause: Throwable? = null) : BookParseException(message, cause)
}
