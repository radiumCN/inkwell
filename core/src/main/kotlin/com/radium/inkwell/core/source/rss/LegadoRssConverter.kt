package com.radium.inkwell.core.source.rss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Legado 订阅源（rssSource）→ 我们的订阅源。
 *
 * 与书源转换器同样的取舍：规则原样带过来（我们的求值器就认 legado 语义），
 * 不做重写；转不了的整条跳过，并说清为什么。
 */
object LegadoRssConverter {

    const val VERSION = 1

    data class Converted(val rule: RssSourceRule, val sourceJson: String)
    data class Skipped(val name: String, val url: String, val reason: String)
    data class Result(val converted: List<Converted>, val skipped: List<Skipped>)

    /** 粗判是否 legado 订阅源（我们自己的格式没有 sourceUrl 这个键） */
    fun looksLikeLegadoRss(text: String): Boolean =
        text.contains("\"sourceUrl\"") && !text.contains("\"bookSourceUrl\"")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun convert(text: String): Result {
        val root = json.parseToJsonElement(text.trim())
        val items = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        val converted = mutableListOf<Converted>()
        val skipped = mutableListOf<Skipped>()
        items.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val name = obj.str("sourceName").orEmpty()
            val url = obj.str("sourceUrl").orEmpty()
            try {
                converted += Converted(convertOne(obj), sourceJson = obj.toString())
            } catch (e: Exception) {
                skipped += Skipped(name, url, e.message ?: "转换失败")
            }
        }
        return Result(converted, skipped)
    }

    private fun convertOne(src: JsonObject): RssSourceRule {
        val url = src.str("sourceUrl")?.trim()
            ?: throw IllegalArgumentException("缺少 sourceUrl")
        require(url.isNotBlank()) { "sourceUrl 为空" }

        // singleUrl 的源只是"用内置浏览器打开一个网页"，没有任何可解析的东西。
        // 硬转进来只会在订阅列表里躺一个永远加载失败的条目 —— 不如说清楚跳过。
        if (src.bool("singleUrl") == true) {
            throw IllegalArgumentException("单网页订阅源（singleUrl），无文章列表可解析")
        }

        return RssSourceRule(
            id = url,
            name = src.str("sourceName")?.trim().orEmpty().ifBlank { url },
            icon = src.str("sourceIcon")?.trim().orEmpty(),
            group = src.str("sourceGroup")?.trim().orEmpty(),
            enabled = src.bool("enabled") ?: true,
            headers = parseHeaders(src.str("header")),
            sorts = parseSorts(src.str("sortUrl")),
            articles = src.str("ruleArticles"),
            nextPage = src.str("ruleNextPage"),
            title = src.str("ruleTitle"),
            description = src.str("ruleDescription"),
            image = src.str("ruleImage"),
            link = src.str("ruleLink"),
            pubDate = src.str("rulePubDate"),
            content = src.str("ruleContent"),
        )
    }

    /** legado 的 sortUrl：每行一个「分类名::地址」；没有 `::` 的整行当地址 */
    private fun parseSorts(raw: String?): List<RssSort> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val idx = t.indexOf("::")
            if (idx > 0) RssSort(t.substring(0, idx).trim(), t.substring(idx + 2).trim())
            else RssSort(t, t)
        }.filter { it.url.isNotBlank() }
    }

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject
                .mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
                .filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
