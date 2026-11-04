package com.radium.inkwell.core.source.detect

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.nio.charset.Charset

/** 网页里提交的一个表单字段 */
data class FormField(val name: String, val value: String, val type: String = "text")

/**
 * 用户在网页里搜的那一次 → Legado `searchUrl` 模板（关键词换成 `{{key}}`）。
 *
 * 两个来源，表单优先：
 * - **表单提交**（[fromForm]）：字段名、隐藏字段、方法、编码一样不缺，GET 拼查询串、POST 拼 body。
 *   不少站的搜索是 POST（`/modules/article/search.php`），光看跳转后的地址根本看不出搜了什么。
 * - **只有跳转后的地址**（[fromUrl]）：脚本拼地址跳过去的站没有表单可抓，只能在地址里找关键词。
 *   关键词按 UTF-8、GBK 两种百分号编码都找一遍 —— 老站多是 GBK，找到 GBK 那种就要带上
 *   `"charset":"gbk"`，否则引擎按 UTF-8 发出去，搜出来的是乱码词。
 *
 * 生成的选项块走 JSON 序列化而不是手拼：表单里的隐藏字段值什么字符都可能有，手拼的引号、反斜杠一错，
 * 引擎就把整串当 GET 地址发出去。
 */
object SearchTemplates {

    /**
     * @param keyword 用户搜的词；已知时按它认关键词字段，未知（null）就取第一个有值的文本框
     * @return 认不出关键词字段（全是隐藏字段、文本框全空）或地址不合法时返回 null
     */
    fun fromForm(action: String, method: String, charset: String?, fields: List<FormField>, keyword: String? = null): String? {
        val url = action.toHttpUrlOrNull() ?: return null
        val keyAt = fields.indexOfFirst { !keyword.isNullOrBlank() && it.value == keyword }.takeIf { it >= 0 }
            ?: fields.indexOfFirst(::isKeywordBox).takeIf { it >= 0 }
            ?: return null
        val cs = legadoCharset(charset)
        val encoding = cs?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val query = fields.mapIndexed { i, f ->
            val value = if (i == keyAt) KEY else formEncode(f.value, encoding)
            "${formEncode(f.name, encoding)}=$value"
        }.joinToString("&")
        val post = method.equals("post", ignoreCase = true)
        // GET 表单提交时，action 自带的查询串会被表单数据整个换掉（HTML 规范如此），所以这里也丢掉它
        val base = if (post) url.toString() else url.newBuilder().query(null).build().toString()
        val head = if (post) base else "$base?$query"
        if (!post && cs == null) return head
        val options = buildJsonObject {
            if (post) {
                put("method", "POST")
                put("body", query)
            }
            if (cs != null) put("charset", cs)
        }
        return "$head,$options"
    }

    /** 表单里用户填的搜索词：第一个有值的文本框（与 [fromForm] 不给 keyword 时认的是同一个） */
    fun formKeyword(fields: List<FormField>): String? = fields.firstOrNull(::isKeywordBox)?.value

    /**
     * [url] 是不是 [siteUrl] 那个站的（同主域，`m.` 与 `www.` 算一个）。网页里的脚本桥对页面上所有 frame 开放，
     * 广告 iframe 里的表单提交也会报上来 —— 不是这个站的一律不认。
     */
    fun belongsTo(url: String, siteUrl: String): Boolean {
        val a = SiteLinks.hostOf(url) ?: return false
        val b = SiteLinks.hostOf(siteUrl) ?: return false
        return SiteLinks.sameSite(a, b)
    }

    private fun isKeywordBox(f: FormField) = f.type.lowercase() in TEXT_TYPES && f.value.isNotBlank()

    /** 在跳转后的地址里找关键词，换成 `{{key}}`；找不到返回 null */
    fun fromUrl(url: String, keyword: String): String? {
        if (keyword.isBlank() || url.toHttpUrlOrNull() == null) return null
        // UTF-8 在前：纯 ASCII 关键词两种编码一样，不该无端给它挂上 gbk
        val forms = listOf(
            keyword to null,
            formEncode(keyword, Charsets.UTF_8) to null,
            percentEncode(keyword, Charsets.UTF_8) to null,
            formEncode(keyword, GBK) to "gbk",
            percentEncode(keyword, GBK) to "gbk",
        )
        for ((encoded, cs) in forms) {
            for (variant in listOf(encoded, encoded.lowercase())) {
                val at = url.indexOf(variant)
                if (at < 0) continue
                val template = url.substring(0, at) + KEY + url.substring(at + variant.length)
                return if (cs == null) template else "$template,${buildJsonObject { put("charset", cs) }}"
            }
        }
        return null
    }

    /**
     * 表单 / 页面报的编码 → 书源里的 charset 选项。UTF-8 不写（引擎默认）；GB 系一律写 gbk
     * （GB2312 是 GBK 的子集，GB18030 站收 GBK 编码的常用字也没问题）。
     */
    internal fun legadoCharset(raw: String?): String? {
        val cs = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            cs == "utf-8" || cs == "utf8" -> null
            cs in GB_FAMILY -> "gbk"
            cs == "big5" -> "big5"
            // windows-1252 / iso-8859-1 之类：页面没声明编码时浏览器的猜测，对中文站没有意义
            else -> null
        }
    }

    private fun formEncode(s: String, cs: Charset) = URLEncoder.encode(s, cs)

    /** 空格编成 %20 而不是 +：路径里的关键词（`/search/剑 来/`）要这种 */
    private fun percentEncode(s: String, cs: Charset) = URLEncoder.encode(s, cs).replace("+", "%20")

    private const val KEY = "{{key}}"
    private val TEXT_TYPES = setOf("text", "search", "textarea", "")
    private val GB_FAMILY = setOf("gbk", "gb2312", "gb18030", "x-gbk")
    private val GBK: Charset = Charset.forName("GBK")
}
