package com.radium.inkwell.core.source.rss

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
import com.radium.inkwell.core.source.detect.ContentDetector
import com.radium.inkwell.core.util.RepeatedTitle
import org.jsoup.nodes.Document

/**
 * 网页 → 文章正文：订阅只给了摘要时，去原网页把全文整理出来（浏览器「阅读模式」那种）。
 *
 * 正文块用来源向导认正文的那一套（[ContentDetector]：直接带着大段文字、中文标点密、链接少的那个元素），
 * 少数派的文章页一次就认对，导航、作者卡、评论都不混进来。文章和小说章节有三处不一样：
 * - 正文常分几节（一节一个 `<section>`），认出来的是字最多的那一节。同一个父元素下、得分够得上它
 *   [SIBLING_RATIO] 的兄弟一起收进来，按原来的顺序（Readability 的做法）。
 * - 图留着（[HtmlToElements] 的 keepImages）。`<noscript>` 先删掉：懒加载的站在里面放同一张图的备份，收进来就成了两张。
 * - 页面在正文开头又写一遍文章标题的，去掉（文章页上面已经有大标题了）。
 */
internal object ArticleExtractor {

    private val converter = HtmlToElements(keepImages = true)

    /** 认不出正文块时返回空列表 */
    fun extract(doc: Document, title: String): List<ContentElement> {
        doc.select("noscript").remove()
        val (best, score) = ContentDetector.best(doc) ?: return emptyList()
        val blocks = best.parent()
            ?.children()
            ?.filter { it === best || (ContentDetector.score(it) ?: 0.0) >= score * SIBLING_RATIO }
            ?: listOf(best)
        return RepeatedTitle.strip(blocks.flatMap { converter.convert(it) }, title)
    }

    /** 兄弟块的得分够得上最高分的这么多，算同一篇文章的另一节 */
    private const val SIBLING_RATIO = 0.2
}
