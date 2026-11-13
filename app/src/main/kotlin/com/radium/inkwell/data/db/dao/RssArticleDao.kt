package com.radium.inkwell.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.radium.inkwell.data.db.entity.RssArticleEntity
import com.radium.inkwell.data.db.entity.RssArticleRow
import com.radium.inkwell.data.db.entity.RssArticleSeen
import kotlinx.coroutines.flow.Flow

@Dao
interface RssArticleDao {

    /**
     * 这几个「源 + 分类」里缓存着的文章，**不带全文**（理由见 [RssArticleRow]）。
     * 排序在 RssFeedRepository 里做：「全部」和单个源排法不一样
     */
    @Query(
        "SELECT feed, articleKey, sourceId, title, link, image, pubDate, pubTime, firstSeenAt, lastSeenAt, position, readAt " +
            "FROM rss_article WHERE feed IN (:feeds)",
    )
    fun observeRowsByFeeds(feeds: List<String>): Flow<List<RssArticleRow>>

    /** 合并新取到的一页要的旧行：只取沿用的那几样，不把这条列表攒下的全文整读一遍 */
    @Query("SELECT articleKey, firstSeenAt, readAt FROM rss_article WHERE feed = :feed")
    suspend fun getSeenByFeed(feed: String): List<RssArticleSeen>

    /** 点开一篇时取它的全文（时间线的行里没有它） */
    @Query("SELECT description FROM rss_article WHERE feed = :feed AND articleKey = :articleKey")
    suspend fun getDescription(feed: String, articleKey: String): String?

    @Upsert
    suspend fun upsertAll(rows: List<RssArticleEntity>)

    /** 标成读过：同一篇在这个源的几个分类里各有一行，一起改；已经读过的不动（留着第一次读的时刻） */
    @Query("UPDATE rss_article SET readAt = :now WHERE sourceId = :sourceId AND articleKey = :articleKey AND readAt = 0")
    suspend fun markRead(sourceId: String, articleKey: String, now: Long)

    /** 定时清理：超过期限没再取到的（源里已经撤下了） */
    @Query("DELETE FROM rss_article WHERE lastSeenAt < :cutoff")
    suspend fun deleteSeenBefore(cutoff: Long): Int

    /** 订阅源已经删了（本机删的、或同步来的墓碑）留下的文章 */
    @Query("DELETE FROM rss_article WHERE sourceId NOT IN (SELECT id FROM rss_source WHERE deleted = 0)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM rss_article WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)
}
