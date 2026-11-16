package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.rss.RssArticle
import com.radium.inkwell.data.db.entity.RssArticleEntity
import com.radium.inkwell.data.db.entity.RssArticleSeen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 取回来的一页怎么并进缓存。错一处的后果都是用户看得见的：读过的变回没读、没时间的文章每刷一次都蹿到最上面、
 * 同一页里重复的文章撞主键。
 */
class RssFeedMergeTest {

    private val feed = RssFeedRepository.feedKey("https://a.com/feed", "https://a.com/feed")

    private fun article(link: String, title: String = link, pubDate: String? = null) =
        RssArticle(sourceId = "https://a.com/feed", title = title, link = link, pubDate = pubDate)

    /** 库里读回来的旧行只有沿用的那几样（RssArticleDao.getSeenByFeed）；测试里拿上一次合并出的整行折成它 */
    private fun merge(existing: List<RssArticleEntity>, fetched: List<RssArticle>, now: Long) =
        RssFeedRepository.mergeFetched(
            feed, "https://a.com/feed",
            existing.map { RssArticleSeen(it.articleKey, it.firstSeenAt, it.readAt) },
            fetched, now,
        )

    @Test
    fun `第一次取到：时刻都是现在，位置照这一页`() {
        val rows = merge(emptyList(), listOf(article("https://a.com/1"), article("https://a.com/2")), now = 1_000)
        assertEquals(listOf(0, 1), rows.map { it.position })
        assertEquals(listOf(1_000L, 1_000L), rows.map { it.firstSeenAt })
        assertEquals(listOf(1_000L, 1_000L), rows.map { it.lastSeenAt })
        assertEquals(listOf(0L, 0L), rows.map { it.readAt })
    }

    @Test
    fun `再取一次：读过的、第一次见到的时刻沿用，最近一次和位置换新`() {
        val first = merge(emptyList(), listOf(article("https://a.com/1"), article("https://a.com/2")), now = 1_000)
        val read = first.map { if (it.articleKey == "https://a.com/1") it.copy(readAt = 1_500) else it }
        // 源里新来了一篇排在最前面
        val second = merge(read, listOf(article("https://a.com/0"), article("https://a.com/1"), article("https://a.com/2")), now = 2_000)
        val byKey = second.associateBy { it.articleKey }
        assertEquals(1_500L, byKey.getValue("https://a.com/1").readAt)
        assertEquals(1_000L, byKey.getValue("https://a.com/1").firstSeenAt)
        assertEquals(2_000L, byKey.getValue("https://a.com/1").lastSeenAt)
        assertEquals(1, byKey.getValue("https://a.com/1").position)
        assertEquals(2_000L, byKey.getValue("https://a.com/0").firstSeenAt)
        assertEquals(0L, byKey.getValue("https://a.com/0").readAt)
    }

    @Test
    fun `同一页里重复的只留第一条`() {
        // 抓网页的源常把置顶在列表里再列一遍：两条同一个主键，一起写进库后一条盖前一条，位置也乱
        val rows = merge(emptyList(), listOf(article("https://a.com/1"), article("https://a.com/2"), article("https://a.com/1")), now = 1)
        assertEquals(listOf("https://a.com/1", "https://a.com/2"), rows.map { it.articleKey })
    }

    @Test
    fun `没有链接的按标题当标识`() {
        val rows = merge(emptyList(), listOf(article(link = "", title = "第 3 期")), now = 1)
        assertEquals("第 3 期", rows.single().articleKey)
    }

    @Test
    fun `发布时间解析好存下，认不出是 0`() {
        val rows = merge(
            emptyList(),
            listOf(article("https://a.com/1", pubDate = "2026-11-11T00:00:00Z"), article("https://a.com/2", pubDate = "第 3 期")),
            now = 1,
        )
        assertEquals(listOf(1_794_355_200_000L, 0L), rows.map { it.pubTime })
    }

    @Test
    fun `取失败的原因说人话`() {
        assertEquals("连接超时", RssFeedRepository.failureText(java.net.SocketTimeoutException("timeout")))
        assertEquals("连不上网络", RssFeedRepository.failureText(java.io.IOException("x", java.net.UnknownHostException("a.com"))))
        assertEquals("HTTP 403", RssFeedRepository.failureText(java.io.IOException("HTTP 403: https://a.com/500")))
        assertEquals("取不到文章", RssFeedRepository.failureText(IllegalStateException("规则没匹配到 https://a.com/404")))
    }
}
