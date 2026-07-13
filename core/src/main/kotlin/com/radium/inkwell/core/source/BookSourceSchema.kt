package com.radium.inkwell.core.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 书源 JSON 统一编解码实例 */
val BookSourceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 书源规则根对象。
 * 模板变量：{{keyword}} {{page}}（从 1 起）{{baseUrl}}；
 * 管道 {{keyword|encode:gbk}} 做指定编码的 URL 编码（encode 不带参数按 UTF-8）。
 */
@Serializable
data class BookSourceRule(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val baseUrl: String,
    val version: Int = 1,
    val comment: String = "",
    val enabled: Boolean = true,
    /** 站点编码；null = 自动嗅探（Content-Type > meta），gbk/gb2312 一律按 GB18030 解码 */
    val charset: String? = null,
    /** 源级默认请求头 */
    val headers: Map<String, String> = emptyMap(),
    val rateLimit: RateLimitRule? = null,
    val search: SearchRule? = null,
    val detail: DetailRule? = null,
    val toc: TocRule? = null,
    val content: ContentRule? = null,
    val explore: List<ExploreRule> = emptyList(),
) {
    fun toJson(): String = BookSourceJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): BookSourceRule =
            BookSourceJson.decodeFromString(serializer(), text)
    }
}

/** 令牌桶限速：每 intervalMs 补一个令牌，桶容量 burst */
@Serializable
data class RateLimitRule(
    val intervalMs: Long = 0,
    val burst: Int = 1,
)

@Serializable
data class RequestRule(
    /** 可为相对路径，支持模板变量 */
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** 请求级编码，优先于源级 */
    val charset: String? = null,
)

@Serializable
data class SearchRule(
    val request: RequestRule,
    /** 列表规则，求值为节点列表 */
    val list: String,
    /** title/bookUrl 必填，author/coverUrl/intro/latestChapter 可选 */
    val fields: Map<String, String> = emptyMap(),
    val nextPage: String? = null,
)

@Serializable
data class DetailRule(
    /** title/author/coverUrl/intro/tocUrl；tocUrl 缺省时用详情页自身 URL */
    val fields: Map<String, String> = emptyMap(),
)

@Serializable
data class TocRule(
    val list: String,
    /** title/url */
    val fields: Map<String, String> = emptyMap(),
    val nextPage: String? = null,
    /** true 时目录整体倒序 */
    val reverse: Boolean = false,
)

@Serializable
data class ContentRule(
    /** 正文规则，通常取 @html */
    val content: String,
    val nextPage: String? = null,
    val purify: List<PurifyRule> = emptyList(),
)

@Serializable
data class ExploreRule(
    val name: String,
    /** 支持 {{page}} 模板 */
    val url: String,
    val list: String,
    val fields: Map<String, String> = emptyMap(),
    val nextPage: String? = null,
)

/** 净化规则：对段落文本做替换，替换后全空的段落丢弃 */
@Serializable
data class PurifyRule(
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
)
