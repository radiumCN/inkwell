package com.radium.inkwell.core.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 书源 JSON 统一编解码实例；存的是 Legado 原生格式，未知字段忽略 */
val BookSourceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}

/**
 * 书源规则根对象 —— **Legado 原生形状**（字段名与开源阅读一致）。
 *
 * 规则串在运行期由 [LegadoRuleAnalyzer] 直接编译求值，不再经导入期转译。只覆盖小说源
 * （`bookSourceType == 0`）；音频/漫画/文件源在导入时被过滤。
 *
 * 顶层访问器（[id]/[name]/[baseUrl]/[group]/[search]/[explore]/[headers]/[rateLimit]）是给
 * 引擎与 app 层的兼容门面：它们从原生字段派生，让上层无需了解 Legado 字段名。
 */
@Serializable
data class BookSourceRule(
    val bookSourceUrl: String,
    val bookSourceName: String = "",
    val bookSourceGroup: String = "",
    /** 0=文本(小说) 1=音频 2=图片(漫画) 3=文件；本应用只支持 0 */
    val bookSourceType: Int = 0,
    val bookSourceComment: String = "",
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    /** 源级请求头：JSON 字符串（`@js:` 形式暂不支持，忽略） */
    val header: String? = null,
    /** 并发限制："500"(毫秒间隔) 或 "1/1000"(次/毫秒) */
    val concurrentRate: String? = null,
    val searchUrl: String? = null,
    val exploreUrl: String? = null,
    val ruleSearch: SearchRuleSet? = null,
    val ruleExplore: SearchRuleSet? = null,
    val ruleBookInfo: BookInfoRuleSet? = null,
    val ruleToc: TocRuleSet? = null,
    val ruleContent: ContentRuleSet? = null,
) {
    /** 书源唯一标识 = 站点地址（含 Legado 的 `#后缀`，同站多源靠它区分） */
    val id: String get() = bookSourceUrl

    val name: String get() = bookSourceName.ifBlank { bookSourceUrl }

    /**
     * 相对链接解析基准。剥掉 Legado 的 `#后缀`（常含 emoji，用于区分同站重复书源），
     * 它不是真实地址；留着会污染相对链接、还会随 `Referer:{{baseUrl}}` 发出去被 OkHttp 拒收。
     */
    val baseUrl: String get() = bookSourceUrl.substringBefore('#').trimEnd('/')

    val group: String get() = bookSourceGroup

    /** Legado 无源级编码字段；编码来自 searchUrl 的 `,{charset}` 选项或响应头 */
    val charset: String? get() = null

    val headers: Map<String, String> get() = parseHeaderJson(header)

    val rateLimit: RateLimitRule? get() = parseConcurrentRate(concurrentRate)

    /** 具备可用搜索能力时非空（有 searchUrl 且有列表规则），供上层 `search != null` 判断 */
    val search: SearchRuleSet? get() = ruleSearch?.takeIf { !searchUrl.isNullOrBlank() }

    /** 发现页目录，从 exploreUrl 解析（`名称::地址` 多行 / JSON 数组）；动态(JS)地址得空列表 */
    val explore: List<ExploreItem> get() = parseExplore(exploreUrl, enabledExplore)

    fun toJson(): String = BookSourceJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): BookSourceRule =
            BookSourceJson.decodeFromString(serializer(), text)
    }
}

/** 搜索/发现的列表与字段规则（Legado ruleSearch/ruleExplore） */
@Serializable
data class SearchRuleSet(
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val kind: String? = null,
    val wordCount: String? = null,
    val lastChapter: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val bookUrl: String? = null,
)

/** 详情页规则（Legado ruleBookInfo）；[init] 预处理暂不支持 */
@Serializable
data class BookInfoRuleSet(
    val init: String? = null,
    val name: String? = null,
    val author: String? = null,
    val kind: String? = null,
    val wordCount: String? = null,
    val lastChapter: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val tocUrl: String? = null,
)

/** 目录规则（Legado ruleToc） */
@Serializable
data class TocRuleSet(
    val chapterList: String? = null,
    val chapterName: String? = null,
    val chapterUrl: String? = null,
    val isVolume: String? = null,
    val updateTime: String? = null,
    val nextTocUrl: String? = null,
)

/** 正文规则（Legado ruleContent） */
@Serializable
data class ContentRuleSet(
    val content: String? = null,
    val nextContentUrl: String? = null,
    val webJs: String? = null,
    val sourceRegex: String? = null,
    /** `##正则##替换` 净化，可能多行；与用户全局净化合并 */
    val replaceRegex: String? = null,
    val imageStyle: String? = null,
)

/** 令牌桶限速：每 intervalMs 补一个令牌，桶容量 burst */
@Serializable
data class RateLimitRule(
    val intervalMs: Long = 0,
    val burst: Int = 1,
)

/** 净化规则：对段落文本做替换，替换后全空的段落丢弃 */
@Serializable
data class PurifyRule(
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
)

/** 发现页一项：显示名 + 地址（可含 {{page}}） */
data class ExploreItem(val name: String, val url: String)

// ---- 原生字段解析（门面访问器用） ----

private val headerJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseHeaderJson(raw: String?): Map<String, String> {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty() || t.startsWith("@js", ignoreCase = true) || t.contains("<js>", ignoreCase = true)) {
        return emptyMap()
    }
    return runCatching {
        headerJson.parseToJsonElement(t).jsonObject.entries.associate { (k, v) ->
            k to ((v as? JsonPrimitive)?.content ?: v.toString())
        }
    }.getOrDefault(emptyMap())
}

/** concurrentRate: "500"(毫秒间隔) 或 "1/1000"(次数/毫秒) */
internal fun parseConcurrentRate(raw: String?): RateLimitRule? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return null
    t.toLongOrNull()?.let { return if (it > 0) RateLimitRule(intervalMs = it) else null }
    val m = Regex("^(\\d+)/(\\d+)$").find(t) ?: return null
    val (count, ms) = m.destructured
    val interval = ms.toLong() / count.toLong().coerceAtLeast(1)
    return if (interval > 0) RateLimitRule(intervalMs = interval) else null
}

/**
 * 解析 exploreUrl 为发现页列表。支持 Legado 两种写法：
 * - 多行 `名称::地址`
 * - JSON 数组 `[{"title":"…","url":"…"}, …]`
 * 动态地址（`<js>`/`@js:`/整串是表达式）无法静态列举，返回空。
 */
internal fun parseExplore(raw: String?, enabled: Boolean): List<ExploreItem> {
    if (!enabled) return emptyList()
    val t = raw?.trim().orEmpty()
    if (t.isEmpty() || t.startsWith("@js", ignoreCase = true) || t.contains("<js>", ignoreCase = true)) {
        return emptyList()
    }
    if (t.startsWith("[")) {
        val arr = runCatching { headerJson.parseToJsonElement(t) }.getOrNull()
        val list = (arr as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val url = (o["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (name.isEmpty() || url.isEmpty()) null else ExploreItem(name, url)
        }
    }
    return t.lines().mapNotNull { line ->
        val s = line.trim().trimEnd('&')
        if (s.isEmpty()) return@mapNotNull null
        val sep = s.indexOf("::")
        if (sep <= 0) return@mapNotNull null
        ExploreItem(s.substring(0, sep).trim(), s.substring(sep + 2).trim())
    }
}
