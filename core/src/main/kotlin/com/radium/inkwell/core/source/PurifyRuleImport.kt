package com.radium.inkwell.core.source

import com.radium.inkwell.core.util.RepeatedTitle
import com.radium.inkwell.core.util.toHex
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 从文件 / 链接 / 剪贴板读进来的一条净化规则：阅读（Legado）新旧两版的字段、Inkwell 自己备份里的字段归一之后。
 *
 * @property id 入库用的 id。阅读的数字 id 记成 `legado:<id>`，Inkwell 自己的 id 原样；都没有就按内容算一个。
 *   必须是**确定性**的：同一份订阅导第二次要认得出是同一批规则（更新而不是再加一遍），
 *   两台设备各导一次、WebDAV 一合并（按 id 走）也不会变成两份。
 * @property unsupported 导不了的原因（直接显示在行上，说人话）；null = 能导
 */
data class ImportedPurifyRule(
    val id: String,
    val name: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean,
    val scope: String,
    val enabled: Boolean,
    val unsupported: String? = null,
)

/** 读不出规则：消息直接给用户看 */
class PurifyImportException(message: String) : Exception(message)

/**
 * 净化规则导入（阅读的「替换净化」导出、分享链接，Inkwell 的备份）。
 *
 * 阅读的规则有两版字段：
 * - 新版：`id`（数字）、`name`、`pattern`、`replacement`、`isRegex`（缺省真）、`scope`、`scopeTitle`、`scopeContent`、
 *   `isEnabled`（缺省真）、`group`、`excludeScope`、`timeoutMillisecond`、`order`；
 * - 旧版：`regex`、`replaceSummary`、`useTo`、`enable`、`serialNumber`，`isRegex` / `enable` 读不到当假（照阅读的 ReplaceAnalyzer）。
 *
 * Inkwell 没有的能力，不硬导：
 * - **只改章节名**的规则（`scopeContent = false`）：Inkwell 只净化正文，导进来会把正文也改了；
 * - 替换内容是**脚本**（`@js:`）的：净化不跑脚本，原样当文本替换上去只会写一段 JS 进正文；
 * - **正则写坏了**的：导进来也是一条永远不生效的规则。
 * 这几种照样列出来（[ImportedPurifyRule.unsupported]），让人知道少了哪几条、为什么。
 *
 * 分组（`group`）、排除范围（`excludeScope`）、超时（`timeoutMillisecond`）没有对应的字段，读的时候丢掉：
 * 超时由 [RegexWatchdog] 统一管；排除范围少见，丢掉的代价是规则管得比原来宽一点。
 */
