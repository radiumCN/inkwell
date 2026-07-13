package com.radium.inkwell.core.source.legado

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.ContentRule
import com.radium.inkwell.core.source.DetailRule
import com.radium.inkwell.core.source.ExploreRule
import com.radium.inkwell.core.source.PurifyRule
import com.radium.inkwell.core.source.RateLimitRule
import com.radium.inkwell.core.source.RequestRule
import com.radium.inkwell.core.source.RuleParser
import com.radium.inkwell.core.source.SearchRule
import com.radium.inkwell.core.source.TocRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Legado（开源阅读）书源 → Inkwell 书源规则转换器。
 *
 * 覆盖常用规则子集：默认 Jsoup 层级（class./id./tag./children）、@css:、JSONPath（$.）、
 * :正则、##正则净化、||/&&、POST searchUrl 选项、exploreUrl、replaceRegex、concurrentRate。
 * JS（<js>/@js:/{{}}）与 @XPath 规则无法转换：关键规则命中则跳过该源，次要规则命中则丢弃并记警告。
 */
object LegadoConverter {

    data class Converted(val rule: BookSourceRule, val warnings: List<String>)
    data class Skipped(val name: String, val url: String, val reason: String)
    data class Result(val converted: List<Converted>, val skipped: List<Skipped>)

