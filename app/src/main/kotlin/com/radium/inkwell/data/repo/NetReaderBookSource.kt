package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.core.source.PrivateNetworkGuardDns
import com.radium.inkwell.reader.api.ReaderBookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 网络书的阅读数据源：磁盘缓存优先，未命中走书源引擎抓取并落盘 */
class NetReaderBookSource(
    private val bookId: String,
    private val chapters: List<ChapterEntity>,
    private val rule: BookSourceRule,
    private val engine: BookSourceEngine,
    private val cache: ChapterContentCache,
    private val chapterDao: ChapterDao,
    private val http: OkHttpClient = IMAGE_HTTP,
) : ReaderBookSource {

    override val chapterCount: Int get() = chapters.size

    /** 交给引擎作为正文翻页的止步集：翻到别的章节说明本章已完 */
    private val chapterUrls: Set<String> by lazy {
        chapters.mapNotNullTo(HashSet()) { it.url }
    }

    override fun chapterTitle(index: Int): String? = chapters.getOrNull(index)?.title

    override suspend fun loadChapter(index: Int): ChapterContent {
        val chapter = chapters.getOrNull(index) ?: error("章节不存在: $index")
        val url = chapter.url ?: error("章节缺少地址")
        // 缓存以章节 URL 为 key：目录变动后序号会错位，按序号读会读出别的章节。
        // 读盘（含 MD5 计算）切到 IO：阅读器在主线程调用，翻章时别拿它掉帧。
        withContext(Dispatchers.IO) { cache.read(bookId, url) }?.let { return it }
        // 目录阶段 @put 存的变量随章节一起落了库，这里喂回给正文规则的 @get
        val remote = engine.getContent(rule, url, chapterUrls, chapterVariable = chapter.variable)
        val content = ChapterContent(remote.elements)
        withContext(Dispatchers.IO) {
            cache.write(bookId, url, content)
            chapterDao.markCached(bookId, index, true)
        }
        return content
    }

    override suspend fun loadImage(resourceId: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(resourceId).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    private companion object {
        /**
         * 插图下载。地址同样由书源规则给出（不可信），所以和正文抓取一视同仁：
         * 走内网拦截的 DNS，并配全套超时 —— 阅读器翻到带图的一页会等它，不能无限期等。
         *
         * 建成 companion 的单例而非每本书新建：OkHttpClient 自带连接池和线程池，
         * 每开一本书造一个是白扔资源。
         */
        val IMAGE_HTTP: OkHttpClient = OkHttpClient.Builder()
            .dns(PrivateNetworkGuardDns())
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
            .callTimeout(java.time.Duration.ofSeconds(45))
            .build()
    }
}
