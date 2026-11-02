package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.core.source.guardPrivateNetwork
import com.radium.inkwell.reader.api.ReaderBookSource
import com.radium.inkwell.util.SingleFlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 网络书的阅读数据源：磁盘缓存优先，未命中走书源引擎抓取并落盘 */
class NetReaderBookSource(
    private val bookId: String,
    chapters: List<ChapterEntity>,
    private val rule: BookSourceRule,
    private val engine: BookSourceEngine,
    private val cache: ChapterContentCache,
    private val chapterDao: ChapterDao,
    /** 详情页存下的书级变量（BookEntity.variable 解出来的）；正文规则 @get 取不到本章变量时落到这里 */
    private val bookVariables: Map<String, String> = emptyMap(),
    private val http: OkHttpClient = IMAGE_HTTP,
) : ReaderBookSource {

    /**
     * 目录 + 由它派生的 URL 止步集，成对换（一个 volatile 引用，读的人不会拿到「新目录配旧止步集」）。
     *
     * 从前进书时拍一张快照就再也不动：阅读中后台追更换了目录（插入公告章、补上新章），目录面板读的是库里的
     * 新目录，这里还按旧的那张取章 —— 新章点了没反应，插入点之后点第 i 章打开的是旧目录的第 i 章。
     * 现在由阅读页在目录变化时 [updateChapters]，序号与目录面板始终是同一套。
     */
    private class Toc(val chapters: List<ChapterEntity>) {
        /** 交给引擎作为正文翻页的止步集：翻到别的章节说明本章已完 */
        val urls: Set<String> by lazy { chapters.mapNotNullTo(HashSet()) { it.url } }
    }

    @Volatile
    private var toc = Toc(chapters)

    /** 当前这份目录（序号 = 下标）。阅读页拿它和库里的新目录比对、按章节 URL 重新对位 */
    val chapters: List<ChapterEntity> get() = toc.chapters

    fun updateChapters(chapters: List<ChapterEntity>) {
        toc = Toc(chapters)
    }

    /**
     * 换源进行中：抓到的正文照常交给阅读页显示，但**不落盘、不打已缓存标记**。
     *
     * 换源会先清掉整本书的正文缓存再写新目录。这期间用户关掉面板接着翻，正文还是走这个旧源抓的 ——
     * 落了盘，清缓存之后它就成了新目录里的「已缓存」：两个书源规则抓同一个站点时 URL 一模一样，
     * 换源多半就是嫌旧规则抓出来的正文不对，结果新源读到的还是旧规则那份。换源失败则由阅读页恢复写入。
     */
    @Volatile
    var cacheWritesHeld: Boolean = false

    override val chapterCount: Int get() = toc.chapters.size

    override fun chapterTitle(index: Int): String? = toc.chapters.getOrNull(index)?.title

    /**
     * 同一章同时只抓一次（按章节 URL 合并）。
     *
     * 阅读页每出一页就可能排一轮邻章（showPage、showPosition、半排版回调各一次），每轮都对 center±1
     * 调 loadChapter —— 不合并的话同一章被并发抓两到四遍：白耗流量不说，JS 源要在 WebView 渲染锁上
     * 排队、限速源要排令牌，用户正等着的那一章被这些重复请求挤到超时，随后误触发自动换源、清缓存。
     */
    private val loads = SingleFlight<String, ChapterContent>(Dispatchers.IO)

    override suspend fun loadChapter(index: Int): ChapterContent {
        val toc = toc
        val chapter = toc.chapters.getOrNull(index) ?: error("章节不存在: $index")
        val url = chapter.url ?: error("章节缺少地址")
        // 缓存以章节 URL 为 key：目录变动后序号会错位，按序号读会读出别的章节。
        // 读盘（含 MD5 计算）切到 IO：阅读器在主线程调用，翻章时别拿它掉帧。
        withContext(Dispatchers.IO) { cache.read(bookId, url) }?.let { return it }
        return loads.run(url) {
            // 目录阶段 @put 存的变量随章节一起落了库，这里喂回给正文规则的 @get
            val remote = engine.getContent(
                rule, url, toc.urls,
                chapterVariable = chapter.variable,
                chapterTitle = chapter.title.orEmpty(),
                chapterIndex = index,
                variables = bookVariables,
            )
            val content = ChapterContent(remote.elements)
            store(url, content)
            content
        }
    }

    override suspend fun prefetchChapter(index: Int) {
        val toc = toc
        val chapter = toc.chapters.getOrNull(index) ?: return
        val url = chapter.url ?: return
        // 只问「有没有」，别 read：命中时整章读进来解析一遍再扔掉，每到章首章尾都要这样过几章（见 hasCurrent）
        if (withContext(Dispatchers.IO) { cache.hasCurrent(bookId, url) }) return
        // 预取禁止 JS 渲染：WebView 只能在主线程跑，翻页/卷页跟手会被 Chromium 抢走。
        // 静态抓不到就放弃，等用户真翻到这一章再走 loadChapter 的渲染回退。
        val remote = engine.getContent(
            rule, url, toc.urls,
            chapterVariable = chapter.variable,
            allowJsRender = false,
            chapterTitle = chapter.title.orEmpty(),
            chapterIndex = index,
            variables = bookVariables,
        )
        store(url, ChapterContent(remote.elements))
    }

    /**
     * 落盘 + 在目录里标「已缓存」。
     *
     * 标记按章节 URL 认行，而不是信传进来的序号：抓一章要好几秒，这期间后台追更可能已经整本换过目录 ——
     * 旧序号上躺着的是另一章，打错了标记，目录里没缓存的章亮着、缓存了的反倒灭着。
     * 一条按 URL 的 UPDATE 搞定：先查后改的两步之间目录照样可能被换掉。
     */
    private suspend fun store(url: String, content: ChapterContent) {
        if (cacheWritesHeld) return
        withContext(Dispatchers.IO) {
            cache.write(bookId, url, content)
            chapterDao.markCachedByUrl(bookId, url, true)
        }
    }

    override suspend fun loadImage(resourceId: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(resourceId).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body.bytes() else null
            }
        }.getOrNull()
    }

    private companion object {
        /**
         * 插图下载。地址同样由书源规则给出（不可信），所以和正文抓取一视同仁：
         * 走内网拦截，并配全套超时 —— 阅读器翻到带图的一页会等它，不能无限期等。
         *
         * 建成 companion 的单例而非每本书新建：OkHttpClient 自带连接池和线程池，
         * 每开一本书造一个是白扔资源。
         */
        val IMAGE_HTTP: OkHttpClient = OkHttpClient.Builder()
            .guardPrivateNetwork()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
            .callTimeout(java.time.Duration.ofSeconds(45))
            .build()
    }
}
