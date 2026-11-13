package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** 书源业务错误（规则未配置/规则不匹配等） */
class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ---- 结果模型 ----

/** 可序列化：预览页需要整条带过去，详情页解析不出的字段（书名/作者/封面）由它回落 */
@kotlinx.serialization.Serializable
data class SearchResult(
    val title: String,
    val bookUrl: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val latestChapter: String? = null,
    /** 字数原文（「12万字」之类）；多数书源有，没有则为 null */
    val wordCount: String? = null,
    /**
     * 分类/状态原文。Legado 搜索规则没有独立「更新时间」字段，
     * 不少源会把日期写进 kind（如「连载 · 2024-01-15」），排序「更新日期」就靠从这里抠。
     */
    val kind: String? = null,
    val sourceId: String,
    /** 书源名称。只有 id（其实是书源网址）的话，换源列表里满屏都是域名，没人认得出哪个是哪个 */
    val sourceName: String = "",
    /**
     * 搜索这一步 `java.put` / `@put` 存下的变量（Legado 的 searchBook.variable）：进详情时经
     * [BookSourceEngine.getDetail] 的 `variables` 交回去，之后随书存、目录正文都读得到。
     * 接口源常在搜索地址的脚本里把签名用的请求头 put 下来，详情、目录地址再 `java.get` 拼上 ——
     * 不交回去，后面每一步都少了请求头（七猫的接口源就是这样 401 的）。
     */
    val variables: Map<String, String> = emptyMap(),
)

data class SearchPage(
    val items: List<SearchResult>,
    val hasMore: Boolean,
)

data class RemoteBookDetail(
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val intro: String?,
    val tocUrl: String,
    /**
     * 详情页规则用 `@put` / `java.put` 存下的**书级**变量。Legado 把它们挂在 Book 上，目录、正文规则的
     * `@get:{k}` / `java.get(k)` 都取得到（典型：详情页抠出书号，目录接口地址要拼它）。
     * 调用方随书保存，取目录/正文时经 [BookSourceEngine.getToc] / [BookSourceEngine.getContent] 的
     * `variables` 交回来；不传这条链路就是断的。
     */
    val variables: Map<String, String> = emptyMap(),
    /** 书源允许详情页改书名、作者（[BookInfoRuleSet.canReName]），见 [bookNameAndAuthor] */
    val canReName: Boolean = false,
)

data class RemoteChapter(
    val index: Int,
    val title: String,
    val url: String,
    /**
     * 本章的变量（JSON）：目录规则用 @put/java.put 存下的，含 chapterList 那一级存的目录级变量；
     * 正文规则会用 @get 取。从 `getToc(variables = …)` 传进来、目录阶段没改过的书级变量不在这里重复 ——
     * 它们随书存一份就够了。
     */
    val variable: String = "",
)

/** 正文；图片元素的 resourceId 为绝对 URL */
data class RemoteChapterContent(
    val elements: List<ContentElement>,
)

// ---- 调试插桩 ----

data class FetchTrace(
    val requestUrl: String,
    val method: String,
    val statusCode: Int,
    val finalUrl: String,
    val detectedCharset: String,
    val bodyPreview: String,
)

data class RuleTrace(
    val stage: String,
    val fieldName: String,
    val rule: String,
    val output: String?,
    val error: String? = null,
)

interface RuleTraceCollector {
    fun onFetch(trace: FetchTrace)
    fun onRule(trace: RuleTrace)
}

// ---- 引擎 ----