object PurifyRuleImport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 读出所有规则（含导不了的，见 [ImportedPurifyRule.unsupported]），按文件里的顺序；同一个 id 出现两次取后一条。
     *
     * 认三种外形：一组规则（阅读导出的就是这个）、单条规则、带 `replaceRules` 数组的对象（Inkwell 的备份）。
     *
     * @throws PurifyImportException 不是 JSON，或者里面一条净化规则都没有（比如误选了来源文件）
     */
    fun parse(text: String): List<ImportedPurifyRule> {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) throw PurifyImportException("内容是空的")
        val root = try {
            json.parseToJsonElement(trimmed)
        } catch (_: Exception) {
            throw PurifyImportException("不是净化规则（读不出 JSON）")
        }
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["replaceRules"] ?: root["replaceRule"]) as? JsonArray ?: listOf(root)
            else -> emptyList()
        }
        val byId = LinkedHashMap<String, ImportedPurifyRule>()
        items.forEach { el ->
            val rule = (el as? JsonObject)?.let(::fromJson) ?: return@forEach
            byId[rule.id] = rule
        }
        if (byId.isEmpty()) throw PurifyImportException("里面没有净化规则")
        return byId.values.toList()
    }

    /**
     * 阅读的分享链接（`legado://import/replaceRule?src=…`、更早的 `yuedu://replace/importonline?src=…`）
     * 换成里面真正的规则地址；普通网址原样返回（去掉首尾空白）。
     */
    fun unwrapAppLink(url: String): String {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme != "legado" && scheme != "yuedu") return trimmed
        val src = trimmed.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("src=") }
            ?.removePrefix("src=")
            ?: return trimmed
        return runCatching { URLDecoder.decode(src, Charsets.UTF_8) }.getOrDefault(src).trim()
    }

    private fun fromJson(obj: JsonObject): ImportedPurifyRule? {
        // Inkwell 备份里的墓碑：删掉的规则别从备份里复活
        if (obj.bool("deleted") == true) return null
        val newPattern = obj.str("pattern").orEmpty()
        val legacy = newPattern.isEmpty()
        val pattern = newPattern.ifEmpty { obj.str("regex").orEmpty() }
        if (pattern.isEmpty()) return null
        val replacement = obj.str("replacement").orEmpty()
        val isRegex = obj.bool("isRegex") ?: !legacy
        val scope = (obj.str("scope") ?: obj.str("useTo")).orEmpty().trim()
        val enabled = obj.bool("isEnabled") ?: obj.bool("enabled") ?: obj.bool("enable") ?: !legacy
        val name = (obj.str("name") ?: obj.str("replaceSummary")).orEmpty().trim()
            .ifEmpty { pattern.take(NAME_FALLBACK_LENGTH) }
        val unsupported = when {
            obj.bool("scopeContent") == false ->
                if (obj.bool("scopeTitle") == true) "只改章节名的规则，暂不支持" else "这条规则没有作用于正文"
            replacement.startsWith("@js:", ignoreCase = true) || replacement.startsWith("<js>", ignoreCase = true) ->
                "替换内容是脚本，暂不支持"
            // Inkwell 备份里挂在某本书上的规则：换了设备，书的 id 对不上
            obj.str("bookId").orEmpty().isNotEmpty() -> "只对某一本书生效的规则"
            isRegex && runCatching { Regex(pattern) }.isFailure -> "正则写法有误"
            else -> null
        }
        return ImportedPurifyRule(
            id = idOf(obj, pattern, replacement, isRegex, scope),
            name = name,
            pattern = pattern,
            replacement = replacement,
            isRegex = isRegex,
            scope = scope,
            enabled = enabled,
            unsupported = unsupported,
        )
    }

    private fun idOf(obj: JsonObject, pattern: String, replacement: String, isRegex: Boolean, scope: String): String {
        val raw = obj.str("id")?.trim().orEmpty()
        return when {
            // 阅读的 id 是数字（建规则那一刻的时间戳）；0 是没填
            raw.toLongOrNull()?.let { it != 0L } == true -> "legado:$raw"
            raw.isNotEmpty() && raw.toLongOrNull() == null -> raw
            else -> {
                val digest = MessageDigest.getInstance("SHA-1")
                    .digest("$isRegex\u0000$pattern\u0000$replacement\u0000$scope".toByteArray())
                "legado:h" + digest.toHex().take(HASH_ID_LENGTH)
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    /** 没名字的规则拿内容开头当名字，同手建规则（ReplaceRuleViewModel.save） */
    private const val NAME_FALLBACK_LENGTH = 20
    private const val HASH_ID_LENGTH = 16
}

/**
 * 净化规则的作用范围。空 = 处处生效；否则是逗号或分号（全半角都行）分隔的几段，命中一段就生效：
 * - 来源的地址（id）或名字**包含**这一段 —— 「笔趣」管住所有叫笔趣什么的源；
 * - 或者这一段就是**书名**（不计空白、标点、书名号）—— 阅读的规则常这么写，只管某一本书。
 *
 * 本地书没有来源，只认书名那一条。
 */
object PurifyScope {

    fun parts(scope: String): List<String> =
        scope.split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }

    fun matches(scope: String, sourceId: String?, sourceName: String?, bookTitle: String): Boolean {
        val parts = parts(scope)
        if (parts.isEmpty()) return true
        val book = RepeatedTitle.key(bookTitle)
        return parts.any { p ->
            sourceId?.contains(p, ignoreCase = true) == true ||
                sourceName?.contains(p, ignoreCase = true) == true ||
                (book.isNotEmpty() && RepeatedTitle.key(p) == book)
        }
    }
}
