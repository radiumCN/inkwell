package com.radium.inkwell.core.source.detect

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 给识别出来的元素生成 CSS 选择器。
 *
 * 生成的选择器要拿去套**同站的其他书**，所以「在这一页里唯一」不够，还得**稳**：
 * 只用看起来是写死在模板里的 id / class（`list`、`content`、`nr1`），
 * 带长串数字的（`chapter_123456`、`book-8848`）是这本书 / 这一章自己的流水号，换一本就对不上。
 * 实在找不到稳的名字才退回 `:nth-of-type` 路径 —— 它对模板改版很脆，但起码这一站能用。
 */
internal object CssPaths {

    /** 在 [el] 所在文档里**恰好选中它一个**的选择器 */
    fun unique(el: Element): String {
        val doc = el.ownerDocument() ?: return el.cssSelector()
        return uniqueWithin(doc, el, depth = 0)
    }

    private fun uniqueWithin(doc: Document, el: Element, depth: Int): String {
        val tag = el.normalName()
        if (tag == "body" || tag == "html") return tag
        val id = el.id()
        if (isStable(id) && selectsOnly(doc, "#$id", el)) return "#$id"
        val classes = el.classNames().filter(::isStable)
        for (c in classes) {
            if (selectsOnly(doc, "$tag.$c", el)) return "$tag.$c"
        }
        val own = tag + classes.joinToString("") { ".$it" }
        if (classes.size > 1 && selectsOnly(doc, own, el)) return own
        // 自己不够独特：挂到父级下面。层数有限 —— 太长的链对改版极脆，不如直接用位置路径
        val parent = el.parent() ?: return el.cssSelector()
        if (depth >= MAX_DEPTH) return el.cssSelector()
        val up = uniqueWithin(doc, parent, depth + 1)
        if (selectsOnly(doc, "$up > $own", el)) return "$up > $own"
        val nth = "$up > $tag:nth-of-type(${nthOfType(el)})"
        return if (selectsOnly(doc, nth, el)) nth else el.cssSelector()
    }

    /**
     * 像模板里写死的名字：合法 CSS 标识符，且没有三位以上的连续数字。
     * `nr1`、`h1`、`col-md-8` 算稳；`chapter_123456`、`post-20240101` 不算。
     */
    fun isStable(name: String): Boolean =
        name.isNotEmpty() && STABLE_NAME.matches(name) && !LONG_DIGITS.containsMatchIn(name)

    fun selectsOnly(doc: Document, selector: String, el: Element): Boolean {
        val got = select(doc, selector) ?: return false
        return got.size == 1 && got[0] === el
    }

    /** 选择器不合法时返回 null：类名里偶尔有 jsoup 不认的字符 */
    fun select(doc: Document, selector: String): List<Element>? =
        runCatching { doc.select(selector).toList() }.getOrNull()

    private fun nthOfType(el: Element): Int {
        var n = 1
        var sib = el.previousElementSibling()
        while (sib != null) {
            if (sib.normalName() == el.normalName()) n++
            sib = sib.previousElementSibling()
        }
        return n
    }

    private const val MAX_DEPTH = 4
    private val STABLE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")
    private val LONG_DIGITS = Regex("\\d{3,}")
}
