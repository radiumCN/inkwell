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
        val lines = f.readLines()
        // 版本对不上就当没缓存，逼它重抓一遍。
        // 这里存的是**解析结果**而不是原始 HTML —— 解析器修好了，磁盘上那份旧结果并不会
        // 跟着变好。没有这道校验的话，用户已经读过（也就是最想看到修复效果）的章节
        // 会永远停在旧解析上，升级完一看一模一样。见 [FORMAT_VERSION]。
        if (lines.firstOrNull() != FORMAT_VERSION) return null
        val elements = lines.drop(1).mapNotNull { line ->
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
        val body = content.elements.joinToString("\n") { el ->
            when (el) {
                is ContentElement.Paragraph -> escapeIfAmbiguous(el.text.replace('\n', ' '))
                is ContentElement.Heading -> "$HEAD_PREFIX${el.level}:${el.text.replace('\n', ' ')}"
                is ContentElement.Image -> IMG_PREFIX + el.resourceId
                ContentElement.Divider -> DIVIDER
            }
        }
        // 文件名不变（仍是 MD5(url)），所以重抓时是**覆盖**同名文件 —— 旧版本的内容
        // 自然被替换掉，不会在磁盘上留一份读不到又删不掉的垃圾
        val text = FORMAT_VERSION + "\n" + body
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
        /**
         * 缓存格式 / 解析器版本。**任何会改变正文解析结果的改动都必须 +1。**
         *
         * 范围是**整条解析链**，不只是某一个类：书源规则求值（LegadoSelector 的 text/textNodes/
         * html 等提取器）、HtmlToElements 的切段、以及本文件的行格式 —— 任何一环改了输出，都得 +1。
         *
         * 缓存里躺的是解析好的段落，不是原始 HTML。不 +1 的话，用户**已经读过**的章节
         * （恰恰是最想看到修复效果的那些）会永远停在旧解析结果上，改了解析器也白改。
         *
         * v2：修好「靠原始换行分段的源整章挤成一坨」时才发现这件事 —— 当时没有版本号，
         * 用户升级后一看一模一样，因为坏结果早就缓存在磁盘上了。
         * v3：@textNodes 从前用 text()+trim() 取文本，换行被压成空格、段首全角缩进被剥掉，
         * 段落结构在规则求值阶段就没了。改动的是 LegadoSelector 而不是 HtmlToElements ——
         * 正是「范围是整条链」这句话要拦住的情况。
         *
         * 用 [ESCAPE] 打头，保证撞不上任何正文：真以 ESCAPE 开头的段落会被
         * escapeIfAmbiguous 再加一层前缀。老缓存没有这一行，读时自然对不上、当没缓存。
         */
        const val FORMAT_VERSION = "\u0001V3"

        const val IMG_PREFIX = "IMG:"
        const val HEAD_PREFIX = "H"
        const val DIVIDER = "---"
        /** 转义前缀：正常正文里不会出现的控制字符，老缓存里也没有，兼容旧文件 */
        const val ESCAPE = "\u0001"
        /** 标题行：`H` + 一或多位数字 + `:` */
        val HEAD_REGEX = Regex("H\\d+:")
    }
}
