package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import java.io.File
import java.security.MessageDigest

/**
 * 网络书正文磁盘缓存。
 *
 * 以**章节 URL** 而非序号为 key：站点在前面插入章节（公告章、防盗章）后目录整体后移，
 * 若按序号存取，第 5 章会读出旧的第 5 章正文 —— 缓存必须跟着章节走，而不是跟着槽位走。
 *
 * 行格式：普通段落直接一行；`H<级别>:` 标题；`IMG:` 图片 URL；`---` 分隔符。
 * 标题与分隔符必须原样存回：分页器对它们的处理和普通段落不同（标题另一套字号、
 * 分隔符占 1 个字符偏移），丢掉会让「首次分页」与「读缓存后分页」的页边界和
 * charOffset 对不上，恢复阅读位置就会漂。
 */
class ChapterContentCache(private val root: File) {

    private fun dir(bookId: String): File = File(root, bookId).apply { mkdirs() }

    private fun file(bookId: String, chapterUrl: String): File =
        File(dir(bookId), key(chapterUrl) + ".txt")

    private fun key(chapterUrl: String): String =
        MessageDigest.getInstance("MD5").digest(chapterUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun has(bookId: String, chapterUrl: String): Boolean = file(bookId, chapterUrl).isFile

    fun read(bookId: String, chapterUrl: String): ChapterContent? {
        val f = file(bookId, chapterUrl)
        if (!f.isFile) return null
        val elements = f.readLines().mapNotNull { line ->
            when {
                // 转义过的普通段落：它本来长得像标记行（H1:/IMG:/---），存时加了前缀，这里先剥掉。
                // 必须排在其它分支之前，否则又会被当成标记。
                line.startsWith(ESCAPE) -> ContentElement.Paragraph(line.substring(ESCAPE.length))
                line.isBlank() -> null
                line == DIVIDER -> ContentElement.Divider
                line.startsWith(IMG_PREFIX) -> ContentElement.Image(line.removePrefix(IMG_PREFIX))
                // 标题只认 `H<数字>:`。从前是「H 开头且含冒号」，会把 "He said: ..." 这类
                // 英文段落误判成标题、冒号前整段丢失。
                HEAD_REGEX.matchAt(line, 0) != null -> ContentElement.Heading(
                    level = line.getOrNull(HEAD_PREFIX.length)?.digitToIntOrNull() ?: 1,
                    text = line.substringAfter(':'),
                )
                else -> ContentElement.Paragraph(line)
            }
        }
        return ChapterContent(elements)
    }

    /** 段落文本恰好长得像标记行时要转义，否则读回会被误解析 */
    private fun escapeIfAmbiguous(text: String): String {
        val ambiguous = text == DIVIDER ||
            text.startsWith(IMG_PREFIX) ||
            text.startsWith(ESCAPE) ||
            HEAD_REGEX.matchAt(text, 0) != null
        return if (ambiguous) ESCAPE + text else text
    }

    fun write(bookId: String, chapterUrl: String, content: ChapterContent) {
        val text = content.elements.joinToString("\n") { el ->
            when (el) {
                is ContentElement.Paragraph -> escapeIfAmbiguous(el.text.replace('\n', ' '))
                is ContentElement.Heading -> "$HEAD_PREFIX${el.level}:${el.text.replace('\n', ' ')}"
                is ContentElement.Image -> IMG_PREFIX + el.resourceId
                ContentElement.Divider -> DIVIDER
            }
        }
        // 先写临时文件再原子改名：进程被杀留下的半截文件不会被 has()/read() 当成有效缓存，
        // 两个协程（前台加载 + 后台预取）同抓同章时也是「整文件替换」，不会交错出半截内容。
        val target = file(bookId, chapterUrl)
        val tmp = File.createTempFile("tmp", ".part", target.parentFile)
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) target.writeText(text)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 全部正文缓存占用的字节数（设置页要显示，用户得知道清的是多少东西） */
    fun sizeBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 清空所有书的正文缓存。目录与阅读进度不受影响，只是下次读要重新联网抓 */
    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun clear(bookId: String) {
        dir(bookId).deleteRecursively()
    }

    /** 删掉早先按序号命名的缓存（`0.txt`）：目录一变它们就名不副实，且再也不会被读到 */
    fun purgeLegacy(bookId: String) {
        dir(bookId).listFiles()
            ?.filter { it.nameWithoutExtension.toIntOrNull() != null }
            ?.forEach { it.delete() }
    }

    private companion object {
        const val IMG_PREFIX = "IMG:"
        const val HEAD_PREFIX = "H"
        const val DIVIDER = "---"
        /** 转义前缀：正常正文里不会出现的控制字符，老缓存里也没有，兼容旧文件 */
        const val ESCAPE = "\u0001"
        /** 标题行：`H` + 一或多位数字 + `:` */
        val HEAD_REGEX = Regex("H\\d+:")
    }
}
