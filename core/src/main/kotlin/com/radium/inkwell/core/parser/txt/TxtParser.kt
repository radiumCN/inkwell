package com.radium.inkwell.core.parser.txt

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookMetadata
import com.radium.inkwell.core.model.BookParseException
import com.radium.inkwell.core.model.BookParser
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ChapterRef
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.ImageBlob
import com.radium.inkwell.core.model.TocEntry
import java.io.File

class TxtParser(
    private val splitConfig: TxtChapterSplitter.Config = TxtChapterSplitter.Config(),
) : BookParser {

    override fun sniff(file: File): Boolean {
        if (!file.extension.equals("txt", ignoreCase = true)) return false
        return file.isFile && file.length() > 0
    }

    override fun open(file: File): BookHandle {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            throw BookParseException.Corrupted("读取文件失败: ${file.name}", e)
        }
        val head = bytes.copyOfRange(0, minOf(bytes.size, 64 * 1024))
        val charset = EncodingDetector.detect(head)
        val bom = EncodingDetector.bomLength(head)
        val text = String(bytes, bom, bytes.size - bom, charset)
        val splits = TxtChapterSplitter.split(text, splitConfig)
        if (splits.isEmpty()) throw BookParseException.Corrupted("空文件: ${file.name}")
        return TxtBookHandle(file.nameWithoutExtension, text, splits)
    }
}

private class TxtBookHandle(
    title: String,
    private val text: String,
    private val splits: List<TxtChapterSplitter.Split>,
) : BookHandle {

    override val metadata = BookMetadata(title = title)

    override val chapters: List<ChapterRef> =
        splits.mapIndexed { i, s -> ChapterRef(i, s.title) }

    override val toc: List<TocEntry> =
        splits.mapIndexed { i, s -> TocEntry(s.title, i, level = s.level) }

    override fun loadChapter(index: Int): ChapterContent {
        val split = splits[index]
        val body = text.substring(split.start, split.end)
        val paragraphs = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterIndexed { i, line -> !(i == 0 && line == split.title) } // 标题行由排版层渲染
            .map { ContentElement.Paragraph(it) as ContentElement }
            .toList()
        return ChapterContent(paragraphs)
    }

    override fun loadResource(resourceId: String): ImageBlob? = null

    override fun close() {}
}