class BookSourceEngine(
    private val http: SourceHttpClient,
    /**
     * 用户自定义的全局净化规则。取的是**函数**不是列表：引擎是单例，规则随时可改，
     * 拿一份构造期的快照就永远读不到用户新加的规则。入参是当前书源和书名，供按作用范围筛选
     * （阅读的规则常在作用范围里写书名，见 [PurifyScope]）。
     */
    private val globalPurify: (source: BookSourceRule, bookTitle: String) -> List<PurifyRule> = { _, _ -> emptyList() },
    private val trace: RuleTraceCollector? = null,
    scriptRuntime: com.radium.inkwell.core.source.js.ScriptRuntime? = null,
    /** JS 渲染回退；未注入时行为与从前完全一致 */
    private val renderer: PageRenderer? = null,
) {

    private val jsHttp = com.radium.inkwell.core.source.js.EngineJsHttp(http)
    private val evaluator = RuleEvaluator(
        scriptRuntime,
        jsHttp = jsHttp,
    )

    /**
     * 已确认需要 JS 渲染的「书源 + 阶段」；后续同一级请求先走渲染器，省掉每次先静态空跑一遍。
     * 按阶段记：正文要渲染的站，目录往往是静态的，不该被连累成每页都等 WebView（见 [withRenderFallback]）。
     */
    private val needsRender = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 公开入口一律在 IO 线程自我确权。并把当前 Job 绑到 [jsHttp]，好让脚本里的
     * runBlocking 网络在外层协程取消时把 OkHttp Call 掐掉。
     *
     * 绑定走协程上下文元素而不是 set/remove：block 里第一次挂起后恢复到哪根 IO 线程
     * 不由我们定，裸 ThreadLocal 会在换线程后失联（详见 [EngineJsHttp.bindingTo]）。
     */
    private suspend fun <T> engineIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            withContext(jsHttp.bindingTo(coroutineContext[Job])) {
                try {
                    block()
                } catch (e: Exception) {
                    // 脚本里的 java.ajax 是同步桥：外层被取消时它只能交回空串，脚本随之「正常」跑完，
                    // 规则抽不到东西 → SourceException("正文规则未匹配到内容")。这一路冒到阅读页，
                    // 「翻页取消了上一次加载」就成了「章节读不出来」，进而误触发自动换源、清缓存 ——
                    // 正是本项目「catch 前先 rethrow 取消」要防的那件事。外层已在取消，就还原成取消。
                    if (e !is CancellationException) currentCoroutineContext().ensureActive()
                    throw e
                }
            }
        }
    suspend fun search(source: BookSourceRule, keyword: String, page: Int = 1): SearchPage =
        engineIo {
            val searchUrl = source.searchUrl?.takeIf { it.isNotBlank() }
                ?: throw SourceException("来源「${source.name}」未配置搜索规则")
            val rule = source.ruleSearch ?: throw SourceException("来源「${source.name}」未配置搜索规则")
            val vars = varsOf(source, "key" to keyword, "keyword" to keyword, "page" to page.toString())
            // 地址脚本和列表规则用同一张变量表（同 Legado 的 ruleData）：搜索地址里 java.put 的东西，
            // 解析结果时 java.get 要拿得到。从前两边各开一张，put 进去的转头就没了
            val js = jsContextOf(source)
            val fetched = fetchByRequest(source, searchUrl, vars, "search", js)
            parseListPage(
                source, "search", fetched, vars, rule.bookList.orEmpty(), listFieldMap(rule), nextPageRule = null,
                pageable = pageable(searchUrl), js = js,
            )
        }

    /**
     * 按书源的 searchUrl 抓搜索结果页、不解析。「新建书源」要在结果页上认列表：认的必须是**引擎**
     * 发出去的那个请求拿回来的页面（方法、body、编码、请求头都照 searchUrl 的选项走），
     * 拿 WebView 里看到的页面去认，规则可能套不上引擎抓到的静态页。只要求有 searchUrl，不要求有 ruleSearch。
     */
    suspend fun fetchSearchPage(source: BookSourceRule, keyword: String, page: Int = 1): FetchedPage =
        engineIo {
            val searchUrl = source.searchUrl?.takeIf { it.isNotBlank() }
                ?: throw SourceException("来源「${source.name}」未配置搜索地址")
            val vars = varsOf(source, "key" to keyword, "keyword" to keyword, "page" to page.toString())
            fetchByRequest(source, searchUrl, vars, "search")
        }

    /**
     * 地址模板里有 page 变量才谈得上翻页 —— 没有它，请求「第 2 页」拿回来的还是第 1 页。
     * Legado 的搜索/发现没有独立的 nextPage 规则，全靠 searchUrl/exploreUrl 里的 {{page}} 重发请求。
     */
    private fun pageable(vararg templates: String?): Boolean =
        templates.any { it != null && PAGE_VAR.containsMatchIn(it) }

    suspend fun explore(source: BookSourceRule, exploreIndex: Int, page: Int = 1): SearchPage =
        engineIo {
            val item = source.explore.getOrNull(exploreIndex)
                ?: throw SourceException("来源「${source.name}」发现页下标越界: $exploreIndex")
            // Legado：发现页列表规则缺省时复用搜索规则。「缺省」看的是 bookList 空不空，不是
            // ruleExplore 这个对象在不在 —— Legado 编辑器每次保存都写一个 `"ruleExplore":{}`，
            // 只按对象判的话，这类书源的发现页永远是空列表。
            val rule = source.ruleExplore?.takeUnless { it.bookList.isNullOrBlank() }
                ?: source.ruleSearch
                ?: source.ruleExplore
                ?: throw SourceException("来源「${source.name}」未配置发现规则")
            val vars = varsOf(source, "key" to "", "keyword" to "", "page" to page.toString())
            val js = jsContextOf(source)
            val fetched = fetchByRequest(source, item.url, vars, "explore", js)
            parseListPage(
                source, "explore", fetched, vars, rule.bookList.orEmpty(), listFieldMap(rule), nextPageRule = null,
                pageable = pageable(item.url), js = js,
            )
        }

    /** 搜索/发现列表项的字段规则 → 引擎内部字段名 */
    private fun listFieldMap(r: SearchRuleSet): Map<String, String> = buildMap {
        r.name?.let { put("title", it) }
        r.bookUrl?.let { put("bookUrl", it) }
        r.author?.let { put("author", it) }
        r.coverUrl?.let { put("coverUrl", it) }
        r.intro?.let { put("intro", it) }
        r.lastChapter?.let { put("latestChapter", it) }
        r.wordCount?.let { put("wordCount", it) }
        r.kind?.let { put("kind", it) }
    }

    /**
     * @param variables 搜索结果带来的变量（[SearchResult.variables]）。详情规则的 `java.get` 先能读到它们，
     *   返回的 [RemoteBookDetail.variables] 里也含着，随书存下（Legado：book.variable 取自 searchBook.variable）
     */
    suspend fun getDetail(
        source: BookSourceRule,
        bookUrl: String,
        variables: Map<String, String> = emptyMap(),
    ): RemoteBookDetail =
        engineIo {
            val url = resolveUrl(source.baseUrl, bookUrl)
            // 详情页解析不出来也不判死：书名/作者在搜索结果里早就拿到了，详情页只是「有就覆盖」。
            // 真正决定这本书能不能读的是目录 —— 让下一步去报错，那才是有信息量的错误。
            // Legado 里 ruleBookInfo 可缺省（详情页即目录页），缺省时直接用详情页地址当目录地址。
            withRenderFallback(source, "detail") { render -> parseDetail(source, url, render, variables) }
                ?: RemoteBookDetail(
                    title = "", author = null, coverUrl = null, intro = null, tocUrl = url, variables = variables,
                )
        }

    private suspend fun parseDetail(
        source: BookSourceRule,
        url: String,
        render: Boolean,
        variables: Map<String, String>,
    ): RemoteBookDetail? {
        val info = source.ruleBookInfo
        if (info == null) {
            // 无详情规则：详情页即目录页，不抓取，直接把地址透传给目录阶段（搜索带来的变量照样往下交）
            return RemoteBookDetail(
                title = "", author = null, coverUrl = null, intro = null, tocUrl = url, variables = variables,
            )
        }
        val fetched = fetchPage(source, url, "detail", render)
        val js = jsContextOf(source, bookUrl = url)
        js.scriptVars.putAll(variables)
        val pageCtx = pageContext(fetched, varsOf(source), js)
        // Legado 先跑 init，再在它**选中的那块内容**上求后面的字段（setContent(getElement(init))）。
        // 常见两种写法：@put:{n,a,k} 存变量给后面 @get:{n}；`$.data` / `class.info` 切换求值内容。
        // 从前只为副作用跑一遍，后一种的 `"init":"$.data"` 配 `"name":"$.book_name"` 会全部取空，
        // 目录地址回落成 bookUrl，最后报「目录规则未匹配到章节」—— 书打不开。
        val ctx = info.init?.takeIf { it.isNotBlank() }
            ?.let { initContentOf(it, pageCtx) }
            ?: pageCtx
        val title = evalField("detail", "title", info.name, ctx)
        val author = evalField("detail", "author", info.author, ctx)
        js.book.name = title.orEmpty()
        js.book.author = author.orEmpty()
        val cover = evalUrlField("detail", "coverUrl", info.coverUrl, ctx)
        val intro = evalField("detail", "intro", info.intro, ctx)
        js.book.coverUrl = cover.orEmpty()
        js.book.intro = intro.orEmpty()
        val tocRule = evalUrlField("detail", "tocUrl", info.tocUrl, ctx)?.takeIf { it.isNotBlank() }

        // 一个字段都没匹配上 → 这一页大概率是 JS 渲染的，返回 null 交给渲染兜底重试。
        //
        // 判据不能是「书名没匹配到」。书名在搜索结果里早就拿到了，详情页的书名规则
        // 只是「有就覆盖」—— Legado 从不因此判错。我们从前拿它当「页面没解析出来」的信号，
        // 于是站点改版、或书名规则只对 PC 版页面有效这类小事，都会让整个书源被判死。
        // 校验里那一大片「详情页未匹配到书名」就是这么来的，而它们在阅读 APP 里好好的。
        val nothingMatched = title.isNullOrBlank() && author.isNullOrBlank() &&
            cover.isNullOrBlank() && intro.isNullOrBlank() && tocRule == null
        val anyRuleConfigured = listOf(info.name, info.author, info.coverUrl, info.intro, info.tocUrl)
            .any { !it.isNullOrBlank() }
        if (nothingMatched && anyRuleConfigured) return null

        val tocUrl = tocRule?.let { resolveUrl(fetched.finalUrl, it) }
            ?: fetched.finalUrl // 缺省：详情页即目录页
        return RemoteBookDetail(
            title = title.orEmpty(), // 可能为空 → 调用方回落到搜索结果的书名
            author = author,
            coverUrl = cover?.let { resolveUrl(fetched.finalUrl, it) },
            intro = intro,
            tocUrl = tocUrl,
            // init 的 @put、各字段脚本里的 java.put 都写在这张表上（init 切换内容时 js 是同一个）
            variables = js.scriptVars.toMap(),
            canReName = !info.canReName.isNullOrBlank(),
        )
    }

    /**
     * @param variables 书级变量（[RemoteBookDetail.variables]）。目录规则的 `@get:{k}` / `java.get(k)`
     * 先查它；chapterList 那一级 java.put 的值也写进同一张表、跨目录分页保留。
     */
    suspend fun getToc(
        source: BookSourceRule,
        tocUrl: String,
        bookUrl: String = "",
        bookTitle: String = "",
        bookAuthor: String = "",
        variables: Map<String, String> = emptyMap(),
    ): List<RemoteChapter> =
        engineIo {
            val rule = source.ruleToc ?: throw SourceException("来源「${source.name}」未配置目录规则")
            withRenderFallback(source, "toc") { render ->
                collectToc(source, rule, tocUrl, render, bookUrl, bookTitle, bookAuthor, variables)
            } ?: throw SourceException("目录规则未匹配到章节: $tocUrl")
        }

    private suspend fun collectToc(
        source: BookSourceRule,
        rule: TocRuleSet,
        tocUrl: String,
        render: Boolean,
        bookUrl: String,
        bookTitle: String,
        bookAuthor: String,
        variables: Map<String, String>,
    ): List<RemoteChapter>? {
        // Legado：chapterList 前导 `-` 表示整体倒序
        val listRaw = rule.chapterList.orEmpty().trim()
        val reverse = listRaw.startsWith("-")
        val listRule = if (reverse) listRaw.substring(1) else listRaw
        val collected = mutableListOf<Triple<String, String, String>>()
        val seenChap = HashSet<String>()
        val pending = ArrayDeque<String>()
        resolveUrl(source.baseUrl, tocUrl).takeIf { it.isNotBlank() }?.let { pending.add(it) }
        val visited = HashSet<String>()
        var pages = 0
        // 目录级变量表：书级变量打底，chapterList / nextTocUrl 上的 java.put、@put 也写在这里。
        // 整个目录阶段一张、跨分页保留 —— Legado 把这一级的变量存在 Book 上，翻到第二页照样取得到。
        val tocVars = java.util.concurrent.ConcurrentHashMap(variables)
        // nextTocUrl 在 Legado 里可以一次吐出全部页码（.pages a@href / option@value / JS 数组）。
        // 从前只取第一个：第一个常是「当前页」，visited 一撞就停，目录只剩第一页。
        //
        // 按「波」抓：每一波把队列里还没访问过的页一次取出，**并发抓、按原顺序解析**。
        // 从前严格一页一页地等：手机站一页 20 章、四十页的目录要二十多秒，预览（15s）、加书架（20s）
        // 的超时稳定撞上，报「书源响应超时」再去试下一个源。只给一个「下一页」的链式分页每波只有一页，
        // 照旧串行；全部页码一次给出的，第二波就是剩下的全部页。解析仍是串行的：目录级变量表
        // （java.put / @put）要按页序写，结果也要按页序拼。限速仍由 http 层按书源的令牌桶管。
        // 要走 WebView 渲染的不并发：WebView 只能在主线程跑，同时开几个只会一起慢。
        while (pending.isNotEmpty() && pages < MAX_TOC_PAGES) {
            val wave = ArrayList<String>()
            while (pending.isNotEmpty() && pages + wave.size < MAX_TOC_PAGES) {
                val url = pending.removeFirst()
                if (visited.add(pageKey(url))) wave += url
            }
            if (wave.isEmpty()) break
            pages += wave.size
            coroutineScope {
                val lanes = Semaphore(if (render || wave.size == 1) 1 else TOC_CONCURRENCY)
                val fetches = wave.map { url ->
                    async { lanes.withPermit { fetchPage(source, url, "toc", render) } }
                }
                for (page in fetches) {
                    val fetched = page.await()
                    collectTocPage(
                        source, rule, fetched, listRule, tocUrl, bookUrl, bookTitle, bookAuthor,
                        tocVars, variables, collected, seenChap,
                    ) { resolved -> if (pageKey(resolved) !in visited) pending.add(resolved) }
                }
            }
        }
        if (collected.isEmpty()) return null
        val ordered = if (reverse) collected.asReversed() else collected
        return ordered.mapIndexed { i, (title, u, vars) -> RemoteChapter(i, title, u, vars) }
    }

    /** 解析目录的一页：章节追加进 [collected]，下一页地址交给 [onNext] */
    private fun collectTocPage(
        source: BookSourceRule,
        rule: TocRuleSet,
        fetched: FetchedPage,
        listRule: String,
        tocUrl: String,
        bookUrl: String,
        bookTitle: String,
        bookAuthor: String,
        tocVars: java.util.concurrent.ConcurrentHashMap<String, String>,
        variables: Map<String, String>,
        collected: MutableList<Triple<String, String, String>>,
        seenChap: MutableSet<String>,
        onNext: (String) -> Unit,
    ) {
        val ctx = pageContext(
            fetched, varsOf(source),
            jsContextOf(source, bookUrl = bookUrl, tocUrl = tocUrl, bookName = bookTitle, bookAuthor = bookAuthor)
                .copy(scriptVars = tocVars),
        )
        for (item in evalList("toc", listRule, ctx)) {
            // 每个章节项一份变量表：@put 存的参数属于这一章，不能串到别的章节上。
            // 但要拿目录级变量表打底，而不是一张白纸：chapterList 里 `<js>java.put('bid',…)</js>`
            // 存下的书号，chapterUrl 的 `@get:{bid}` 要取得到，也要随章节落库给正文用。从前给的是空表，两头都断。
            val itemCtx = item.copy(js = item.js.copy(scriptVars = java.util.concurrent.ConcurrentHashMap(tocVars)))
            val title = evalField("toc", "title", rule.chapterName, itemCtx)
            val chapUrl = evalUrlField("toc", "url", rule.chapterUrl, itemCtx)
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
            if (!title.isNullOrBlank() && !chapUrl.isNullOrBlank() && seenChap.add(chapUrl)) {
                // 原样传进来、没被改过的书级变量不进章节：调用方随书存着一份，每章再存一遍只是把库撑大
                val own = itemCtx.js.scriptVars.filter { (k, v) -> variables[k] != v }
                collected += Triple(title, chapUrl, encodeVars(own))
            }
        }
        val nexts = rule.nextTocUrl
            ?.let { evalUrlFields("toc", "nextPage", it, ctx) }
            .orEmpty()
        for (n in nexts) {
            val resolved = resolveUrl(fetched.finalUrl, n)
            if (isFetchable(resolved)) onNext(resolved)
        }
    }

    /**
     * @param otherChapterUrls 本书其余章节的地址。正文分页会跟着 nextPage 一直翻，而不少站点把
     * 分页按钮也标成「下一章」（如仓库看书网 874007_2.html → 874007_3.html → 874008.html），
     * 一路跟下去会把后面十几章吞进当前章。撞上目录里的其他章节即停。
     */
    /**
     * @param chapterVariable 目录阶段存下的变量（[RemoteChapter.variable]）。正文规则里的
     * `@get:{k}` / `java.get(k)` 要取得到它 —— 不传这条链路就是断的。
     * @param variables 书级变量（[RemoteBookDetail.variables]）；与本章变量同名时本章的优先
     * （Legado 的 get 也是先查章节、再查书）。
     */
    suspend fun getContent(
        source: BookSourceRule,
        chapterUrl: String,
        otherChapterUrls: Set<String> = emptySet(),
        chapterVariable: String = "",
        allowJsRender: Boolean = true,
        chapterTitle: String = "",
        chapterIndex: Int = 0,
        bookUrl: String = "",
        bookTitle: String = "",
        variables: Map<String, String> = emptyMap(),
    ): RemoteChapterContent = engineIo {
        val rule = source.ruleContent ?: throw SourceException("来源「${source.name}」未配置正文规则")
        val result = if (allowJsRender) {
            withRenderFallback(source, "content") { render ->
                collectContent(
                    source, rule, chapterUrl, otherChapterUrls, chapterVariable, render,
                    chapterTitle, chapterIndex, bookUrl, bookTitle, variables,
                )
            }
        } else {
            collectContent(
                source, rule, chapterUrl, otherChapterUrls, chapterVariable, render = false,
                chapterTitle, chapterIndex, bookUrl, bookTitle, variables,
            )
        }
        result ?: throw SourceException("正文规则未匹配到内容: $chapterUrl")
    }

    /**
     * 正文里一张插图的字节（[ContentElement.Image.resourceId] 就是这里的 [imageUrl]）。
     *
     * 带上来源的请求头（UA、Referer、Cookie 这些防盗链要看的）；地址自带 `,{"headers":…}` 的（阅读的写法）照它的来。
     * 来源没写 Referer、图床又拒了的，带上来源站点再取一次 —— 浏览器里看得见的图，是因为浏览器带着网页的来源。
     * 不一上来就带：有的图床反过来，带了别的站就回一张「禁止外链」的占位图（正常返回，没有机会重试），
     * 订阅文章的图也是这么取的（见 app 的 ImageReferer）。
     */
    suspend fun getImage(source: BookSourceRule, imageUrl: String): ByteArray = engineIo {
        val split = splitUrlOptions(imageUrl)
        val url = resolveUrl(source.baseUrl, split?.first ?: imageUrl)
        val headers = mergeHeaders(sourceHeaders(source), split?.second?.headers.orEmpty(), varsOf(source))
        try {
            http.fetchBytes(url, headers)
        } catch (e: SourceHttpClient.HttpStatusException) {
            val referer = siteOrigin(source.baseUrl)
            if (referer == null || headers.keys.any { it.equals("Referer", ignoreCase = true) }) throw e
            http.fetchBytes(url, headers + ("Referer" to referer))
        }
    }

    /** 变量表 ↔ JSON（随章节一起落库，见 ChapterEntity.variable） */
    private fun encodeVars(vars: Map<String, String>): String = SourceVariables.encode(vars)

    private fun decodeVars(json: String): MutableMap<String, String> =
        java.util.concurrent.ConcurrentHashMap(SourceVariables.decode(json))

    private suspend fun collectContent(
        source: BookSourceRule,
        rule: ContentRuleSet,
        chapterUrl: String,
        otherChapterUrls: Set<String>,
        chapterVariable: String,
        render: Boolean,
        chapterTitle: String,
        chapterIndex: Int,
        bookUrl: String,
        bookTitle: String,
        variables: Map<String, String>,
    ): RemoteChapterContent? {
        // 净化：源级(replaceRegex) 在前、用户的通用规则在后；正则预编译。
        // 源级规则按 Legado 的容错来：一条写坏的正则退回字面替换，而不是让每一章都打不开（见 [Purifier.legado]）
        val sourcePurifier = Purifier.legado(parseReplaceRegex(rule.replaceRegex))
        val userPurifier = Purifier.lenient(globalPurify(source, bookTitle))
        val elements = mutableListOf<ContentElement>()
        val firstUrl = resolveUrl(source.baseUrl, chapterUrl)
        // 用到才建：绝大多数章节只有一页，根本走不到「下一页是不是别的章」那一步。从前每章（含每次预取）
        // 都把整本几千章的地址过一遍 pageKey，pageKey(firstUrl) 还在循环里重算
        val stopAt by lazy(LazyThreadSafetyMode.NONE) {
            val self = pageKey(firstUrl)
            otherChapterUrls.mapNotNullTo(HashSet()) { u -> pageKey(u).takeIf { it != self } }
        }
        var url: String? = firstUrl
        val visited = HashSet<String>()
        var pages = 0
        // 目录阶段存下的变量在这里喂回脚本：正文规则的 @get:{k} / java.get(k) 靠它。
        // 书级变量打底、本章变量覆盖；整章一张表，第一页 java.put 的值翻到下一页还在
        val contentVars = java.util.concurrent.ConcurrentHashMap(variables).apply { putAll(decodeVars(chapterVariable)) }
        while (url != null && pages < MAX_CONTENT_PAGES && visited.add(pageKey(url))) {
            pages++
            val fetched = fetchPage(source, url, "content", render)
            val js = jsContextOf(
                source,
                bookUrl = bookUrl,
                bookName = bookTitle,
                chapterUrl = url,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
            ).copy(scriptVars = contentVars)
            val ctx = pageContext(fetched, varsOf(source), js)
            val html = evalField("content", "content", rule.content, ctx)
            if (html != null) {
                val body = Jsoup.parseBodyFragment(asContentHtml(html), fetched.finalUrl).body()
                // 插图照收（相对地址按这一页的地址补全）；阅读页显示不显示是阅读设置的事，见 HtmlToElements
                elements += HtmlToElements(keepImages = true, keepBlocks = false).convert(body)
            }
            url = rule.nextContentUrl
                ?.let { evalUrlField("content", "nextPage", it, ctx) }
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
                // 末页的「下一页」常是 javascript:void(0)：当作没有下一页。从前拿去抓会抛「非法 URL」，
                // 前面已经攒好的几页一起作废，整章判失败、还触发自动换源
                ?.takeIf { isFetchable(it) }
                ?.takeIf { pageKey(it) !in stopAt } // 翻到别的章节了 → 本章结束
        }
        // 净化超时（某条规则陷进灾难性回溯）就按未净化返回：净化只是清广告，
        // 不能让它把"这章有广告"升级成"这章打不开"。
        val purified = try {
            userPurifier.apply(sourcePurifier.apply(elements))
        } catch (_: PurifyTimeoutException) {
            elements
        }
        return purified.takeIf { it.isNotEmpty() }?.let { RemoteChapterContent(it) }
    }

    // ---- JS 渲染回退 ----

    /**
     * 先按已知模式抓一次；解析为空且注入了渲染器时，换另一种模式再试一次。
     * 某一级确认需要渲染就记住（按「书源 + 阶段」），后续同一级先走渲染器，不再每次先静态空跑。
     *
     * 记住的只是「先试哪种」，不是「以后只许渲染」。从前一旦记住就只走渲染器、失败即判空：
     * 站点改版不再需要渲染、或渲染器这次超时，这一级就再也读不出来；而且某一级要渲染，
     * 详情/目录/正文三级就全被拖进 WebView。现在先试的那种对不上规则，就回头用另一种再抓一遍，
     * 并按实际成功的那种更新记录 —— 多一次抓取只发生在本来就要失败的路径上。
     *
     * 只用于 detail/toc/content —— 这三级都是用户点开某一本书触发的，慢一点可以接受。
     * 搜索/发现会向上百个书源并发扇出，其中大量是已死站点，逐个渲染会把整次搜索拖垮。
     */
    private suspend fun <T : Any> withRenderFallback(
        source: BookSourceRule,
        stage: String,
        attempt: suspend (render: Boolean) -> T?,
    ): T? {
        if (renderer == null) return attempt(false)
        val key = "${source.id}\n$stage"
        val known = key in needsRender
        attempt(known)?.let { return it }
        val other = attempt(!known) ?: return null
        if (known) needsRender -= key else needsRender += key
        return other
    }

    /**
     * render=true 时走 JS 渲染；渲染器缺席或渲染失败时静默退回静态抓取。
     *
     * 渲染器只会 GET 一个地址：`,{method/body}` 交给它，POST 与 body 全丢，拿回来的是另一张页面，
     * 规则自然对不上。所以带 POST/body 的请求一律静态抓（选项照常由 [fetchUrl] 解析），
     * 交给渲染器的地址也先剥掉 `,{…}` 后缀 —— 留着它，WebView 打开的就是一个必然 404 的废地址。
     */
    private suspend fun fetchPage(
        source: BookSourceRule,
        url: String,
        stage: String,
        render: Boolean,
    ): FetchedPage {
        val key = recentKey(source, url, render)
        if (stage == "toc" && key != null) takeRecent(key)?.let { return it }
        val page = fetchPageUncached(source, url, stage, render)
        if (stage == "detail" && key != null) {
            keepRecent(key, page)
            // 目录地址缺省取的是详情页的 finalUrl（跳转之后的地址），两个都记上
            recentKey(source, page.finalUrl, render)?.let { keepRecent(it, page) }
        }
        return page
    }

    /**
     * 刚抓过的详情页，短暂留一会儿给紧接着的目录阶段。
     *
     * 「详情页即目录页」（没写 tocUrl，目录地址就是详情页本身）的书源，预览、加书架、换源都是
     * 先 getDetail 再 getToc —— 同一个地址前后脚抓两遍：多一个完整往返、多排一次限速令牌，
     * 第二次还更容易吃 403，而预览 15 秒、加书架 20 秒的超时就那么点预算。
     * 只为接住紧挨着的下一步：**取一次就作废**（之后的追更、刷新目录照常现抓，不会拿到旧页），
     * 条数、存活时间也压得很小，写入时顺手清掉过期的，别让几页响应体一直攥在单例里。只缓存不带 body 的 GET。
     * 调试书源时（[trace]）不走缓存：每次抓取都得出现在调试输出里。
     */
    private val recentPages = object : LinkedHashMap<String, Pair<Long, FetchedPage>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, FetchedPage>>?): Boolean =
            size > RECENT_PAGES
    }

    private fun recentKey(source: BookSourceRule, url: String, render: Boolean): String? {
        if (trace != null) return null
        val opt = splitUrlOptions(url)?.second
        if (opt != null && (opt.body != null || !(opt.method ?: "GET").equals("GET", ignoreCase = true))) return null
        return source.id + "\u0000" + render + "\u0000" + pageKey(url)
    }

    private fun keepRecent(key: String, page: FetchedPage) = synchronized(recentPages) {
        val now = System.nanoTime()
        recentPages.values.removeAll { (at, _) -> now - at > RECENT_PAGE_TTL_NS }
        recentPages[key] = now to page
    }

    private fun takeRecent(key: String): FetchedPage? = synchronized(recentPages) {
        val (at, page) = recentPages.remove(key) ?: return null
        // 同一页可能记在两个键下（请求地址与跳转后的 finalUrl），一起作废
        recentPages.values.removeAll { (_, p) -> p === page }
        page.takeIf { System.nanoTime() - at <= RECENT_PAGE_TTL_NS }
    }

    private suspend fun fetchPageUncached(
        source: BookSourceRule,
        url: String,
        stage: String,
        render: Boolean,
    ): FetchedPage {
        val split = splitUrlOptions(url)
        val opt = split?.second
        // 地址自带 webView（书源声明这一页要渲染）的，不等「静态解析为空」再回退，直接渲染
        if ((render || opt?.webView == true) && renderer != null) {
            val staticOnly = opt != null && (opt.body != null || (opt.method ?: "GET") != "GET")
            if (!staticOnly) {
                val target = split?.first ?: url
                // 源级 header 模板（{{baseUrl}} 等）要用真实变量展开，否则防盗链站拿到空 Referer 会 403
                val headers = mergeHeaders(sourceHeaders(source), opt?.headers.orEmpty(), varsOf(source))
                render(target, headers)?.let { return it }
            }
        }
        return fetchUrl(source, url, stage)
    }

    // ---- 内部 ----

    private fun varsOf(source: BookSourceRule, vararg extra: Pair<String, String>): Map<String, String> =
        mapOf("baseUrl" to source.baseUrl, *extra)

    private suspend fun fetchByRequest(
        source: BookSourceRule,
        urlTemplate: String,
        vars: Map<String, String>,
        stage: String,
        js: JsContext = jsContextOf(source),
    ): FetchedPage {
        // 地址里的 {{}} 同样按 Legado 语义展开（JS 表达式 / 嵌套规则），不只是变量替换
        val urlCtx = urlContext(source, vars, js)
        val keyword = vars["keyword"].orEmpty()

        // 地址整串可能是 JS（<js>…</js> / @js:…），脚本的返回值才是真地址；
        // 而且地址常自带 ,{method/body/charset/headers} —— 拿到最终地址后才谈得上解析。
        val rawUrl = evaluator.evalUrlJs(urlTemplate, urlCtx) ?: urlTemplate

        // 与 Legado 同序：先展开模板、再解析选项。选项块在展开前常常不是合法 JSON ——
        // `,{"method":"POST","body":{"page":{{page}}}}` 里的 {{page}} 没有引号。从前先拿 JSON 校验去找选项，
        // 找不到就整串当 GET 发出去，`,{…}` 原样留在地址里。所以先按花括号配平在结构上切开（[optionsIndex]），
        // 地址与选项块各自展开：关键词在两边要的转义不一样。
        val optAt = optionsIndex(rawUrl)
        val urlPart = if (optAt >= 0) rawUrl.substring(0, optAt) else rawUrl
        // 选项块里的关键词先换成占位符再展开、解析：原样代入的话，关键词里一个 ' 或 " 就能把 JSON 弄坏。
        // 解析不出来（展开出的值把 JSON 弄坏了）就当没有选项 —— 无论如何不能让 `,{…}` 进地址
        val opt = if (optAt >= 0) {
            parseUrlOptions(evaluator.expandTemplate(markKeyword(rawUrl.substring(optAt + 1)), urlCtx))
                ?: UrlOptions()
        } else {
            UrlOptions()
        }
        val method = opt.method ?: "GET"
        val charset = opt.charset ?: source.charset
        val body = opt.body?.let { fillKeywordInBody(it, keyword, method, charset) }
        val headers = opt.headers.mapValues { (_, v) -> v.replace(KEYWORD_MARK, keyword) }

        // 地址里的关键词按站点编码百分号转义，且转成路径、查询串里都安全的形式（见 [encodeUrlComponent]）。
        // 从前原样代入：`C#` 把后面的 `&page=2` 截成片段，`C++` 到服务端成了空格，`A&B` 被拆成两个字段，
        // `100%` 是个坏转义；非 UTF-8 站还只认字面的 {{key}}，{{keyword}} 照样按 UTF-8 发。
        val expanded = evaluator.expandTemplate(markKeyword(urlPart), urlCtx)
            .replace(KEYWORD_MARK, encodeUrlComponent(keyword, charset))
        val url = resolveUrl(source.baseUrl, encodeNonAsciiQuery(expanded, charset))
        val mergedHeaders = mergeHeaders(sourceHeaders(source, js), headers, vars)
        // 书源自己声明了要 WebView（Legado 的 webView）：搜索、发现也照办。不声明的源照旧只静态抓 ——
        // 自动回退（[withRenderFallback]）不碰搜索，是怕上百个源一起排队等 WebView；声明了的只有寥寥几个，
        // 而它们的结果本来就是页面脚本拉回来的，静态抓永远是空壳（七猫的网页版）。
        // 渲染器只会 GET：带 body 的照旧静态抓。渲染失败（超时、加载出错）也退回静态抓
        if (opt.webView && body == null && method.equals("GET", ignoreCase = true)) {
            render(url, mergedHeaders)?.let { return it }
        }
        return doFetch(
            stage = stage,
            url = url,
            method = method,
            body = body,
            headers = mergedHeaders,
            charset = charset,
            rateLimit = source.rateLimit,
        )
    }

    /** 交给渲染器；没注入渲染器或渲染失败返回 null，调用方退回静态抓取 */
    private suspend fun render(target: String, headers: Map<String, String>): FetchedPage? {
        val r = renderer ?: return null
        // UA 必须与静态抓取一致：站点常按 UA 给不同 DOM，规则是照静态那份写的
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            ?: SourceHttpClient.DEFAULT_UA
        return r.render(target, headers.withoutLegadoOnlyHeaders(), ua)?.also { page ->
            trace?.onFetch(
                FetchTrace(target, "RENDER", page.statusCode, page.finalUrl, page.detectedCharset, page.bodyText.take(500))
            )
        }
    }

    /**
     * detail/toc/content 阶段的抓取。从前恒 GET、且把 header 模板用 emptyMap() 展开：
     * - 规则/JS 拼出的地址常自带 `,{method/body/charset/headers}` 选项（尤其翻页、POST 目录接口），
     *   要用 [splitUrlOptions] 解析并透传，否则 method/body/charset 全丢、请求方式错到底。
     * - 源级 header 里的 `{{baseUrl}}` 在这三级要展开成真实值（fix：传 varsOf(source)），否则 403。
     */
    private suspend fun fetchUrl(source: BookSourceRule, url: String, stage: String): FetchedPage {
        var target = url
        var method = "GET"
        var body: String? = null
        var charset: String? = source.charset
        var headers: Map<String, String> = emptyMap()
        splitUrlOptions(url)?.let { (bare, opt) ->
            target = bare
            opt.method?.let { method = it }
            opt.body?.let { body = it }
            opt.charset?.let { charset = it }
            if (opt.headers.isNotEmpty()) headers = opt.headers
        }
        val vars = varsOf(source)
        return doFetch(
            stage = stage,
            url = target,
            method = method,
            body = body?.let { raw ->
                val templated =
                    if (method.equals("POST", ignoreCase = true)) encodeKeyInFormTemplate(raw, charset) else raw
                expandTemplate(templated, vars)
            },
            headers = mergeHeaders(sourceHeaders(source), headers, vars),
            charset = charset,
            rateLimit = source.rateLimit,
        )
    }

    private suspend fun doFetch(
        stage: String,
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        charset: String?,
        rateLimit: RateLimitRule?,
    ): FetchedPage {
        val page = http.fetch(url, method, body, headers, charset, rateLimit)
        trace?.onFetch(
            FetchTrace(
                requestUrl = url,
                method = method.uppercase(),
                statusCode = page.statusCode,
                finalUrl = page.finalUrl,
                detectedCharset = page.detectedCharset,
                bodyPreview = page.bodyText.take(500),
            )
        )
        return page
    }

    private fun mergeHeaders(
        sourceHeaders: Map<String, String>,
        requestHeaders: Map<String, String>,
        vars: Map<String, String>,
    ): Map<String, String> =
        (sourceHeaders + requestHeaders).mapValues { (_, v) -> expandTemplate(v, vars) }

    /**
     * 源级请求头。JSON 写法解析一次就记住（[BookSourceRule.headers]）；脚本写法（[BookSourceRule.headerScript]）
     * 同阅读，**每次请求现跑**：脚本常拿当前时间算签名（值得阅读、书香之家），记住一份就是一份过期的签名。
     * 脚本里有 java / source / cookie / cache / baseUrl 和源的 jsLib。
     *
     * 跑不出 JSON（报错、返回的不是对象）就当没有源级请求头 —— 和从前一样，别让一段坏 header 把这个源的请求全拦下。
     * 变量表拷一份给它：header 脚本里 java.put 的东西不该混进这条链路要交回去的变量（[SearchResult.variables]）。
     *
     * 脚本 `source.putLoginHeader(…)` 存下的头盖在最上面（同阅读的 getHeaderMap）：一读这类站先回一段脚本挑战，
     * 规则算出 Cookie 存进去，后面的请求就得带着它
     */
    private fun sourceHeaders(source: BookSourceRule, js: JsContext = jsContextOf(source)): Map<String, String> =
        baseSourceHeaders(source, js) + evaluator.loginHeaders(source.id)

    private fun baseSourceHeaders(source: BookSourceRule, js: JsContext): Map<String, String> {
        val script = source.headerScript ?: return source.headers
        val ctx = urlContext(
            source,
            varsOf(source),
            js.copy(scriptVars = java.util.concurrent.ConcurrentHashMap(js.scriptVars)),
        )
        val out = evaluator.evalSourceScript(script, ctx)
        val headers = parseHeaderJson(out)
        trace?.onRule(
            RuleTrace("header", "header", script, out, error = if (headers.isEmpty()) "header 脚本没有返回请求头" else null),
        )
        return headers
    }

    /** 页面上下文：文档（`<?xml` 开头的按 XML 解析，见 [parsePage]）与原始文本（JSON 规则懒解析）同时可用 */
    private fun pageContext(
        fetched: FetchedPage,
        vars: Map<String, String>,
        js: JsContext = JsContext(),
    ): EvalContext =
        EvalContext(
            element = parsePage(fetched.bodyText, fetched.finalUrl),
            json = fetched.bodyText,
            baseUrl = fetched.finalUrl,
            vars = vars,
            js = js,
        )

    /** 脚本上下文：书源标识 + 当前书/章节。scriptVars 每条抓取链路一份，供 java.put/get 传值 */
    /** 展开地址模板用的上下文：没有页面，只有变量与脚本桥 */
    private fun urlContext(source: BookSourceRule, vars: Map<String, String>, js: JsContext = jsContextOf(source)) =
        EvalContext(
            element = null,
            json = null,
            baseUrl = source.baseUrl,
            vars = vars,
            js = js,
        )

    private fun jsContextOf(
        source: BookSourceRule,
        bookUrl: String = "",
        tocUrl: String = "",
        chapterUrl: String = "",
        bookName: String = "",
        bookAuthor: String = "",
        chapterTitle: String = "",
        chapterIndex: Int = 0,
    ) = JsContext(
        sourceKey = source.id,
        sourceFacts = source.scriptFacts,
        jsLib = source.jsLib,
        book = com.radium.inkwell.core.source.js.BookBridge(
            name = bookName,
            author = bookAuthor,
            bookUrl = bookUrl,
            tocUrl = tocUrl,
            origin = source.id,
            originName = source.bookSourceName,
        ),
        chapter = com.radium.inkwell.core.source.js.ChapterBridge(
            title = chapterTitle,
            url = chapterUrl,
            index = chapterIndex,
        ),
    )

    private fun parseListPage(
        source: BookSourceRule,
        stage: String,
        fetched: FetchedPage,
        vars: Map<String, String>,
        listRule: String,
        fields: Map<String, String>,
        nextPageRule: String?,
        pageable: Boolean = false,
        js: JsContext = jsContextOf(source),
    ): SearchPage {
        val ctx = pageContext(fetched, vars, js)
        val items = evalList(stage, listRule, ctx).mapNotNull { node ->
            // 每条结果拷一份页面的变量表（同 Legado：每个 searchBook 拷一份 ruleData 的变量）：
            // 一条结果的规则里 put 的，不串到下一条
            val itemCtx = node.copy(
                js = node.js.copy(scriptVars = java.util.concurrent.ConcurrentHashMap(js.scriptVars)),
            )
            val title = evalField(stage, "title", fields["title"], itemCtx)
            val bookUrl = evalUrlField(stage, "bookUrl", fields["bookUrl"], itemCtx)
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
            if (title.isNullOrBlank() || bookUrl.isNullOrBlank()) return@mapNotNull null
            SearchResult(
                title = title,
                bookUrl = bookUrl,
                author = evalField(stage, "author", fields["author"], itemCtx),
                coverUrl = evalUrlField(stage, "coverUrl", fields["coverUrl"], itemCtx)
                    ?.let { resolveUrl(fetched.finalUrl, it) },
                intro = evalField(stage, "intro", fields["intro"], itemCtx),
                latestChapter = evalField(stage, "latestChapter", fields["latestChapter"], itemCtx),
                wordCount = evalField(stage, "wordCount", fields["wordCount"], itemCtx),
                kind = evalField(stage, "kind", fields["kind"], itemCtx),
                sourceId = source.id,
                sourceName = source.name,
                variables = itemCtx.js.scriptVars.toMap(),
            )
        }
        // nextPage 规则命中即可继续；书源没有该规则时，只要地址模板带 page 且这一页有结果，
        // 就还能往下翻（Legado 的搜索/发现正是这样分页的）
        val hasNextRule = nextPageRule != null &&
            !evalUrlField(stage, "nextPage", nextPageRule, ctx).isNullOrBlank()
        return SearchPage(items, hasNextRule || (pageable && items.isNotEmpty()))
    }

    private fun evalList(
        stage: String,
        rule: String,
        ctx: EvalContext,
        name: String = "list",
    ): List<EvalContext> =
        try {
            val nodes = evaluator.evalToNodes(LegadoRuleAnalyzer.analyze(rule), ctx)
            trace?.onRule(RuleTrace(stage, name, rule, "${nodes.size} 个节点"))
            nodes
        } catch (e: Exception) {
            trace?.onRule(RuleTrace(stage, name, rule, null, e.message))
            throw e
        }

    /**
     * 详情 init 选出的内容；纯 `@put`（没有选出东西）或选出的只是个标量时返回 null，保持整页。
     * 标量不切：脚本 init 常顺手返回个值（如 `java.put` 的返回值），在一个字符串上什么字段都取不出来。
     */
    private fun initContentOf(rule: String, ctx: EvalContext): EvalContext? =
        evalList("detail", rule, ctx, name = "init").firstOrNull()?.takeIf { node ->
            val json = node.json
            node.element != null || when (json) {
                null -> false
                is String -> json.trimStart().let { it.startsWith("{") || it.startsWith("[") }
                else -> true
            }
        }

    /**
     * URL 字段求值：只取首个非空匹配。
     * 文本字段多匹配时换行拼接是合理的（如多段简介），但地址字段绝不能拼 ——
     * 页面上下两处导航常各有一个「下一章」，拼起来会得到一个必然 404 的废地址。
     *
     * 目录翻页 [evalUrlFields] 例外：Legado 的 nextTocUrl 就是「全部页码」，必须全收。
     */
    private fun evalUrlField(stage: String, name: String, rule: String?, ctx: EvalContext): String? =
        evalUrlFields(stage, name, rule, ctx).firstOrNull()

    /** nextTocUrl：全部非空地址。JS 返回 JSON 数组时拆开，避免整段当一个废 URL。 */
    private fun evalUrlFields(stage: String, name: String, rule: String?, ctx: EvalContext): List<String> {
        if (rule.isNullOrBlank()) return emptyList()
        return try {
            val values = flattenUrlResults(evaluator.evalToStrings(LegadoRuleAnalyzer.analyze(rule), ctx))
            trace?.onRule(RuleTrace(stage, name, rule, values.joinToString(" | ").take(200)))
            values
        } catch (e: Exception) {
            trace?.onRule(RuleTrace(stage, name, rule, null, e.message))
            throw e
        }
    }

    /** 单字段求值；规则缺失返回 null，求值异常上报 trace 后原样抛出 */
    private fun evalField(stage: String, name: String, rule: String?, ctx: EvalContext): String? {
        if (rule.isNullOrBlank()) return null
        return try {
            val value = evaluator.evalToString(LegadoRuleAnalyzer.analyze(rule), ctx)
            trace?.onRule(RuleTrace(stage, name, rule, value?.take(200)))
            value
        } catch (e: Exception) {
            trace?.onRule(RuleTrace(stage, name, rule, null, e.message))
            throw e
        }
    }

    private companion object {
        val VARS_JSON = kotlinx.serialization.json.Json
        val PAGE_VAR = Regex("\\{\\{[^}]*page[^}]*\\}\\}")
        const val MAX_TOC_PAGES = 200
        /** 目录分页的并发抓取数。再多就是对着同一个站点群发，限流、封 IP 的风险比省下的时间大 */
        const val TOC_CONCURRENCY = 4
        /** 见 [recentPages]：只接「详情 → 目录」紧挨着的那一步 */
        const val RECENT_PAGES = 8
        const val RECENT_PAGE_TTL_NS = 30_000_000_000L
        /** 单章分页抓取上限。50 会让失控的 nextPage 空转近一分钟；正经分页站很少超过十几页 */
        const val MAX_CONTENT_PAGES = 16

        /**
         * JS 的 `list.push` 经 Rhino 会 stringify 成 `["/p2","/p3"]`，evalToStrings 只拿到这一段。
         * 换行分隔的多地址同样拆开 —— 但「一个地址 + 跨行的 `,{…}` 选项块」是**一个**地址：
         * 凤凰网书城的目录地址是脚本拼的 `…/directory,{` 换行 `"body": …,` 换行 `"method": "POST"` 换行 `}`，
         * 从前按行拆成四段，取第一段 `…/directory,{` 去抓，必然 404。
         */
        fun flattenUrlResults(values: List<String>): List<String> {
            val out = ArrayList<String>()
            for (v in values) {
                val t = v.trim()
                if (t.isEmpty()) continue
                if (t.startsWith("[") && t.endsWith("]")) {
                    val arr = runCatching { VARS_JSON.parseToJsonElement(t) }.getOrNull()
                        as? kotlinx.serialization.json.JsonArray
                    if (arr != null) {
                        var any = false
                        for (el in arr) {
                            val s = (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                            if (s.isNotEmpty()) {
                                out += s
                                any = true
                            }
                        }
                        if (any) continue
                    }
                }
                val optAt = if ('\n' in t) optionsIndex(t) else -1
                if ('\n' in t && !(optAt > 0 && '\n' !in t.substring(0, optAt))) {
                    t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
                } else {
                    out += t
                }
            }
            return out
        }
    }
}

