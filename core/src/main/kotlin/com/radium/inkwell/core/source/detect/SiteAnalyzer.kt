package com.radium.inkwell.core.source.detect

import com.radium.inkwell.core.source.BookInfoRuleSet
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.BookSourceTypes
import com.radium.inkwell.core.source.ContentRuleSet
import com.radium.inkwell.core.source.FetchedPage
import com.radium.inkwell.core.source.PageRenderer
import com.radium.inkwell.core.source.SourceException
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.TocRuleSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 书页（详情页或目录页）的识别结果。
 *
 * @param bookUrl 用户给的那一页（跟随跳转后的地址），加书时当 bookUrl 存
 * @param tocUrl 实际识别出目录的那一页：与 [bookUrl] 相同，或经 [tocLink] 点进去的另一页
 * @param tocLink 目录在另一页时，详情页上指向它的规则（→ `ruleBookInfo.tocUrl`）
 * @param tocNextPage 目录分页时「下一页」的规则（→ `ruleToc.nextTocUrl`）
 * @param rendered 静态 HTML 里认不出目录、是 WebView 执行完页面脚本后才认出来的
 */
data class BookPageAnalysis(
    val bookUrl: String,
    val tocUrl: String,
    val info: DetectedBookInfo,
    val tocLink: DetectedField?,
    val tocCandidates: List<TocCandidate>,
    val tocNextPage: DetectedField?,
    val rendered: Boolean = false,
)

/** 章节页的识别结果；[nextPage] → `ruleContent.nextContentUrl`（一章分几页的站） */
data class ChapterPageAnalysis(
    val url: String,
    val contentCandidates: List<ContentCandidate>,
    val nextPage: DetectedField?,
    val rendered: Boolean = false,
)

/**
 * 「新建书源」的识别入口：给网址，抓页面，认结构。
 *
 * 只在建源时跑一次，产物（[SourceDraftBuilder]）是一份普通的 Legado 书源 —— 之后的加书、追更、
 * 换源、WebDAV 同步都走原有引擎，与导入的书源没有区别。识别逻辑本身是纯函数
 * （[TocDetector] / [ContentDetector] / [BookInfoDetector]），这里只管抓取与两条兜底：
 * - 目录在另一页（手机站详情页只列最新章节）→ 跟着「查看目录」点进去再认一次，取章节多的那页；
 * - 静态 HTML 里什么都认不出 → 交给 [PageRenderer] 执行页面脚本后再认（与引擎的渲染回退同一个实现）。
 */
