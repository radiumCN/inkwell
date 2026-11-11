package com.radium.inkwell.data.repo

import androidx.room3.withWriteTransaction
import com.radium.inkwell.core.source.rss.RssArticle
import com.radium.inkwell.core.source.rss.RssDates
import com.radium.inkwell.core.source.rss.RssEngine
import com.radium.inkwell.core.source.rss.RssSort
import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.db.dao.RssArticleDao
import com.radium.inkwell.data.db.entity.RssArticleEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 订阅页要取的一条列表：某个源的某个分类 */
data class RssFeed(val rule: RssSourceRule, val sort: RssSort) {
    val key: String get() = RssFeedRepository.feedKey(rule.id, sort.url)
}

/**
 * 订阅页（时间线）的文章：取、存、记已读、清理。
 *
 * - **取过就不再取**：这次打开 App 里某条列表取成功过，再进订阅页直接显示缓存，下拉才重取（[refresh] 的 force）。
 *   记在进程里（[fetchedThisSession]）：App 被杀、重开就是新的一次。
 * - **先显示上次的**：文章存在库里（[RssArticleEntity]），页面订阅 [observe]，取回来的写进库、页面跟着变。
 * - **读没读**在同一行上（[markRead]）。
 * - **清理**跟着「自动清理已读缓存」的天数（[prune]，冷启动在后台跑）。
 */
class RssFeedRepository(
    private val db: InkwellDb,
    private val dao: RssArticleDao,
    private val engine: RssEngine,
) {
    private val fetchedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun observe(feeds: List<String>): Flow<List<RssArticleEntity>> = dao.observeByFeeds(feeds)

    /** 这条列表这次打开 App 里取成功过没有 */
    fun fetchedThisSession(feed: String): Boolean = feed in fetchedThisSession

    /**
     * 把 [feeds] 取一遍、写进库。[force] = false 时跳过这次已经取过的。几个源一起取（最多 [CONCURRENCY] 个），
     * 一个源坏了不耽误别的：返回取失败的（源 id → 原因），没失败的不在里面。
     */
    suspend fun refresh(feeds: List<RssFeed>, force: Boolean): Map<String, String> = coroutineScope {
        val todo = if (force) feeds else feeds.filter { it.key !in fetchedThisSession }
        val gate = Semaphore(CONCURRENCY)
        todo.map { feed ->
            async {
                gate.withPermit {
                    try {
                        fetchOne(feed)
                        null
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        feed.rule.id to failureText(e)
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    private suspend fun fetchOne(feed: RssFeed) {
        val page = engine.articles(feed.rule, feed.sort.url)
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            // 读旧行和写新行在一个事务里：两次取同一条列表叠在一起时，后写的不会拿着过期的旧行把读过的记号盖掉
            db.withWriteTransaction {
                val existing = dao.getByFeed(feed.key)
                dao.upsertAll(mergeFetched(feed.key, feed.rule.id, existing, page.items, now))
            }
        }
        fetchedThisSession += feed.key
    }

    suspend fun markRead(sourceId: String, articleKey: String) =
        dao.markRead(sourceId, articleKey, System.currentTimeMillis())

    /** 订阅源删掉时连它的文章一起清 */
    suspend fun deleteBySource(sourceId: String) {
        dao.deleteBySource(sourceId)
        fetchedThisSession.removeIf { it.startsWith(sourceId + FEED_SEPARATOR) }
    }

    /**
     * 定时清理（冷启动后在后台跑，同正文缓存）：删掉的订阅源留下的一律清；[maxAgeDays] 天里一次都没再取到的清掉
     * （订阅源已经撤下了）。[maxAgeDays] 为 0 = 用户关了自动清理，只清孤儿。
     */
    suspend fun prune(maxAgeDays: Int, now: Long = System.currentTimeMillis()) {
        dao.deleteOrphans()
        if (maxAgeDays <= 0) return
        dao.deleteSeenBefore(now - maxAgeDays * DAY_MS)
    }

    companion object {
        private const val CONCURRENCY = 4
        private const val DAY_MS = 86_400_000L
        private const val FEED_SEPARATOR = "\n"

        fun feedKey(sourceId: String, sortUrl: String): String = sourceId + FEED_SEPARATOR + sortUrl

        /**
         * 取回来的一页和库里旧的合起来，得到要写进库的行。只写这次取到的：旧的、这次没出现的留着不动
         * （订阅源撤下的文章照样留在时间线上，到期由 [prune] 清）。
         *
         * - 第一次取到的时刻、读过的时刻沿用旧行：不然每刷一次，没有发布时间的文章都「变新」、读过的又变回没读
         * - 最近一次取到的时刻 = [now]，位置 = 这一页里的下标：只看一个源时照源自己的顺序排
         * - 同一页里重复的（抓网页的源常把置顶在列表里再列一遍）只留第一条
         */
        internal fun mergeFetched(
            feed: String,
            sourceId: String,
            existing: List<RssArticleEntity>,
            fetched: List<RssArticle>,
            now: Long,
        ): List<RssArticleEntity> {
            val old = existing.associateBy { it.articleKey }
            return fetched.distinctBy { it.key }.mapIndexed { index, a ->
                val prev = old[a.key]
                RssArticleEntity(
                    feed = feed,
                    articleKey = a.key,
                    sourceId = sourceId,
                    title = a.title,
                    link = a.link,
                    description = a.description,
                    image = a.image,
                    pubDate = a.pubDate,
                    pubTime = RssDates.parse(a.pubDate, now) ?: 0L,
                    firstSeenAt = prev?.firstSeenAt ?: now,
                    lastSeenAt = now,
                    position = index,
                    readAt = prev?.readAt ?: 0L,
                )
            }
        }

        /** 页面上那一行「某某 没更新上（原因）」里的原因：说人话，别把异常原文整段甩出来 */
        internal fun failureText(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.toList()
            return when {
                chain.any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } -> "连接超时"
                chain.any { it is java.net.UnknownHostException } -> "连不上网络"
                chain.any { it is java.net.ConnectException || it is javax.net.ssl.SSLException } -> "连不上服务器"
                else -> HTTP_CODE.find(e.message.orEmpty())?.let { "HTTP ${it.groupValues[1]}" } ?: "取不到文章"
            }
        }

        /** 抓取客户端报的是「HTTP 403: 地址」：只认这个开头，别把地址里碰巧的三位数当状态码 */
        private val HTTP_CODE = Regex("HTTP (\\d{3})")
    }
}
