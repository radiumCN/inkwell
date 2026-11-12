package com.radium.inkwell.core.source.rss

import com.radium.inkwell.core.source.LEGADO_ITEM_SEPARATOR
import com.radium.inkwell.core.source.headerScriptOf
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

    /**
     * v2：sortUrl 与 Legado 一样按 `&&` 拆分（v1 只按换行）。
     * v3：单网页源（singleUrl）不再跳过，转成 [RssSourceRule.singleUrl]。
     * v4：带上公共脚本库（jsLib）。
     * v5：header 写成脚本的带上脚本（[RssSourceRule.headerScript]），从前当没有请求头。
     * 存着的转换结果比这个旧、又留着原始 JSON 的，打开时按原始 JSON 重转一次（见 RssRepository.getRule）
     */
    const val VERSION = 5

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

        val rule = RssSourceRule(
            id = url,
            name = src.str("sourceName")?.trim().orEmpty().ifBlank { url },
            icon = src.str("sourceIcon")?.trim().orEmpty(),
            group = src.str("sourceGroup")?.trim().orEmpty(),
            enabled = src.bool("enabled") ?: true,
            headers = parseHeaders(src.str("header")),
            headerScript = headerScriptOf(src.str("header")),
            sorts = parseSorts(src.str("sortUrl")),
            articles = src.str("ruleArticles"),
            nextPage = src.str("ruleNextPage"),
            title = src.str("ruleTitle"),
            description = src.str("ruleDescription"),
            image = src.str("ruleImage"),
            link = src.str("ruleLink"),
            pubDate = src.str("rulePubDate"),
            content = src.str("ruleContent"),
            // 单网页源：没有文章列表，点开就是用应用内网页打开（同阅读）。从前整条跳过，源仓库的合集导进来少一大半
            singleUrl = src.bool("singleUrl") == true,
            enableJs = src.bool("enableJs") ?: true,
            jsLib = src.str("jsLib"),
        )
        // 地址要跑脚本才算得出来的单网页源（`http@js:…`、分类地址是 `@js:`）：列进来也是一个永远打不开的网页
        if (rule.singleUrl && rule.pageUrl() == null) {
            throw IllegalArgumentException("单网页订阅源的地址要跑脚本才算得出来，暂不支持")
        }
        return rule
    }

    /**
     * legado 的 sortUrl：每项一个「分类名::地址」，项之间用换行**或 `&&`** 分隔（Legado 按 `(&&|\n)+` 拆）；
     * 没有 `::` 的整项当地址。从前只按换行拆，写成一行 `a::url1&&b::url2` 的源只剩一个分类、
     * 地址还是一长串拼接起来的错地址。
     */
    private fun parseSorts(raw: String?): List<RssSort> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(LEGADO_ITEM_SEPARATOR).mapNotNull { line ->
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
