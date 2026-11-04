package com.radium.inkwell.core.source.detect

import com.radium.inkwell.core.source.BookInfoRuleSet
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.BookSourceTypes
import com.radium.inkwell.core.source.ContentRuleSet
import com.radium.inkwell.core.source.FetchedPage
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.FetchTrace
import com.radium.inkwell.core.source.RuleTrace
import com.radium.inkwell.core.source.RuleTraceCollector
import com.radium.inkwell.core.source.PageRenderer
import com.radium.inkwell.core.source.PurifyRule
import com.radium.inkwell.core.source.js.ScriptRuntime
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.source.SearchRuleSet
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
 * @param fromChapterUrl 用户给的其实是一章（[BookInfoDetector.isChapterPage]），已顺着「目录」跳到书页；
 *   [bookUrl] 是跳过去的那一页
 */
data class BookPageAnalysis(
    val bookUrl: String,
    val tocUrl: String,
    val info: DetectedBookInfo,
    val tocLink: DetectedField?,
    val tocCandidates: List<TocCandidate>,
    val tocNextPage: DetectedField?,
    val rendered: Boolean = false,
    val fromChapterUrl: String? = null,
)

/** 只认书页、不认目录的结果（[SiteAnalyzer.peekBook]）：书页地址（章节页已跳过）+ 书籍信息 */
data class BookPeek(val bookUrl: String, val info: DetectedBookInfo)

/** 试抓时一个字段实际取到的东西（[SiteAnalyzer.dryRun]）；[error] 非空说明这条规则求值时抛了错 */
data class FieldSample(val output: String?, val error: String? = null)

/**
 * 按一份规则把详情 → 目录 → 正文（→ 搜索）完整走一遍的结果，给「高级」页逐栏显示。
 *
 * @param samples 每个字段**第一次**求值的结果（目录各章的书名规则会求值几千次，只看第一章）；
 *   正文一栏放的是净化**之后**的正文开头，替换规则改得对不对在这里看
 * @param failures 阶段（[STAGE_DETAIL] 等）→ 整段失败的原因。前一段失败，后一段就没跑
 */
data class DryRun(
    val samples: Map<RuleField, FieldSample>,
    val failures: Map<String, String>,
    val chapterCount: Int,
    val sampleChapterTitle: String?,
    val searchCount: Int?,
)

/** 书源的搜索部分：`searchUrl` + `ruleSearch` */
data class SearchSetup(val searchUrl: String, val rules: SearchRuleSet)

/**
 * 搜索识别的结果。[results] 是**引擎**按 [setup] 真搜出来的（不是识别器自己数的），界面拿它给用户核对。
 */
