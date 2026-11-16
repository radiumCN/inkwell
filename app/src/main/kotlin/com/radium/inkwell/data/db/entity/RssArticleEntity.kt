package com.radium.inkwell.data.db.entity

import androidx.room3.Entity
import androidx.room3.Index

/**
 * 订阅文章的本机缓存：订阅页打开先显示上次取到的，再在后台更新；断网也有东西看。
 * 同一行还记着读没读（[readAt]），读过的标题变灰。
 *
 * 一行 = 某个源某个分类里的一篇。同一篇出现在一个源的好几个分类里会存好几行（各分类的列表顺序不一样），
 * 读没读按「源 + 文章」整组改（见 RssArticleDao.markRead）。
 *
 * 只是缓存：不进 WebDAV 备份（换台设备照样能重新取），清理跟着「自动清理已读缓存」的天数走 ——
 * 订阅源里已经撤下、超过那么多天没再取到的删掉（[lastSeenAt]，见 RssFeedRepository.prune）。
 * 还在源里的不删：删了下次取又回来，读过的记号却没了。
 */
@Entity(
    tableName = "rss_article",
    primaryKeys = ["feed", "articleKey"],
    indices = [Index(value = ["sourceId"], name = "idx_rss_article_source")],
)
data class RssArticleEntity(
    /** 哪个源的哪个分类：`源地址 + "\n" + 分类地址`（见 RssFeedRepository.feedKey） */
    val feed: String,
    /** 文章在源里的标识：链接，没有链接就是标题（同 RssArticle.key） */
    val articleKey: String,
    val sourceId: String,
    val title: String,
    val link: String,
    val description: String?,
    val image: String?,
    /** feed 里的发布时间原文 */
    val pubDate: String?,
    /** [pubDate] 解析出的时刻；认不出是 0（见 core 的 RssDates） */
    val pubTime: Long,
    /** 第一次取到的时刻：没有发布时间的文章在「全部」里按它排 */
    val firstSeenAt: Long,
    /** 最近一次取到的时刻：同一次取到的整批是同一个值；清理按它算 */
    val lastSeenAt: Long,
    /** 在最近一次取到的列表里排第几：只看一个源时照源自己的顺序排 */
    val position: Int,
    /** 读过的时刻；0 = 没读过 */
    val readAt: Long = 0,
)

/**
 * 时间线上的一行：[RssArticleEntity] 去掉 [RssArticleEntity.description]。
 *
 * description 存的是全文（feed 的 content:encoded 优先，见 core 的 RssXmlParser），一篇几 KB 到几十 KB，
 * 时间线的行却只显示标题、来源、时间和配图。从前列表查的是 `SELECT *`：表按整张失效，「全部」刷新 N 个源
 * 就把所有源、所有缓存着的文章（撤下的还要留够清理天数）连同全文整读 N 遍 —— 几十个源攒上几千行，
 * 每一遍就是几十 MB 的字符串。全文只在点开那一篇时按主键取（RssArticleDao.getDescription）。
 */
data class RssArticleRow(
    val feed: String,
    val articleKey: String,
    val sourceId: String,
    val title: String,
    val link: String,
    val image: String?,
    val pubDate: String?,
    val pubTime: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val position: Int,
    val readAt: Long = 0,
)

/** 合并新取到的一页时只要旧行的这几样（沿用第一次取到的时刻与读过的记号），见 RssFeedRepository.mergeFetched */
data class RssArticleSeen(
    val articleKey: String,
    val firstSeenAt: Long,
    val readAt: Long,
)