/**
 * Legado replaceRegex：多行 `##正则##替换`（或 `正则##替换`），逐行解析为净化规则。
 * 含 JS 的整块忽略（我们的净化不跑脚本）。非法正则由 [Purifier.legado] 退回字面替换。
 */
internal fun parseReplaceRegex(raw: String?): List<PurifyRule> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.contains("<js>", ignoreCase = true) || text.contains("@js:", ignoreCase = true)) {
        return emptyList()
    }
    return text.lines().mapNotNull { line ->
        val t = line.trim().removePrefix("##")
        if (t.isEmpty()) return@mapNotNull null
        val sep = t.indexOf("##")
        val pattern = if (sep < 0) t else t.substring(0, sep)
        val replacement = if (sep < 0) "" else t.substring(sep + 2).removeSuffix("###")
        if (pattern.isBlank()) null else PurifyRule(pattern = pattern, replacement = replacement, isRegex = true)
    }
}

/**
 * 正文规则的结果不一定是 HTML：多元素 `@text`（每个元素一段，求值器用 `\n` 拼起来）、`@textNodes`、
 * JSON 接口的正文、JS 解密出的纯文本，段落结构全靠换行承载。而 [HtmlToElements] 只把
 * 「换行 + 全角缩进」当段界 —— 对 HTML 这是对的（源码里为可读性折的行不是段落），但对上面这些
 * 结果，段首没有缩进就会整章挤成一段。Legado 按换行分段，同一个书源在它那里是正常的。
 *
 * 所以：结果里没有任何块级标签或 `<br>` 时，它的换行就是段落边界，换成 `<br>` 再交给转换器。
 * 只有行内标签（`<span>`/`<font>` 之类的广告包裹）不算 HTML 结构，照样按换行分段。
 */
