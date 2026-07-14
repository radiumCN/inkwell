package com.radium.inkwell.core.source.rss

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 订阅源。
 *
 * 与书源同构，但少了"搜索/目录"这两级：订阅只有 分类 → 文章列表 → 文章正文。
 *
 * [articles] 为空是**常态**而不是错误：现实里绝大多数订阅源就是一个标准的
 * RSS/Atom XML feed，压根没有规则。这种源由 [RssXmlParser] 直接解析，
 * 用户只要填个地址就能订阅 —— 逼他去写规则毫无道理。
 */
@Serializable
data class RssSourceRule(
    /** 源地址，同时用作主键（同 legado 的 sourceUrl） */
    val id: String,
    val name: String,
    val icon: String = "",
    val group: String = "",
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val charset: String? = null,
    /** 分类。为空时用源地址本身当唯一分类 */
    val sorts: List<RssSort> = emptyList(),

    /** 文章列表规则；null = 按标准 RSS/Atom XML 解析 */
    val articles: String? = null,
    val nextPage: String? = null,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val link: String? = null,
    val pubDate: String? = null,
    /** 文章正文规则；null = 回落到列表里的 description，再没有就只能外链打开 */
    val content: String? = null,
) {
    /** 至少有一个分类：没配分类的源，源地址本身就是那个分类 */
    fun sortsOrDefault(): List<RssSort> =
        sorts.ifEmpty { listOf(RssSort(title = name, url = id)) }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): RssSourceRule = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class RssSort(val title: String, val url: String)

/** 一篇文章 */
data class RssArticle(
    val sourceId: String,
    val title: String,
    val link: String,
    val description: String? = null,
    val image: String? = null,
    val pubDate: String? = null,
) {
    /** 列表里同一篇文章的标识：link 优先，没有就退到标题 */
    val key: String get() = link.ifBlank { title }
}

data class RssArticlePage(
    val items: List<RssArticle>,
    val hasMore: Boolean = false,
)
