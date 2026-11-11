package com.radium.inkwell.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.radium.inkwell.data.db.entity.RssArticleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RssArticleDao {

    /** 这几个「源 + 分类」里缓存着的文章。排序在 RssFeedRepository 里做：「全部」和单个源排法不一样 */
    @Query("SELECT * FROM rss_article WHERE feed IN (:feeds)")
    fun observeByFeeds(feeds: List<String>): Flow<List<RssArticleEntity>>

    @Query("SELECT * FROM rss_article WHERE feed = :feed")
    suspend fun getByFeed(feed: String): List<RssArticleEntity>

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