data class SearchAnalysis(val setup: SearchSetup, val keyword: String, val results: List<SearchResult>)

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
    private val scriptRuntime: ScriptRuntime? = null,
    private val globalPurify: (BookSourceRule) -> List<PurifyRule> = { emptyList() },
) {

    /**
     * 搜索的抓取与验证走一个不带脚本、不带渲染的引擎：生成的搜索规则里没有脚本，
     * 搜索也从不走渲染回退（会向上百个源扇出）—— 这里跑得通，正式引擎里就跑得通。
     */
    private val engine = BookSourceEngine(http)

    /** 公开入口自己切 IO（与 [com.radium.inkwell.core.source.BookSourceEngine] 同约定），调用方在哪个线程都安全 */
    suspend fun analyzeBook(url: String): BookPageAnalysis = withContext(Dispatchers.IO) {
        val entry = loadEntry(url, "打不开这个网址")
        val book = bookPageOf(entry)
        val fromChapter = entry.location().takeIf { book !== entry }
        val plain = inspectBook(book, render = false).copy(fromChapterUrl = fromChapter)
        if (plain.tocCandidates.isNotEmpty() || renderer == null) return@withContext plain
        val rendered = load(book.location(), render = true) ?: return@withContext plain
        inspectBook(rendered, render = true).copy(fromChapterUrl = fromChapter)
            .takeIf { it.tocCandidates.isNotEmpty() } ?: plain
    }

    suspend fun analyzeChapter(url: String): ChapterPageAnalysis = withContext(Dispatchers.IO) {
        val doc = loadEntry(url, "打不开这一章")
        val plain = inspectChapter(doc, render = false)
        val best = plain.contentCandidates.firstOrNull()?.textLength ?: 0
        if (best >= MIN_CONTENT_CHARS || renderer == null) return@withContext plain
        val rendered = load(url, render = true)?.let { inspectChapter(it, render = true) } ?: return@withContext plain
        val renderedBest = rendered.contentCandidates.firstOrNull()?.textLength ?: 0
        if (renderedBest > best) rendered else plain
    }

    /**
     * 认搜索：按 [searchUrl] 模板（[SearchTemplates] 从用户在网页里搜的那一次生成）用引擎抓结果页，
     * 认出结果列表，再用引擎真搜一次验证。[bookUrl] 是向导前面认过的那本书，它的地址形状是认书链接的锚点。
     *
     * @throws SourceException 抓不到、认不出、或认出来的规则引擎搜不出东西；消息是给用户看的那句话
     */
    suspend fun analyzeSearch(searchUrl: String, keyword: String, bookUrl: String): SearchAnalysis =
        withContext(Dispatchers.IO) {
            val probe = BookSourceRule(
                bookSourceUrl = SourceDraftBuilder.siteOrigin(bookUrl),
                searchUrl = searchUrl,
                ruleSearch = SearchRuleSet(),
            )
            val page = engine.fetchSearchPage(probe, keyword)
            val doc = Jsoup.parse(page.bodyText, page.finalUrl)
            when (val found = SearchListDetector.detect(doc, keyword, SiteLinks.shapeOf(bookUrl))) {
                is SearchDetection.TooFew -> throw SourceException(
                    if (found.count == 1) {
                        "这次只搜到一本书，看不出结果列表的样子。换个常见的字（比如「$SUGGESTED_KEYWORD」）再搜一次"
                    } else {
                        "结果页上没认出书的列表。确认网页上搜出了结果再点；有的网站搜到唯一一本时会直接跳进书页"
                    },
                )
                is SearchDetection.Found -> {
                    val rule = probe.copy(ruleSearch = found.rules)
                    val results = engine.search(rule, keyword).items
                    if (results.isEmpty()) throw SourceException("按识别结果没搜出东西")
                    SearchAnalysis(SearchSetup(searchUrl, found.rules), keyword, results)
                }
            }
        }

    /**
     * 用户在网页上点了目录里的一章：围绕这条链接重认目录（[TocDetector.detectAround]）。
     * 网页（WebView）里的链接在静态 HTML 里找不到时，再用渲染后的页面认一次 —— 目录是脚本画出来的站就是这样。
     * 用的是刚认过的那份页面（[recentPages]），点一下不再下载一遍。
     */
    suspend fun pickToc(tocUrl: String, linkUrl: String): TocCandidate? = withContext(Dispatchers.IO) {
        recallOrLoad(tocUrl, render = false)?.let { TocDetector.detectAround(it, linkUrl) }
            ?: if (renderer != null) recallOrLoad(tocUrl, render = true)?.let { TocDetector.detectAround(it, linkUrl) } else null
    }

    /** 用户在网页上点了正文里的一段：围绕点中的文字重认正文块（[ContentDetector.detectAround]） */
    suspend fun pickContent(chapterUrl: String, snippet: String): ContentCandidate? = withContext(Dispatchers.IO) {
        recallOrLoad(chapterUrl, render = false)?.let { ContentDetector.detectAround(it, snippet) }
            ?: if (renderer != null) recallOrLoad(chapterUrl, render = true)?.let { ContentDetector.detectAround(it, snippet) } else null
    }

    /** 向导退出时调：清掉为点选留着的那几页 DOM，单例别一直攥着 */
    fun forgetPages() = synchronized(recentPages) { recentPages.clear() }

    /**
     * 按 [rule] 用引擎把整条链路走一遍，记下每个字段取到了什么（见 [DryRun]）。
     * 引擎配置与正式的一样（同一个抓取客户端、脚本运行时、渲染回退、全局净化）：这里抓得到，加书后就抓得到；
     * 少了脚本运行时，写 `@js:` 的规则在这里说不行、存进去却能用。
     *
     * @param keyword 非空且规则带搜索时，最后再搜一次
     */
    suspend fun dryRun(rule: BookSourceRule, bookUrl: String, keyword: String?): DryRun = withContext(Dispatchers.IO) {
        val traces = java.util.Collections.synchronizedList(ArrayList<RuleTrace>())
        val eng = BookSourceEngine(
            http,
            globalPurify = globalPurify,
            trace = object : RuleTraceCollector {
                override fun onFetch(trace: FetchTrace) = Unit
                override fun onRule(trace: RuleTrace) {
                    traces += trace
                }
            },
            scriptRuntime = scriptRuntime,
            renderer = renderer,
        )
        val failures = LinkedHashMap<String, String>()
        val detail = stage(STAGE_DETAIL, failures) { eng.getDetail(rule, bookUrl) }
        val chapters = detail?.let { d ->
            stage(STAGE_TOC, failures) { eng.getToc(rule, d.tocUrl, bookUrl = bookUrl, variables = d.variables) }
        }.orEmpty()
        val sample = chapters.take(SAMPLE_WINDOW).firstOrNull { ChapterTitles.isRegularChapter(it.title) }
            ?: chapters.firstOrNull()
        val content = sample?.let { c ->
            stage(STAGE_CONTENT, failures) {
                eng.getContent(
                    rule, c.url, chapters.mapTo(HashSet()) { it.url },
                    chapterVariable = c.variable, chapterTitle = c.title, chapterIndex = c.index,
                    bookUrl = bookUrl, variables = detail?.variables.orEmpty(),
                )
            }
        }
        val search = if (!keyword.isNullOrBlank() && rule.search != null) {
            stage(STAGE_SEARCH, failures) { eng.search(rule, keyword) }
        } else {
            null
        }
        // 每栏取第一条「取到了东西」的记录，都没取到才退回第一条。要渲染的站，引擎先静态抓一遍（全空）、
        // 再渲染：只看第一条的话，概览说「目录 30 章」，章节列表那栏却是「0 个节点」，诱人去改一条没毛病的规则
        val chosen = HashMap<Pair<String, String>, RuleTrace>()
        for (t in traces.toList()) {
            val key = t.stage to t.fieldName
            val held = chosen[key]
            if (held == null || (!foundSomething(held) && foundSomething(t))) chosen[key] = t
        }
        val samples = HashMap<RuleField, FieldSample>()
        for (f in RuleField.entries) {
            chosen[f.stage to f.traceName]?.let { samples[f] = FieldSample(it.output, it.error) }
        }
        content?.elements
            ?.filterIsInstance<com.radium.inkwell.core.model.ContentElement.Paragraph>()
            ?.take(PREVIEW_PARAGRAPHS)
            ?.joinToString("\n") { it.text }
            ?.let { samples[RuleField.Content] = FieldSample(it.take(PREVIEW_CHARS)) }
        DryRun(samples, failures, chapters.size, sample?.title, search?.items?.size)
    }

    /** 跑一段，失败记一句话、返回 null；取消照常往外抛 */
    private suspend fun <T> stage(name: String, failures: MutableMap<String, String>, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failures[name] = e.message ?: e.javaClass.simpleName
        null
    }

    /** 这条记录取到了东西：有输出、不是空串；列表规则的输出是「N 个节点」，0 个不算 */
    private fun foundSomething(t: RuleTrace): Boolean {
        val out = t.output ?: return false
        if (out.isBlank()) return false
        return !(t.fieldName == "list" && out.startsWith("0 "))
    }

    /**
     * 只认书页地址与书名、作者、封面、简介，不找目录。用已有书源按网址打开一本书时用：
     * 给的若是一章，书页地址要换成目录那页（书源的详情规则是照书页写的）；预览页还要个书名占位 ——
     * 书源自己的详情规则未必认得出（不少源的书名只在搜索结果里给）。抓不到就原样返回 [url]、信息为空。
     */
    suspend fun peekBook(url: String): BookPeek = withContext(Dispatchers.IO) {
        val entry = load(url, render = false) ?: return@withContext BookPeek(url, DetectedBookInfo())
        val book = bookPageOf(entry)
        BookPeek(book.location(), BookInfoDetector.detect(book))
    }

    /**
     * 入口页若是一章，顺着「目录」（没有就「返回书页」）跳到书页；不是章节页、或跳不过去就原样返回。
     * 跳过去的那页本身可能还是详情页，完整目录在更深一层 —— 那一步由 [inspectBook] 照常处理。
     */
    private suspend fun bookPageOf(entry: Document): Document {
        if (!BookInfoDetector.isChapterPage(entry)) return entry
        val link = BookInfoDetector.findTocLink(entry) ?: BookInfoDetector.findBookPageLink(entry) ?: return entry
        return load(link.value, render = false) ?: entry
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
     * 抓一页并解析。失败返回 null 由调用方决定怎么兜：「查看目录」点进去打不开只是放弃这条路、
     * 用详情页上认出来的。入口页走 [loadEntry]，要把原因说清。取消照常往外抛。
     */
    private suspend fun load(url: String, render: Boolean): Document? = try {
        fetchDoc(url, render)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * 入口页（用户给的网址、样本章）：抓不到要说清为什么。被拒、证书坏了、内网、找不到这个站，
     * 都不是「网址填错了」—— 从前一律报「打不开这个网址」，用户只会去换一个网址再试。
     */
    private suspend fun loadEntry(url: String, what: String): Document = try {
        fetchDoc(url, render = false) ?: throw SourceException("$what：$url")
    } catch (e: CancellationException) {
        throw e
    } catch (e: SourceException) {
        throw e
    } catch (e: Exception) {
        throw SourceException(explainFailure(what, e), e)
    }

    private suspend fun fetchDoc(url: String, render: Boolean): Document? {
        val page: FetchedPage = if (render) renderer?.render(url) ?: return null else http.fetch(url)
        val doc = Jsoup.parse(page.bodyText, page.finalUrl)
        synchronized(recentPages) {
            recentPages[pageKey(url, render)] = doc
            recentPages[pageKey(page.finalUrl, render)] = doc
        }
        return doc
    }

    private suspend fun recallOrLoad(url: String, render: Boolean): Document? =
        synchronized(recentPages) { recentPages[pageKey(url, render)] } ?: load(url, render)

    private fun pageKey(url: String, render: Boolean) = (if (render) "R " else "S ") + url

    /**
     * 最近认过的几页（解析好的 DOM），只给点选读：用户在网页上点一下，要围绕它重认的正是刚认过的那份页面，
     * 不必再下载一遍（目录要渲染的站还得和阅读器排队等 WebView）。识别入口照常现抓，
     * 别把上一次向导的旧页面认给这一次；向导退出时 [forgetPages] 清掉。
     */
    private val recentPages = object : LinkedHashMap<String, Document>(RECENT_PAGES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Document>?): Boolean =
            size > RECENT_PAGES
    }

    companion object {
        /** 静态页正文不到这么多字，多半是脚本渲染的，值得等一次 WebView */
        private const val MIN_CONTENT_CHARS = 100

        /** 为点选留着的页数：书页、目录页、样本章，静态与渲染各一份 */
        private const val RECENT_PAGES = 6

        private val HTTP_STATUS = Regex("^HTTP (\\d{3})")

        /** 抓取失败 → 给用户看的一句话（见 [loadEntry]） */
        internal fun explainFailure(what: String, e: Exception): String {
            val msg = e.message.orEmpty()
            val status = HTTP_STATUS.find(msg)?.groupValues?.get(1)?.toInt()
            return when {
                // 内网拦截也是 UnknownHostException，得先于「找不到网站」判断
                msg.startsWith("拒绝访问内网地址") -> "$what：这个地址指向本机或局域网，不允许访问"
                status == 401 || status == 403 || status == 503 ->
                    "$what：网站拒绝了访问（HTTP $status），可能要登录或人机验证"
                status == 404 -> "$what：网站上没有这一页（HTTP 404），检查一下网址"
                status != null -> "$what：网站出错了（HTTP $status）"
                e is javax.net.ssl.SSLException -> "$what：网站的安全证书有问题"
                e is java.net.UnknownHostException -> "$what：找不到这个网站，检查一下网址"
                e is java.io.InterruptedIOException -> "$what：网站响应太慢"
                else -> "$what：${msg.ifBlank { e.javaClass.simpleName }}"
            }
        }

        /** 试抓挑样本章的范围：开头这么多章里第一个正经章节（同 [TocCandidate.sampleChapter]） */
        private const val SAMPLE_WINDOW = 10
        private const val PREVIEW_PARAGRAPHS = 3
        private const val PREVIEW_CHARS = 300

        /** 建议用户在网页里搜的词：常见到几乎每个站都搜得出一串结果，一条结果认不出列表 */
        const val SUGGESTED_KEYWORD = "剑"
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
        /** 用户在网页里搜过一次、认出来了才有；没有就是不能搜索的书源 */
        search: SearchSetup? = null,
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
            searchUrl = search?.searchUrl,
            ruleSearch = search?.rules,
            ruleBookInfo = bookInfo,
            ruleToc = TocRuleSet(
                chapterList = toc.listRule,
                chapterName = "text",
                chapterUrl = "href",
                nextTocUrl = book.tocNextPage?.rule,
            ),
            ruleContent = ContentRuleSet(
                content = content.rule.ifBlank { null },
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