class SiteAnalyzer(
    private val http: SourceHttpClient,
    private val renderer: PageRenderer? = null,
) {

    /** 公开入口自己切 IO（与 [com.radium.inkwell.core.source.BookSourceEngine] 同约定），调用方在哪个线程都安全 */
    suspend fun analyzeBook(url: String): BookPageAnalysis = withContext(Dispatchers.IO) {
        val doc = load(url, render = false) ?: throw SourceException("打不开这个网址：$url")
        val plain = inspectBook(doc, render = false)
        if (plain.tocCandidates.isNotEmpty() || renderer == null) return@withContext plain
        val rendered = load(url, render = true) ?: return@withContext plain
        inspectBook(rendered, render = true).takeIf { it.tocCandidates.isNotEmpty() } ?: plain
    }

    suspend fun analyzeChapter(url: String): ChapterPageAnalysis = withContext(Dispatchers.IO) {
        val doc = load(url, render = false) ?: throw SourceException("打不开这一章：$url")
        val plain = inspectChapter(doc, render = false)
        val best = plain.contentCandidates.firstOrNull()?.textLength ?: 0
        if (best >= MIN_CONTENT_CHARS || renderer == null) return@withContext plain
        val rendered = load(url, render = true)?.let { inspectChapter(it, render = true) } ?: return@withContext plain
        val renderedBest = rendered.contentCandidates.firstOrNull()?.textLength ?: 0
        if (renderedBest > best) rendered else plain
    }

    /**
     * 只认书名、作者、封面、简介，不找目录。用已有书源按网址打开一本书时，预览页要个书名占位 ——
     * 书源自己的详情规则未必认得出（不少源的书名只在搜索结果里给）。一页静态抓取，失败返回空。
     */
    suspend fun peekBookInfo(url: String): DetectedBookInfo = withContext(Dispatchers.IO) {
        load(url, render = false)?.let { BookInfoDetector.detect(it) } ?: DetectedBookInfo()
    }

    private suspend fun inspectBook(doc: Document, render: Boolean): BookPageAnalysis {
        val info = BookInfoDetector.detect(doc)
        val here = TocDetector.detect(doc)
        val link = BookInfoDetector.findTocLink(doc)
        if (link != null) {
            val tocDoc = load(link.value, render)
            val there = tocDoc?.let { TocDetector.detect(it) }.orEmpty()
            val hereCount = here.firstOrNull()?.chapters?.size ?: 0
            if (tocDoc != null && there.isNotEmpty() && there[0].chapters.size >= hereCount) {
                return BookPageAnalysis(
                    bookUrl = doc.location(),
                    tocUrl = tocDoc.location(),
                    info = info,
                    tocLink = link,
                    tocCandidates = there,
                    tocNextPage = SiteLinks.findNextPage(tocDoc),
                    rendered = render,
                )
            }
        }
        return BookPageAnalysis(
            bookUrl = doc.location(),
            tocUrl = doc.location(),
            info = info,
            tocLink = null,
            tocCandidates = here,
            tocNextPage = SiteLinks.findNextPage(doc),
            rendered = render,
        )
    }

    private fun inspectChapter(doc: Document, render: Boolean) = ChapterPageAnalysis(
        url = doc.location(),
        contentCandidates = ContentDetector.detect(doc),
        nextPage = SiteLinks.findNextPage(doc),
        rendered = render,
    )

    /**
     * 抓一页并解析。失败返回 null 由调用方决定怎么兜：入口页打不开要报错，
     * 「查看目录」点进去打不开只是放弃这条路、用详情页上认出来的。取消照常往外抛。
     */
    private suspend fun load(url: String, render: Boolean): Document? {
        val page: FetchedPage = try {
            if (render) {
                renderer?.render(url) ?: return null
            } else {
                http.fetch(url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return Jsoup.parse(page.bodyText, page.finalUrl)
    }

    private companion object {
        /** 静态页正文不到这么多字，多半是脚本渲染的，值得等一次 WebView */
        const val MIN_CONTENT_CHARS = 100
    }
}

/** 把识别结果拼成一份标准 Legado 书源 */
object SourceDraftBuilder {

    /**
     * @param name 书源名，界面上让用户改（默认可用站点域名）
     * @param group 分组，界面上统一给一个（如「自建」），方便和导入的书源分开管理
     */
    fun build(
        name: String,
        book: BookPageAnalysis,
        toc: TocCandidate,
        chapter: ChapterPageAnalysis,
        content: ContentCandidate,
        group: String = "",
    ): BookSourceRule {
        val info = book.info
        val bookInfo = BookInfoRuleSet(
            name = info.name?.rule,
            author = info.author?.rule,
            coverUrl = info.coverUrl?.rule,
            intro = info.intro?.rule,
            tocUrl = book.tocLink?.rule,
        ).takeIf { !info.isEmpty || book.tocLink != null }
        return BookSourceRule(
            bookSourceUrl = siteOrigin(book.bookUrl),
            bookSourceName = name,
            bookSourceGroup = group,
            bookSourceType = BookSourceTypes.TEXT,
            bookSourceComment = "由「新建书源」根据 ${book.bookUrl} 自动识别生成",
            ruleBookInfo = bookInfo,
            ruleToc = TocRuleSet(
                chapterList = toc.listRule,
                chapterName = "text",
                chapterUrl = "href",
                nextTocUrl = book.tocNextPage?.rule,
            ),
            ruleContent = ContentRuleSet(
                content = content.rule,
                nextContentUrl = chapter.nextPage?.rule,
            ),
        )
    }

    /** `https://www.example.com/book/1/` → `https://www.example.com`（书源地址 = 站点根） */
    fun siteOrigin(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        val port = if (u.port == okhttp3.HttpUrl.defaultPort(u.scheme)) "" else ":${u.port}"
        return "${u.scheme}://${u.host}$port"
    }

    /** 默认书源名：去掉 `www.` 的域名。书源列表里认站点靠它，用户可以改 */
    fun defaultName(url: String): String =
        url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url
}