internal fun asContentHtml(raw: String): String =
    if (CONTENT_BLOCK_TAG.containsMatchIn(raw)) raw else raw.replace(CONTENT_LINE_BREAK, "<br>")

private val CONTENT_BLOCK_TAG = Regex(
    "<(?:br|p|div|h[1-6]|li|hr|blockquote|section|article|dd|dt|td|tr|table|pre)\\b",
    RegexOption.IGNORE_CASE,
)
private val CONTENT_LINE_BREAK = Regex("\\r\\n?|\\n")

/**
 * 把 POST 表单模板里尚未带管道的 `{{key}}` / `{{keyword}}` 改成 `{{key|encode}}`。
 * 必须在展开之前做：展开后再按 `&` 切，关键词里的 `&` 已经变成字段分隔符了。
 */
internal fun encodeKeyInFormTemplate(body: String, charsetName: String?): String {
    val t = body.trim()
    if (t.startsWith("{") || t.startsWith("[")) return body
    val cs = charsetName?.lowercase()
    val pipe = if (cs.isNullOrBlank() || cs == "utf-8" || cs == "utf8") "encode" else "encode:$cs"
    return body
        .replace("{{key}}", "{{key|$pipe}}")
        .replace("{{keyword}}", "{{keyword|$pipe}}")
}

