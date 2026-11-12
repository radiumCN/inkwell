package com.radium.inkwell.core.source

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * 要导出的一条净化规则：库里那一行去掉 Inkwell 自己的簿记字段（排序、时间戳、墓碑）。
 *
 * @property id Inkwell 的 id：从阅读导进来的是 `legado:<数字>`，手建的是 UUID，预置的是 `seed:<名字>`
 * @property scope 作用范围。「本书专属」的规则由调用方换成那本书的书名再交进来（见 [PurifyRuleExport]）
 */
data class ExportPurifyRule(
    val id: String,
    val name: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean,
    val scope: String,
    val enabled: Boolean,
)

/**
 * 净化规则导出成阅读（Legado）新版的格式：阅读的「替换净化 → 导入」、Inkwell 的「从文件导入」（[PurifyRuleImport]）都认。
 *
 * - **id 必须是数字**：阅读把 id 当 Long 读，一个 UUID 会让整份导入失败。从阅读导进来的规则（`legado:<数字>`）
 *   还原成原来那个数字，导回阅读是「更新」而不是多出一条；其余的按 Inkwell 的 id 算一个**确定**的数 ——
 *   同一条规则导两次是同一个 id，阅读那边认得出来。
 * - 「只改章节名」「排除范围」这些 Inkwell 没有的能力照阅读的缺省写：作用于正文、不作用于标题、不排除。
 * - 「本书专属」的规则在阅读里没有对应（阅读按书名 / 来源管作用范围，不挂在某一本书上），作用范围写成那本书的书名：
 *   阅读认书名，[PurifyScope] 也认书名，导到哪边都只管这一本书。
 */
object PurifyRuleExport {

    private val json = Json { prettyPrint = true }

    fun toJson(rules: List<ExportPurifyRule>): String {
        val array = buildJsonArray {
            rules.forEachIndexed { i, r ->
                addJsonObject {
                    put("id", legadoId(r.id))
                    put("name", r.name)
                    put("group", "")
                    put("pattern", r.pattern)
                    put("replacement", r.replacement)
                    put("scope", r.scope)
                    put("scopeTitle", false)
                    put("scopeContent", true)
                    put("excludeScope", "")
                    put("isEnabled", r.enabled)
                    put("isRegex", r.isRegex)
                    put("timeoutMillisecond", LEGADO_TIMEOUT_MS)
                    // 阅读按 order 排、按顺序替换：照列表里的先后写
                    put("order", i)
                }
            }
        }
        return json.encodeToString(JsonArray.serializer(), array)
    }

    /**
     * 阅读认的数字 id。`legado:<数字>` 还原；其余取 id 的 SHA-1 前 52 位再加一 ——
     * 正数（0 在阅读和 [PurifyRuleImport] 里都当「没填」），也留在 JS 的安全整数里，经网页工具转一手不走样。
     */
    internal fun legadoId(id: String): Long {
        if (id.startsWith(LEGADO_PREFIX)) {
            id.removePrefix(LEGADO_PREFIX).toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        var n = 0L
        for (i in 0 until 7) n = (n shl 8) or (digest[i].toLong() and 0xff)
        return (n and ID_MASK) + 1
    }

    private const val LEGADO_PREFIX = "legado:"
    private const val ID_MASK = (1L shl 52) - 1
    /** 阅读新建规则的缺省超时 */
    private const val LEGADO_TIMEOUT_MS = 3000
}