    /** 粗判是否 Legado 格式（我们自己的格式用 baseUrl/schemaVersion） */
    fun looksLikeLegado(text: String): Boolean =
        text.contains("\"bookSourceUrl\"")

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
            val name = obj.str("bookSourceName") ?: "未命名"
            val url = obj.str("bookSourceUrl") ?: ""
            try {
                converted += convertOne(obj)
            } catch (e: LegadoUnsupported) {
                skipped += Skipped(name, url, e.message ?: "不支持")
            } catch (e: Exception) {
                skipped += Skipped(name, url, "解析失败: ${e.message?.take(80)}")
            }
        }
        return Result(converted, skipped)
    }

    private class LegadoUnsupported(message: String) : Exception(message)

    // ---------- 单个书源 ----------

    private fun convertOne(src: JsonObject): Converted {
        val warnings = mutableListOf<String>()
        val baseUrl = src.str("bookSourceUrl")?.trimEnd('/')
            ?: throw LegadoUnsupported("缺少 bookSourceUrl")
        val name = src.str("bookSourceName") ?: baseUrl
        val type = src.int("bookSourceType") ?: 0
        if (type != 0) throw LegadoUnsupported("非文字书源（type=$type）不支持")

        val ruleSearch = src.obj("ruleSearch")
        val searchUrlRaw = src.str("searchUrl")?.takeIf { it.isNotBlank() }

        val headers = parseHeader(src.str("header"), warnings)
        val sourceCharset = searchUrlRaw?.let { detectCharsetHint(it) }
        val ctx = Ctx(warnings)

        // 搜索（可缺省/转换失败：还有发现页时降级保留书源）
        var searchError: String? = null
        val search: SearchRule? = if (ruleSearch != null && searchUrlRaw != null) {
            try {
                buildSearchRule(ruleSearch, searchUrlRaw, ctx)
            } catch (e: LegadoUnsupported) {
                searchError = e.message
                null
            }
        } else null

        // 详情
        val ruleBookInfo = src.obj("ruleBookInfo")
        if (!ruleBookInfo?.str("init").isNullOrBlank()) {
            warnings += "ruleBookInfo.init 不支持，已忽略"
        }
        val detailFields = buildMap {
            putRule("title", ruleBookInfo?.str("name"), Kind.TEXT, "detail.title", ctx)
            putRule("author", ruleBookInfo?.str("author"), Kind.TEXT, "detail.author", ctx)
            putRule("coverUrl", ruleBookInfo?.str("coverUrl"), Kind.URL, "detail.coverUrl", ctx)
            putRule("intro", ruleBookInfo?.str("intro"), Kind.TEXT, "detail.intro", ctx)
            putRule("tocUrl", ruleBookInfo?.str("tocUrl"), Kind.URL, "detail.tocUrl", ctx)
        }

        // 目录
        val ruleToc = src.obj("ruleToc") ?: throw LegadoUnsupported("缺少 ruleToc")
        var chapterListRaw = ruleToc.str("chapterList")
            ?: throw LegadoUnsupported("缺少目录列表规则")
        var reverse = false
        if (chapterListRaw.startsWith("-")) {
            reverse = true
            chapterListRaw = chapterListRaw.substring(1)
        }
        val tocList = convertRule(chapterListRaw, Kind.LIST, "toc.list", ctx)
            ?: throw LegadoUnsupported("目录列表规则无法转换: ${ctx.lastError ?: chapterListRaw}")
        val tocFields = buildMap {
            putRule("title", ruleToc.str("chapterName"), Kind.TEXT, "toc.title", ctx, required = true)
            putRule("url", ruleToc.str("chapterUrl"), Kind.URL, "toc.url", ctx, required = true)
        }
        val tocNext = convertRule(ruleToc.str("nextTocUrl"), Kind.URL, "toc.nextPage", ctx)

        // 正文
        val ruleContent = src.obj("ruleContent") ?: throw LegadoUnsupported("缺少 ruleContent")
        val contentBase = ruleContent.str("content")
            ?: throw LegadoUnsupported("缺少正文规则")
        // 正文规则的 ## 替换下沉到 purify（绕开 DSL 内嵌正则的转义限制）
        val (contentRuleRaw, contentPurify) = splitHashReplace(contentBase)
        val contentRule = convertRule(forceHtmlExtractor(contentRuleRaw), Kind.HTML, "content", ctx)
            ?: throw LegadoUnsupported("正文规则无法转换: ${ctx.lastError ?: contentBase}")
        val contentNext = convertRule(ruleContent.str("nextContentUrl"), Kind.URL, "content.nextPage", ctx)
        // 净化正则在导入侧（即目标设备的正则引擎上）预编译校验，非法的丢弃而非整源报废
        val purify = buildList {
            contentPurify?.let { add(it) }
            addAll(parseReplaceRegex(ruleContent.str("replaceRegex"), warnings))
        }.filter { rule ->
            !rule.isRegex || runCatching { java.util.regex.Pattern.compile(rule.pattern) }.isSuccess.also { ok ->
                if (!ok) warnings += "净化正则不合法已丢弃：${rule.pattern.take(40)}"
            }
        }

        // 发现页
        val explore = convertExplore(src, ctx)

        if (search == null && explore.isEmpty()) {
            throw LegadoUnsupported(
                searchError?.let { "搜索规则无法转换（$it）且无发现规则" }
                    ?: "缺少搜索与发现规则"
            )
        }
        if (search == null && searchError != null) {
            warnings += "搜索规则无法转换（$searchError），仅保留发现页"
        }
        if (tocFields["title"] == null || tocFields["url"] == null) {
            throw LegadoUnsupported("目录章节规则无法转换")
        }

        val rule = BookSourceRule(
            schemaVersion = 1,
            id = baseUrl.removePrefix("https://").removePrefix("http://").trim('/'),
            name = name,
            baseUrl = baseUrl,
            version = 1,
            comment = buildString {
                append("由 Legado 书源转换")
                src.str("bookSourceComment")?.takeIf { it.isNotBlank() }?.let { append("；", it.take(100)) }
            },
            enabled = src.bool("enabled") ?: true,
            charset = sourceCharset,
            headers = headers,
            rateLimit = parseConcurrentRate(src.str("concurrentRate")),
            search = search,
            detail = DetailRule(fields = detailFields),
            toc = TocRule(list = tocList, fields = tocFields, nextPage = tocNext, reverse = reverse),
            content = ContentRule(content = contentRule, nextPage = contentNext, purify = purify),
            explore = explore,
        )
        return Converted(rule, warnings.toList())
    }

    /** 搜索规则整体构建；必填字段无法转换时抛 LegadoUnsupported */
    private fun buildSearchRule(ruleSearch: JsonObject, searchUrlRaw: String, ctx: Ctx): SearchRule {
        val request = convertSearchUrl(searchUrlRaw)
        val list = convertRule(ruleSearch.str("bookList"), Kind.LIST, "search.list", ctx)
            ?: throw LegadoUnsupported("搜索列表规则无法转换: ${ctx.lastError ?: ruleSearch.str("bookList")}")
        val fields = buildMap {
            putRule("title", ruleSearch.str("name"), Kind.TEXT, "search.title", ctx, required = true)
            putRule("bookUrl", ruleSearch.str("bookUrl"), Kind.URL, "search.bookUrl", ctx, required = true)
            putRule("author", ruleSearch.str("author"), Kind.TEXT, "search.author", ctx)
            putRule("coverUrl", ruleSearch.str("coverUrl"), Kind.URL, "search.coverUrl", ctx)
            putRule("intro", ruleSearch.str("intro"), Kind.TEXT, "search.intro", ctx)
            putRule("latestChapter", ruleSearch.str("lastChapter"), Kind.TEXT, "search.lastChapter", ctx)
        }
        if (fields["title"] == null) throw LegadoUnsupported("搜索书名规则无法转换")
        if (fields["bookUrl"] == null) throw LegadoUnsupported("搜索书籍链接规则无法转换")
        return SearchRule(request = request, list = list, fields = fields, nextPage = null)
    }

    // ---------- 规则字符串转换 ----------

    private enum class Kind { LIST, TEXT, URL, HTML }

    private class Ctx(val warnings: MutableList<String>) {
        var lastError: String? = null
    }

    private fun MutableMap<String, String>.putRule(
        key: String,
        raw: String?,
        kind: Kind,
        where: String,
        ctx: Ctx,
        required: Boolean = false,
    ) {
        val converted = convertRule(raw, kind, where, ctx)
        if (converted != null) {
            put(key, converted)
        } else if (required && !raw.isNullOrBlank()) {
            // 交给调用方按缺失字段判定
        }
    }

    /** 单条 Legado 规则 → 我们的 DSL；不可转换返回 null 并记录原因 */
    private fun convertRule(raw: String?, kind: Kind, where: String, ctx: Ctx): String? {
        val rule = raw?.trim().orEmpty()
        if (rule.isEmpty()) return null
        try {
            // ## 净化后缀（正文的已在上游剥离并下沉 purify）
            val (base, hashPipe) = splitHashToPipe(rule, where, ctx)

            val orParts = splitTop(base, "||").map { part ->
                convertAlternative(part.trim(), kind, where)
            }
            var dsl = orParts.joinToString(" || ")
            if (hashPipe != null) dsl += hashPipe
            // 用引擎解析器验证转换产物，非法则报不可转换
            RuleParser.parse(dsl)
            return dsl
        } catch (e: Exception) {
            ctx.lastError = e.message
            ctx.warnings += "$where: 规则无法转换（${e.message?.take(60)}），已忽略「${rule.take(60)}」"
            return null
        }
    }

    private fun convertAlternative(rule: String, kind: Kind, where: String): String {
        // && / %% 拼接（%% 轮询近似为拼接）
        val andParts = splitTop(rule.replace("%%", "&&"), "&&")
        if (andParts.size > 1) {
            return andParts.joinToString(" && ") { convertAtomic(it.trim(), kind, where) }
        }
        return convertAtomic(rule, kind, where)
    }

    /** JS 里引用了这些对象的脚本依赖 Legado 的 java 桥/上下文，我们不提供 → 保持跳过 */
    private val UNSUPPORTED_JS_REFS = Regex("\\b(java|cookie|source|book|chapter|cache)\\s*\\.")

    private fun convertAtomic(rule: String, kind: Kind, where: String): String {
        if (rule.contains("{{")) {
            throw LegadoUnsupported("规则内嵌 {{js}} 不支持")
        }
        if (rule.startsWith("@XPath:", ignoreCase = true) || rule.startsWith("//")) {
            throw LegadoUnsupported("XPath 规则不支持")
        }
        // <js>...</js> 块：前段为基础规则（可为空），脚本转为 js 管道
        if (rule.contains("<js>")) {
            val jsStart = rule.indexOf("<js>")
            val jsEnd = rule.indexOf("</js>", jsStart)
            if (jsEnd < 0) throw LegadoUnsupported("JS 块未闭合")
            val script = rule.substring(jsStart + 4, jsEnd).trim()
            val base = rule.substring(0, jsStart).trim().trimEnd('@')
            val tail = rule.substring(jsEnd + 5).trim()
            if (tail.isNotEmpty()) throw LegadoUnsupported("JS 块后还有规则，暂不支持")
            return attachJs(base, script, kind, where)
        }
        // rule@js:script 后缀（八一中文式）；整条 @js: 开头 = 纯脚本
        val jsSuffix = rule.indexOf("@js:")
        if (jsSuffix > 0) {
            return attachJs(rule.substring(0, jsSuffix), rule.substring(jsSuffix + 4), kind, where)
        }
        if (rule.startsWith("@js:")) {
            return attachJs("", rule.substring(4), kind, where)
        }
        return when {
            rule.startsWith("@css:", ignoreCase = true) ->
                convertCssRule(rule.substring(5), kind)
            rule.startsWith("@json:", ignoreCase = true) ->
                "json:" + rule.substring(6).trim()
            rule.startsWith("$.") || rule.startsWith("$[") ->
                "json:$rule"
            rule.startsWith(":") ->
                "regex:" + escapeForDsl(rule.substring(1))
            else -> convertJsoupHierarchy(rule, kind)
        }
    }

    /** 基础规则 + JS：base 为空时脚本作为原子规则，否则作为管道；base64 绕开 DSL 切分 */
    private fun attachJs(base: String, script: String, kind: Kind, where: String): String {
        if (UNSUPPORTED_JS_REFS.containsMatchIn(script)) {
            throw LegadoUnsupported("JS 使用了不支持的对象（java/book/cookie 等）")
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        return if (base.isEmpty()) {
            "js:b64:$encoded"
        } else {
            convertAtomic(base, kind, where) + " | js:b64:$encoded"
        }
    }

    /** @css:selector@extractor → css:selector@extractor */
    private fun convertCssRule(body: String, kind: Kind): String {
        val at = body.lastIndexOf('@')
        return if (at > 0) {
            val selector = body.substring(0, at).trim()
            val extractor = mapExtractor(body.substring(at + 1).trim())
            "css:$selector$extractor"
        } else {
            "css:${body.trim()}${defaultExtractor(kind)}"
        }
    }

    /**
     * 默认 Jsoup 层级：`class.foo.0@tag.a@href`。
     * 段间 @ 分隔为后代关系，末段可能是提取器；索引 0/-1/n → first/last/index 管道；
     * 中间段索引近似为 :eq(n) 并记为警告（常见的兄弟场景等价）。
     */
    private fun convertJsoupHierarchy(rule: String, kind: Kind): String {
        val segments = rule.split("@").filter { it.isNotBlank() }
        if (segments.isEmpty()) throw LegadoUnsupported("空规则")

        // 裸提取器（如 "text"/"href"）= 取当前节点自身
        if (segments.size == 1) {
            mapExtractorOrNull(segments[0].trim())?.let { return "css:$it" }
        }

        var extractor: String? = null
        var selectorSegs = segments
        if (segments.size > 1) {
            mapExtractorOrNull(segments.last().trim())?.let {
                extractor = it
                selectorSegs = segments.dropLast(1)
            }
        }

        val cssParts = mutableListOf<String>()
        var tailPipe = ""
        selectorSegs.forEachIndexed { i, seg ->
            val isLast = i == selectorSegs.lastIndex
            val (css, index) = convertSegment(seg.trim())
            if (index != null) {
                if (isLast) {
                    tailPipe = when {
                        index == 0 -> " | first"
                        index == -1 -> " | last"
                        else -> " | index:$index"
                    }
                    cssParts += css
                } else {
                    cssParts += css + midIndexSelector(css, index)
                }
            } else {
                cssParts += css
            }
        }
        val selector = cssParts.joinToString(" ")
        val ext = extractor ?: defaultExtractor(kind)
        return "css:$selector$ext$tailPipe"
    }

    /** 中间层索引的 CSS 近似：children 按子元素序号精确，其余按兄弟/同型序号 */
    private fun midIndexSelector(css: String, n: Int): String = when {
        css.endsWith("*") -> when (n) {
            0 -> ":first-child"
            -1 -> ":last-child"
            else -> ":nth-child(${n + 1})"
        }
        n == -1 -> ":last-of-type"
        else -> ":eq($n)"
    }

    /**
     * 单段：class.foo.0 / id.x / tag.a.-1 / text.xxx / children[0] / tr!0 / 原生CSS。
     * 返回 (css, 索引)；`!0` 转 :gt(0)，`[n]` 与 `.n` 同义。
     */
    private fun convertSegment(raw: String): Pair<String, Int?> {
        var seg = raw
        var excludeSuffix = ""
        Regex("\\.?!(-?\\d+)$").find(seg)?.let { m ->
            excludeSuffix = when (m.groupValues[1].toInt()) {
                0 -> ":gt(0)" // 排除第一个
                -1 -> ":not(:last-of-type)" // 排除最后一个
                else -> throw LegadoUnsupported("索引排除语法不支持: $raw")
            }
            seg = seg.removeRange(m.range)
        }
        if (seg.contains('!')) throw LegadoUnsupported("索引排除语法不支持: $raw")
        var bracketIdx: Int? = null
        Regex("\\[(-?\\d+)]$").find(seg)?.let { m ->
            bracketIdx = m.groupValues[1].toInt()
            seg = seg.removeRange(m.range)
        }
        if (seg.contains('[') && !seg.contains('=')) {
            // 属性选择器 [attr=x] 放行给原生 CSS；纯数字范围 [1:5] 不支持
            if (Regex("\\[[\\d:!,]+]").containsMatchIn(seg)) {
                throw LegadoUnsupported("索引范围语法不支持: $raw")
            }
        }

        val parts = seg.split(".")
        fun idxOf(s: String): Int? = s.toIntOrNull()
        var (css, dotIdx) = when {
            seg == "children" -> "> *" to null
            parts.size >= 2 && parts[0] == "class" -> {
                val idx = if (parts.size >= 3) idxOf(parts.last()) else null
                val nameParts = if (idx != null) parts.subList(1, parts.size - 1) else parts.drop(1)
                // Legado 里 class 名含空格 = 多 class 并列（.a.b）
                "." + nameParts.joinToString(".").replace(" ", ".") to idx
            }
            parts.size >= 2 && parts[0] == "id" -> {
                val idx = if (parts.size >= 3) idxOf(parts.last()) else null
                val nameParts = if (idx != null) parts.subList(1, parts.size - 1) else parts.drop(1)
                "#" + nameParts.joinToString(".") to idx
            }
            parts.size >= 2 && parts[0] == "tag" -> {
                val idx = if (parts.size >= 3) idxOf(parts.last()) else null
                val name = if (idx != null) parts[1] else parts.drop(1).joinToString(".")
                name to idx
            }
            parts.size >= 2 && parts[0] == "text" ->
                "*:containsOwn(${parts.drop(1).joinToString(".")})" to null
            else -> seg to null // 当作原生 CSS，最终由 RuleParser 校验
        }
        if (excludeSuffix.isNotEmpty()) {
            css += if (css.endsWith("*") && excludeSuffix == ":not(:last-of-type)") {
                ":not(:last-child)"
            } else excludeSuffix
        }
        return css to (bracketIdx ?: dotIdx)
    }

    private fun defaultExtractor(kind: Kind): String = when (kind) {
        Kind.LIST -> ""
        Kind.TEXT -> "@text"
        Kind.URL -> "@href"
        Kind.HTML -> "@html"
    }

    private fun mapExtractor(name: String): String =
        mapExtractorOrNull(name) ?: "@attr($name)"

    private fun mapExtractorOrNull(name: String): String? = when (name) {
        "text", "textNodes" -> "@text"
        "ownText" -> "@ownText"
        "html" -> "@html"
        "all" -> "@outerHtml"
        "href" -> "@href"
        "src" -> "@src"
        "content" -> "@attr(content)"
        "data-src", "data-original", "data-url", "title", "alt", "value" -> "@attr($name)"
        else -> null
    }

    // ---------- ## 净化 ----------

    /** `rule##pattern##replacement[###]` → (rule, " | regex:pattern replacement") */
    private fun splitHashToPipe(rule: String, where: String, ctx: Ctx): Pair<String, String?> {
        val idx = rule.indexOf("##")
        if (idx < 0) return rule to null
        val base = rule.substring(0, idx)
        val rest = rule.substring(idx + 2).removeSuffix("###")
        val sep = rest.indexOf("##")
        val pattern = if (sep < 0) rest else rest.substring(0, sep)
        val replacement = if (sep < 0) "" else rest.substring(sep + 2)
        if (pattern.isBlank()) return base to null
        if (pattern.contains('|') || replacement.contains('|') || pattern.contains("&&")) {
            ctx.warnings += "$where: ## 正则含 | 无法内嵌，已丢弃该净化「${pattern.take(40)}」"
            return base to null
        }
        return base to " | regex:${escapeForDsl(pattern)} $replacement"
    }

    /** 正文规则专用：## 替换转为 PurifyRule（purify 列表不经过 DSL，无转义限制） */
    private fun splitHashReplace(rule: String): Pair<String, PurifyRule?> {
        val idx = rule.indexOf("##")
        if (idx < 0) return rule to null
        val base = rule.substring(0, idx)
        val rest = rule.substring(idx + 2).removeSuffix("###")
        val sep = rest.indexOf("##")
        val pattern = if (sep < 0) rest else rest.substring(0, sep)
        val replacement = if (sep < 0) "" else rest.substring(sep + 2)
        if (pattern.isBlank()) return base to null
        return base to PurifyRule(pattern = pattern, replacement = replacement, isRegex = true)
    }

    /**
     * DSL 内嵌正则的转义：空格会被管道切分，改写为 unicode 转义（反斜杠+u0020）；
     * 反斜杠+空格在 Android ICU 正则引擎上是非法转义，不能用。
     */
    private fun escapeForDsl(pattern: String): String =
        pattern.replace(" ", "\\u0020")

    /** Legado replaceRegex: `##pattern##replacement`，可能多行 */
    private fun parseReplaceRegex(raw: String?, warnings: MutableList<String>): List<PurifyRule> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (text.contains("<js>") || text.contains("@js:")) {
            warnings += "replaceRegex 含 JS，已忽略"
            return emptyList()
        }
        return text.lines().mapNotNull { line ->
            val t = line.trim().removePrefix("##")
            if (t.isEmpty()) return@mapNotNull null
            val sep = t.indexOf("##")
            val pattern = if (sep < 0) t else t.substring(0, sep)
            val replacement = if (sep < 0) "" else t.substring(sep + 2).removeSuffix("###")
            if (pattern.isBlank()) null
            else PurifyRule(pattern = pattern, replacement = replacement, isRegex = true)
        }
    }

    // ---------- searchUrl / exploreUrl / header ----------

    /** `url,{"method":"POST","body":"...","charset":"gbk"}` → RequestRule */
    private fun convertSearchUrl(raw: String): RequestRule {
        var urlPart = raw.trim()
        var options: JsonObject? = null
        val optIdx = urlPart.indexOf(",{")
        if (optIdx > 0) {
            val optText = urlPart.substring(optIdx + 1)
            urlPart = urlPart.substring(0, optIdx)
            // Legado 选项常用单引号 JSON，标准解析失败时替换重试
            options = runCatching { json.parseToJsonElement(optText).jsonObject }.getOrNull()
                ?: runCatching {
                    json.parseToJsonElement(optText.replace('\'', '"')).jsonObject
                }.getOrNull()
        }
        val charset = options?.str("charset")?.lowercase()
        val keyword = if (charset != null && charset != "utf-8") "{{keyword|encode:$charset}}" else "{{keyword}}"

        fun convertVars(s: String): String {
            val out = s.replace("{{key}}", keyword)
                .replace("searchKey", keyword) // 老式变量
                .replace("searchPage", "{{page}}")
            // 允许 page 算术表达式（如 {{(page-1)*50}}，引擎模板支持求值）
            Regex("\\{\\{([^}]*)}}").findAll(out).forEach { m ->
                val body = m.groupValues[1].trim()
                val isKnown = body == "keyword" || body == "page" || body.startsWith("keyword|")
                val isPageMath = body.matches(Regex("[0-9page()+\\-*/\\s]+")) && body.contains("page")
                if (!isKnown && !isPageMath) {
                    throw LegadoUnsupported("searchUrl 含表达式: ${m.value}")
                }
            }
            return out
        }

        val body = options?.str("body")?.let { convertVars(it) }
        return RequestRule(
            url = convertVars(urlPart),
            method = options?.str("method")?.uppercase() ?: "GET",
            body = body,
            headers = options?.obj("headers")?.entries
                ?.associate { it.key to (it.value as? JsonPrimitive)?.content.orEmpty() }
                ?: emptyMap(),
            charset = charset,
        )
    }

    private fun convertExplore(src: JsonObject, ctx: Ctx): List<ExploreRule> {
        val exploreUrl = src.str("exploreUrl")?.trim().orEmpty()
        if (exploreUrl.isEmpty() || exploreUrl.contains("<js>") || exploreUrl.startsWith("@js")) return emptyList()
        val ruleExplore = src.obj("ruleExplore") ?: src.obj("ruleSearch") ?: return emptyList()

        val list = convertRule(ruleExplore.str("bookList"), Kind.LIST, "explore.list", ctx) ?: return emptyList()
        val fields = buildMap {
            putRule("title", ruleExplore.str("name"), Kind.TEXT, "explore.title", ctx)
            putRule("bookUrl", ruleExplore.str("bookUrl"), Kind.URL, "explore.bookUrl", ctx)
            putRule("author", ruleExplore.str("author"), Kind.TEXT, "explore.author", ctx)
            putRule("coverUrl", ruleExplore.str("coverUrl"), Kind.URL, "explore.coverUrl", ctx)
        }
        if (fields["title"] == null || fields["bookUrl"] == null) return emptyList()

        // 每行 "名称::url"，url 里 {{page}} 保留
        return exploreUrl.lines().mapNotNull { line ->
            val t = line.trim().trimEnd('&')
            if (t.isEmpty() || t.contains("{{") && !t.contains("{{page}}")) return@mapNotNull null
            val sep = t.indexOf("::")
            if (sep <= 0) return@mapNotNull null
            ExploreRule(
                name = t.substring(0, sep).trim(),
                url = t.substring(sep + 2).trim(),
                list = list,
                fields = fields,
            )
        }.take(30)
    }

    private fun parseHeader(raw: String?, warnings: MutableList<String>): Map<String, String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        if (text.startsWith("@js") || text.contains("<js>")) {
            warnings += "header 为 JS 规则，已忽略"
            return emptyMap()
        }
        return runCatching {
            json.parseToJsonElement(text).jsonObject.entries.associate {
                it.key to ((it.value as? JsonPrimitive)?.content ?: it.value.toString())
            }
        }.getOrElse {
            warnings += "header 解析失败，已忽略"
            emptyMap()
        }
    }

    /** concurrentRate: "500"（毫秒间隔）或 "1/1000"（次数/毫秒） */
    private fun parseConcurrentRate(raw: String?): RateLimitRule? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return if (it > 0) RateLimitRule(intervalMs = it) else null }
        val m = Regex("^(\\d+)/(\\d+)$").find(t) ?: return null
        val (count, ms) = m.destructured
        val interval = ms.toLong() / count.toLong().coerceAtLeast(1)
        return if (interval > 0) RateLimitRule(intervalMs = interval) else null
    }

    /** 从 searchUrl 的 charset 选项推断源级编码提示 */
    private fun detectCharsetHint(searchUrl: String): String? {
        val m = Regex("\"charset\"\\s*:\\s*\"([^\"]+)\"").find(searchUrl) ?: return null
        return m.groupValues[1].lowercase().takeIf { it != "utf-8" }
    }

    /** 正文规则默认取 html（保留 <br>/<img> 供段落切分），Legado 默认 text 会丢结构 */
    private fun forceHtmlExtractor(rule: String): String = rule

    // ---------- 顶层切分（跳过转义） ----------

    private fun splitTop(text: String, delim: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\') {
                i += 2
                continue
            }
            if (text.startsWith(delim, i)) {
                out += text.substring(start, i)
                i += delim.length
                start = i
            } else i++
        }
        out += text.substring(start)
        return out.filter { it.isNotBlank() }
    }

    // ---------- JsonObject 取值 ----------

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = when (val v = this[key]) {
        is JsonObject -> v
        is JsonPrimitive -> runCatching { json.parseToJsonElement(v.content).jsonObject }.getOrNull()
        else -> null
    }
}