/**
 * 相对 → 绝对 URL 解析；base 非法或无法解析时原样返回。
 *
 * 地址尾部的 `,{…}` 选项后缀（method/body/charset/headers）**原样保留**：这里只解析地址主体，
 * 解析完把后缀接回去，留到 detail/toc/content 抓取时才由 [splitUrlOptions] 解析。从前在这里直接
 * 剥掉，于是这三级拿不到 POST/body/charset，用 JS 拼目录地址、POST 目录接口的书源全默默降级成 GET。
 */
internal fun resolveUrl(base: String, ref: String): String {
    val trimmed = ref.trim()
    if (trimmed.isEmpty()) return trimmed
    val optAt = optionsIndex(trimmed)
    val bare = (if (optAt >= 0) trimmed.substring(0, optAt) else trimmed).trim()
    val suffix = if (optAt >= 0) trimmed.substring(optAt) else ""
    val resolved = when {
        bare.isEmpty() -> bare
        bare.startsWith("http://") || bare.startsWith("https://") -> bare
        else -> base.toHttpUrlOrNull()?.resolve(bare)?.toString() ?: bare
    }
    return resolved + suffix
}

/** 去掉 `,{…}` 选项后的地址本体 */
private fun bareUrl(url: String): String {
    val optAt = optionsIndex(url)
    return if (optAt >= 0) url.substring(0, optAt) else url
}

