package com.radium.inkwell.core.source.rss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 要导出的一个订阅源：库里那一行里阅读用得上的部分。
 *
 * @property sourceJson 从阅读导进来时的原文；直接粘 feed 地址订的是空串
 */
data class ExportRssSource(
    val url: String,
    val name: String,
    val icon: String,
    val group: String,
    val enabled: Boolean,
    val sourceJson: String,
)

/**
 * 订阅源导出成阅读（Legado）的订阅源 JSON：阅读的「订阅源 → 导入」、Inkwell 的导入（[LegadoRssConverter]）都认。
 *
 * - 从阅读导进来的：**原文照搬**（规则、脚本、登录、样式一个不丢 —— 转换器认不全的字段，原文里都还在），
 *   只把开关和分组换成现在的：这两样能在 Inkwell 里改，导出去应该是改过的样子。
 * - 直接粘地址订的：只有地址和名字，写一份最简的。阅读里没有文章规则（`ruleArticles`）的订阅源就按 RSS/Atom 解析，
 *   和 Inkwell 一样。
 */
object RssSourceExport {

    private val json = Json { prettyPrint = true }
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun toJson(sources: List<ExportRssSource>): String =
        json.encodeToString(JsonArray.serializer(), JsonArray(sources.map(::toLegado)))

    private fun toLegado(s: ExportRssSource): JsonObject {
        val original = s.sourceJson.takeIf { it.isNotBlank() }
            ?.let { runCatching { lenient.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            // 原文和这一行对不上（原文里的地址不是它）就不用：别把另一个源的规则安到这个地址上
            ?.takeIf { (it["sourceUrl"] as? JsonPrimitive)?.content?.trim() == s.url }
        val base = original ?: buildJsonObject {
            put("sourceUrl", s.url)
            put("sourceName", s.name)
            if (s.icon.isNotBlank()) put("sourceIcon", s.icon)
        }
        return JsonObject(
            base + mapOf(
                "enabled" to JsonPrimitive(s.enabled),
                "sourceGroup" to JsonPrimitive(s.group),
            ),
        )
    }
}
