package com.radium.inkwell.core.util

import com.radium.inkwell.core.model.ContentElement
import java.text.Normalizer

/**
 * 章节正文开头和章节名重复的那一两行。
 *
 * 排版层每章开头自己排一个大标题（章节名，见 reader 的 Paginator），而章节正文里往往也写着一遍：
 * EPUB 的章节文件几乎都以 `<h1>/<h2>` 标题开头，来源抓下来的正文第一行也常是章节名。两样都排出来，
 * 同一个「第一章 明道宫」一大一小叠两遍（被指出过）。txt 解析时只去掉和章节名一字不差的首行，多一个空格、
 * 一个句号就漏了。
 *
 * 只认「确实就是标题」的，宁可漏、不误删正文：
 * - **标题格式的行**（[ContentElement.Heading]，EPUB 的 h1~h6）：被章节名包含就算 —— 「第一章」「明道宫」
 *   这种只写了半截、或者拆成两行写的；
 * - **普通段落**：得和章节名（或「书名 + 章节名」）完全一样才算，拆成几段的拼起来一样也算；只是以章节名里的字开头的正文不动。
 *
 * 比较前去掉空白和标点、统一全半角与大小写（[key]）：「第一章　明道宫。」和「第一章 明道宫」是同一行。
 * 只看开头 [MAX_LEADING] 个元素，碰到第一段对不上的正文就停。
 *
 * 在显示时做、不在解析时做：已经缓存的正文、已经导进来的书都不用重来。
 */
object RepeatedTitle {

    /** 最多往下看几个元素：标题拆成两行、再跟一行带书名的，三四行足够；再往下就是正文了 */
    private const val MAX_LEADING = 4

    /** 标题行只写了半截时，至少要这么长才认 —— 单个字「一」也被「第一章」包含，那不算标题 */
    private const val MIN_PARTIAL = 2

    /**
     * 去掉 [elements] 开头和 [chapterTitle] 重复的元素；没有重复就原样返回同一个列表。
     *
     * @param bookTitle 书名：有的站在正文第一行写「书名 第一章 明道宫」，那一行同样是重复的标题
     */
    fun strip(elements: List<ContentElement>, chapterTitle: String, bookTitle: String = ""): List<ContentElement> {
        val title = key(chapterTitle)
        if (title.isEmpty() || elements.isEmpty()) return elements
        val book = key(bookTitle)
        val withBook = if (book.isEmpty()) null else book + title
        var cut = 0
        // 还没拼成整个标题的那几段（标题拆成两段写）。拼不成就整串作罢，cut 只记已经拼成的
        var pending = ""
        for (i in 0 until minOf(elements.size, MAX_LEADING)) {
            val el = elements[i]
            val text = when (el) {
                is ContentElement.Heading -> el.text
                is ContentElement.Paragraph -> el.text
                else -> break
            }
            val k = key(text)
            if (k.isEmpty()) break
            if (el is ContentElement.Heading && pending.isEmpty() && isTitleHeading(k, title, withBook)) {
                cut = i + 1
                continue
            }
            val joined = pending + k
            if (joined == title || joined == withBook) {
                cut = i + 1
                pending = ""
                continue
            }
            if (title.startsWith(joined) || withBook?.startsWith(joined) == true) {
                pending = joined
                continue
            }
            break
        }
        return if (cut == 0) elements else elements.subList(cut, elements.size).toList()
    }

    private fun isTitleHeading(k: String, title: String, withBook: String?): Boolean =
        k == title || k == withBook || (k.length >= MIN_PARTIAL && title.contains(k))

    /** 比较用的「骨架」：NFKC（全角数字字母 → 半角）、小写，只留字母和数字（汉字算字母） */
    internal fun key(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(normalized.length)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