/**
 * 站点的来源（`https://www.example.com/`）：取图被拒时当 Referer 带上（见 [BookSourceEngine.getImage]）。
 * 浏览器跨站取图默认也只带来源，图床认的就是它。不是 http(s) 地址返回 null。
 */
internal fun siteOrigin(url: String): String? {
    val u = bareUrl(url).trim().toHttpUrlOrNull() ?: return null
    val port = if (u.port != okhttp3.HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""
    return "${u.scheme}://${u.host}$port/"
}

/** 能拿去抓的地址：http(s)。`javascript:void(0)`、空串、未能解析成绝对地址的相对路径都不算 */
internal fun isFetchable(url: String): Boolean {
    val bare = bareUrl(url).trim()
    return bare.startsWith("http://", ignoreCase = true) || bare.startsWith("https://", ignoreCase = true)
}

/**
 * 翻页时判「这一页去过没有」用的键：去掉 `#` 片段（片段不发给服务器，是同一页）。
 *
 * 末页的「下一页」常写成 `href="#"`，解析出来是当前页地址加个 `#`；按原字符串比会被当成新的一页
 * 再抓一遍，正文就重复了一整页。只在这里去，不改 [resolveUrl] —— 用 hash 路由的单页站点
 * （`/#/book/1`）走 WebView 渲染时，片段是有意义的。
 */
internal fun pageKey(url: String): String {
    val optAt = optionsIndex(url)
    val bare = if (optAt >= 0) url.substring(0, optAt) else url
    val suffix = if (optAt >= 0) url.substring(optAt) else ""
    return bare.substringBefore('#') + suffix
}

/** Legado 的地址选项后缀：`url,{"method":"POST","body":"…"}`（常用单引号） */
internal data class UrlOptions(
    val method: String? = null,
    val body: String? = null,
    val charset: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /**
     * 书源声明这个地址要用 WebView 打开、等页面脚本跑完再取 HTML（Legado 的 `webView`）：
     * 结果是页面加载后再由脚本拉回来的站（七猫的网页版搜索），静态抓到的只是一个空壳。
     */
    val webView: Boolean = false,
)

/**
 * 找到地址里真正开启 `,{…}` 选项块的下标，找不到返回 -1。
 *
 * 不能见到第一个 `,{` 就认 —— 那个 `,{` 可能在 JS 字符串**里面**：
 * `@js:url="https://m.wcxsw.org/search.php,{'body':'…'}"`。所以逐个候选位置试着把 `{…}` 解析成
 * JSON（单引号也认），能解析出来的才是真选项。从前 stripUrlOptions 用裸 `indexOf(",{")`
 * 不验证，把地址从中间截断成 `@js:url="https://m.wcxsw.o`，请求直接 403。
 *
 * 都解析不出来时再按结构认一次（[structuralOptionsIndex]）：模板还没展开的选项块不是合法 JSON。
 */
private fun optionsIndex(url: String): Int {
    var i = url.indexOf(",{")
    while (i > 0) {
        if (parseLenientJsonObject(url.substring(i + 1)) != null) return i
        i = url.indexOf(",{", i + 1)
    }
    return structuralOptionsIndex(url)
}

/**
 * 结构判据：顶层（不在任何花括号里）的 `,{`，且从它起花括号恰好在串尾配平。不要求是合法 JSON ——
 * 展开前的 `{"body":{"page":{{page}}}}` 就不是，展开后的值也可能把引号弄坏。
 *
 * 真实地址里不会出现裸花括号，能在串尾配平的 `,{…}` 只可能是选项块；JS 字符串里的 `,{…}`（见上）
 * 后面还跟着脚本，配不到串尾。`,{{page}}` 是模板不是选项块（Legado 的选项恒以单个 `{` 开头）。
 */
private fun structuralOptionsIndex(url: String): Int {
    val s = url.trimEnd()
    if (!s.endsWith('}')) return -1
    var depth = 0
    for (i in s.indices) {
        when (s[i]) {
            '{' -> {
                if (depth == 0 && i > 1 && s[i - 1] == ',' && s.getOrNull(i + 1) != '{' && closesAtEnd(s, i)) {
                    return i - 1
                }
                depth++
            }
            '}' -> if (depth > 0) depth--
        }
    }
    return -1
}

/** 从 open 处的 `{` 起计数，配平的那个 `}` 是否恰好是串尾 */
private fun closesAtEnd(s: String, open: Int): Boolean {
    var depth = 0
    for (j in open until s.length) {
        when (s[j]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return j == s.length - 1
            }
        }
    }
    return false
}

