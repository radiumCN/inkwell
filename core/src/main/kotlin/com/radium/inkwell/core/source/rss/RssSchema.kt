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

    /**
     * 单网页源（阅读的 singleUrl）：没有文章列表，点开就是用应用内网页打开 [pageUrl]（源仓库、使用文档、工具站）。
     * 从前导入时整条跳过，一份合集导进来少一大半；阅读的做法是照样列着、点开就是网页，这里照做。
     */
    val singleUrl: Boolean = false,
    /** 网页里的脚本开不开（阅读的 enableJs，缺省开）。只有单网页源的网页页用得上 */
    val enableJs: Boolean = true,
    /** 公共脚本库（阅读的 jsLib）：分类地址、列表、正文规则里的脚本都能调它定义的函数 */
    val jsLib: String? = null,
) {
    /** 至少有一个分类：没配分类的源，源地址本身就是那个分类 */
    fun sortsOrDefault(): List<RssSort> =
        sorts.ifEmpty { listOf(RssSort(title = name, url = id)) }

    /**
     * 单网页源点开时打开的地址；不是单网页源、或者地址要跑脚本才算得出来，为 null。
     *
     * 照阅读（RssSourceViewModel.getSingleUrl）：配了分类地址就用第一个分类的地址，没配就用源地址。
     * 阅读的地址后面可以跟 `,{…}` 选项：带 `js` 的要跑脚本才知道真正的地址，做不了；其余的（请求头之类）丢掉，
     * 地址本身照样打开。
     */
    fun pageUrl(): String? {
        if (!singleUrl) return null
        var url = (sorts.firstOrNull()?.url ?: id).trim()
        if (url.contains("@js:", ignoreCase = true) || url.contains("<js>", ignoreCase = true)) return null
        val options = url.indexOf(",{")
        if (options > 0) {
            if (url.substring(options).contains("\"js\"")) return null
            url = url.substring(0, options).trim()
        }
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        return url
    }

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
