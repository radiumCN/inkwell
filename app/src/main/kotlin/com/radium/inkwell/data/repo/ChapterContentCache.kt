package com.radium.inkwell.data.repo

import android.content.Context
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import java.io.File

/**
 * 网络书正文磁盘缓存。格式：每段一行；图片行以 IMG: 前缀存 URL。
 * 换源后整目录删除。
 */
class ChapterContentCache(private val context: Context) {

    private fun dir(bookId: String): File =
        File(File(context.filesDir, "cache"), bookId).apply { mkdirs() }

    private fun file(bookId: String, index: Int): File = File(dir(bookId), "$index.txt")

    fun has(bookId: String, index: Int): Boolean = file(bookId, index).isFile

    fun read(bookId: String, index: Int): ChapterContent? {
        val f = file(bookId, index)
        if (!f.isFile) return null
        val elements = f.readLines().mapNotNull { line ->
            when {
                line.isBlank() -> null
                line.startsWith(IMG_PREFIX) -> ContentElement.Image(line.removePrefix(IMG_PREFIX))
                else -> ContentElement.Paragraph(line)
            }
        }
        return ChapterContent(elements)
    }

    fun write(bookId: String, index: Int, content: ChapterContent) {
        val text = content.elements.mapNotNull { el ->
            when (el) {
                is ContentElement.Paragraph -> el.text.replace('\n', ' ')
                is ContentElement.Heading -> el.text.replace('\n', ' ')
                is ContentElement.Image -> IMG_PREFIX + el.resourceId
                ContentElement.Divider -> null
            }
        }.joinToString("\n")
        file(bookId, index).writeText(text)
    }

    fun clear(bookId: String) {
        dir(bookId).deleteRecursively()
    }

    private companion object {
        const val IMG_PREFIX = "IMG:"
    }
}