/**
 * 切出地址尾部的 `,{…}` 选项；地址里没有选项块返回 null。
 *
 * 有选项块却解析不出来（展开出的值把 JSON 弄坏之类）时选项作废、照样把地址切出来：
 * 带着 `,{…}` 去抓只会是一个必然 404 的废地址。
 */
internal fun splitUrlOptions(url: String): Pair<String, UrlOptions>? {
    val i = optionsIndex(url)
    if (i < 0) return null
    return url.substring(0, i).trim() to (parseUrlOptions(url.substring(i + 1)) ?: UrlOptions())
}

/** 解析选项块 `{…}`（单引号、不加引号的键都认，见 [parseLenientJsonObject]）；不是 JSON 对象返回 null */
private fun parseUrlOptions(text: String): UrlOptions? {
    val obj = parseLenientJsonObject(text) ?: return null
    fun str(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    // 值为空的头照样发（同 Legado）：接口源常把一组头（含 `AUTHORIZATION: ""` 这类空值）连同键名一起算签名，
    // 少发一个，服务端按收到的头验签就对不上 —— 七猫的接口源因此整个 401。从前这里把空值滤掉了。
    // 只丢 JSON 的 null（没有值，不是空串）
    val hdr = (obj["headers"] as? kotlinx.serialization.json.JsonObject)
        ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { k to it } }
        ?.toMap()
        .orEmpty()
    // Legado 的 body 可以直接写成 JSON 对象/数组，发送时序列化成 JSON 文本（UrlOption.getBody 里的 toJson）。
    // 从前只认字符串，`"body":{"page":1}` 整个被丢掉，POST 发出去的是空 body
    val body = when (val b = obj["body"]) {
        null -> null
        is kotlinx.serialization.json.JsonPrimitive -> b.contentOrNull // 含 JsonNull → null
        else -> b.toString()
    }
    return UrlOptions(
        method = str("method")?.uppercase(),
        body = body,
        charset = str("charset"),
        headers = hdr,
        // 同 Legado 的 useWebView()：没有、空串、false / "false" 是不用，其余（true、"1"、任何字）都算要
        webView = when (val w = obj["webView"]) {
            null, is kotlinx.serialization.json.JsonNull -> false
            is kotlinx.serialization.json.JsonPrimitive -> w.content.isNotBlank() && !w.content.equals("false", true)
            else -> true
        },
    )
}

// ---- 搜索关键词代入请求 ----

/**
 * 请求模板里 `{{key}}` / `{{keyword}}` 的占位符。展开前先换成它、展开后再按所在位置换成转义好的关键词：
 * 地址里要百分号转义，表单里要表单编码，JSON 体里要 JSON 转义 —— 同一个值，三种写法。
 * 私用区字符：不会出现在真实地址/书源里，也不需要 JSON 转义。
 *
 * 只换字面的变量模板；`{{java.encodeURI(key)}}` 这类 JS 表达式拿到的 `key` 仍是原文（与 Legado 一致），
 * 否则自己转义过的书源会被转两遍。
 */
internal val KEYWORD_MARK = Char(0xE000) + "inkwell-keyword" + Char(0xE000)

private val KEYWORD_TEMPLATE = Regex("\\{\\{\\s*(?:key|keyword)\\s*\\}\\}")

internal fun markKeyword(template: String): String =
    if (!template.contains("{{")) template else KEYWORD_TEMPLATE.replace(template) { KEYWORD_MARK }

private fun charsetOrUtf8(name: String?): java.nio.charset.Charset =
    name?.takeIf { it.isNotBlank() }?.let { runCatching { charsetOf(it) }.getOrNull() } ?: Charsets.UTF_8

/**
 * 关键词在地址里的形式：按站点编码百分号转义，路径与查询串里都安全。
 * URLEncoder 把空格写成 `+`，那只在表单/查询里成立，放进路径就是字面加号，所以换成 `%20`；
 * 原文里的 `+` 已经先被转成 `%2B`，替换不会误伤。`# & % ? /` 一律转义。
 */
internal fun encodeUrlComponent(value: String, charsetName: String?): String =
    java.net.URLEncoder.encode(value, charsetOrUtf8(charsetName).name()).replace("+", "%20")

/**
 * 非 UTF-8 站点：查询串里还没转义的非 ASCII 字符按站点编码转义。
 *
 * 字面 {{key}} 已在占位符那一步转好了；这一步兜的是 JS 拼出来的地址（`@js:` 地址、`{{'…'+key}}`）——
 * 原文关键词直接进了地址，交给 OkHttp 会按 UTF-8 编码，GBK 站搜出来全是乱码或空。Legado 也是按
 * charset 转义查询字段的。只动非 ASCII：已有的 `%XX` 与 ASCII 原样保留，不会转两遍。
 */
internal fun encodeNonAsciiQuery(url: String, charsetName: String?): String {
    val cs = charsetOrUtf8(charsetName)
    if (cs == Charsets.UTF_8) return url
    val q = url.indexOf('?')
    if (q < 0) return url
    val query = url.substring(q + 1)
    if (query.all { it.code < 0x80 }) return url
    return url.substring(0, q + 1) + NON_ASCII_RUN.replace(query) { java.net.URLEncoder.encode(it.value, cs.name()) }
}

private val NON_ASCII_RUN = Regex("[^\\x00-\\x7F]+")

/**
 * 请求体里的关键词占位符换成编码好的关键词。
 * - JSON 体：JSON 字符串转义（关键词里的 `"` `\` 不该把整个请求体弄坏）
 * - POST 表单：表单编码。必须在拼进表单时做 —— 展开后再按 `&` 切，「A&B」就成了两个字段
 */
internal fun fillKeywordInBody(body: String, keyword: String, method: String, charsetName: String?): String {
    if (!body.contains(KEYWORD_MARK)) return body
    val t = body.trim()
    val value = when {
        t.startsWith("{") || t.startsWith("[") ->
            kotlinx.serialization.json.JsonPrimitive(keyword).toString().let { it.substring(1, it.length - 1) }
        method.equals("POST", ignoreCase = true) ->
            java.net.URLEncoder.encode(keyword, charsetOrUtf8(charsetName).name())
        else -> keyword
    }
    return body.replace(KEYWORD_MARK, value)
}
