package com.radium.inkwell.core.source.legado

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.ContentRule
import com.radium.inkwell.core.source.DetailRule
import com.radium.inkwell.core.source.ExploreRule
import com.radium.inkwell.core.source.PurifyRule
import com.radium.inkwell.core.source.RateLimitRule
import com.radium.inkwell.core.source.RequestRule
import com.radium.inkwell.core.source.RuleParser
import com.radium.inkwell.core.source.isJsonPathVar
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

    /**
     * 转换器版本。改动了转换逻辑就 +1 —— 书源在导入时就转换好存进库了，
     * 升级 App 不会自动重转，得靠这个版本号识别出「用旧转换器转的书源」并重转。
     * 否则我们修的每个转换器 bug 都只对「新导入的书源」生效，老用户永远踩着旧坑。
     */
    const val VERSION = 3

    /** [sourceJson] 是该书源的 legado 原文，留着升级后重新转换用 */
    data class Converted(
        val rule: BookSourceRule,
        val warnings: List<String>,
        val sourceJson: String,
    )
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
                val c = convertOne(obj)
                converted += c.copy(sourceJson = obj.toString())
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
        val rawUrl = src.str("bookSourceUrl") ?: throw LegadoUnsupported("缺少 bookSourceUrl")
        // Legado 用 `#后缀`（常含 emoji）区分同站的重复书源，它不是真实地址的一部分。
        // 留在 baseUrl 里会污染所有相对链接的解析，还会随 Referer:{{baseUrl}} 发出去 ——
        // OkHttp 拒收非 ASCII 头值，整个书源当场不可用。id 仍用原值以保持唯一性。
        val baseUrl = rawUrl.substringBefore('#').trimEnd('/')
        if (baseUrl.isBlank()) throw LegadoUnsupported("bookSourceUrl 无效: $rawUrl")
        val name = src.str("bookSourceName") ?: baseUrl
        val type = src.int("bookSourceType") ?: 0
        if (type != 0) throw LegadoUnsupported("非文字书源（type=$type）不支持")

        // 书源站点列表里常见的占位条目：只有站名/分组等元信息，一条规则都没有（在 Legado 里同样不可用）。
        // 先于 ruleToc 检查报出，否则会被归成「缺少 ruleToc」，让人误以为是转换器挑食。
        val hasAnyRule = !src.str("searchUrl").isNullOrBlank() ||
            !src.str("exploreUrl").isNullOrBlank() ||
            src.obj("ruleSearch") != null ||
            src.obj("ruleToc") != null ||
            src.obj("ruleContent") != null
        if (!hasAnyRule) throw LegadoUnsupported("空书源（无任何规则）")

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
            putRule("title", ruleBookInfo?.str("name"), "detail.title", ctx)
            putRule("author", ruleBookInfo?.str("author"), "detail.author", ctx)
            putRule("coverUrl", ruleBookInfo?.str("coverUrl"), "detail.coverUrl", ctx)
            putRule("intro", ruleBookInfo?.str("intro"), "detail.intro", ctx)
            putRule("tocUrl", ruleBookInfo?.str("tocUrl"), "detail.tocUrl", ctx)
        }
        // 书源明确写了 tocUrl 却转换失败时不能放行：引擎会退化成「详情页即目录页」，
        // 而这个假设只在书源省略 tocUrl 时才成立。放行的后果是导入看似成功、
        // 一读就报「目录规则未匹配到章节」（如创世中文网的 tocUrl 是 {{js}} 表达式）。
        val tocUrlRaw = ruleBookInfo?.str("tocUrl")
        if (!tocUrlRaw.isNullOrBlank() && detailFields["tocUrl"] == null) {
            throw LegadoUnsupported("目录地址规则无法转换: ${ctx.lastError ?: tocUrlRaw.take(40)}")
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
        val tocList = convertRule(chapterListRaw, "toc.list", ctx)
            ?: throw LegadoUnsupported("目录列表规则无法转换: ${ctx.lastError ?: chapterListRaw}")
        val tocFields = buildMap {
            putRule("title", ruleToc.str("chapterName"), "toc.title", ctx, required = true)
            putRule("url", ruleToc.str("chapterUrl"), "toc.url", ctx, required = true)
        }
        val tocNext = convertRule(ruleToc.str("nextTocUrl"), "toc.nextPage", ctx)

        // 正文
        val ruleContent = src.obj("ruleContent") ?: throw LegadoUnsupported("缺少 ruleContent")
        val contentBase = ruleContent.str("content")
            ?: throw LegadoUnsupported("缺少正文规则")
        // 正文规则的 ## 替换下沉到 purify（绕开 DSL 内嵌正则的转义限制）
        val (contentRuleRaw, contentPurify) = splitHashReplace(contentBase)
        val contentRule = convertRule(contentRuleRaw, "content", ctx)
            ?: throw LegadoUnsupported("正文规则无法转换: ${ctx.lastError ?: contentBase}")
        val contentNext = convertRule(ruleContent.str("nextContentUrl"), "content.nextPage", ctx)
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
            throw LegadoUnsupported("目录章节规则无法转换: ${ctx.lastError ?: "规则缺失"}")
        }

        val rule = BookSourceRule(
            schemaVersion = 1,
            // id 用带 # 后缀的原值：同站可以并存多个书源，剥掉后缀会让它们撞 id 被去重丢掉
            id = rawUrl.removePrefix("https://").removePrefix("http://").trim('/'),
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
        return Converted(rule, warnings.toList(), sourceJson = "")
    }

    /** 搜索规则整体构建；必填字段无法转换时抛 LegadoUnsupported */
    private fun buildSearchRule(ruleSearch: JsonObject, searchUrlRaw: String, ctx: Ctx): SearchRule {
        val request = convertSearchUrl(searchUrlRaw)
        val list = convertRule(ruleSearch.str("bookList"), "search.list", ctx)
            ?: throw LegadoUnsupported("搜索列表规则无法转换: ${ctx.lastError ?: ruleSearch.str("bookList")}")
        val fields = buildMap {
            putRule("title", ruleSearch.str("name"), "search.title", ctx, required = true)
            putRule("bookUrl", ruleSearch.str("bookUrl"), "search.bookUrl", ctx, required = true)
            putRule("author", ruleSearch.str("author"), "search.author", ctx)
            putRule("coverUrl", ruleSearch.str("coverUrl"), "search.coverUrl", ctx)
            putRule("intro", ruleSearch.str("intro"), "search.intro", ctx)
            putRule("latestChapter", ruleSearch.str("lastChapter"), "search.lastChapter", ctx)
        }
        if (fields["title"] == null) {
            throw LegadoUnsupported("搜索书名规则无法转换: ${ctx.lastError ?: "规则缺失"}")
        }
        if (fields["bookUrl"] == null) {
            throw LegadoUnsupported("搜索书籍链接规则无法转换: ${ctx.lastError ?: "规则缺失"}")
        }
        return SearchRule(request = request, list = list, fields = fields, nextPage = null)
    }

    // ---------- 规则字符串转换 ----------

    private class Ctx(val warnings: MutableList<String>) {
        var lastError: String? = null
    }

    private fun MutableMap<String, String>.putRule(
        key: String,
        raw: String?,
        where: String,
        ctx: Ctx,
        required: Boolean = false,
    ) {
        val converted = convertRule(raw, where, ctx)
        if (converted != null) {
            put(key, converted)
        } else if (required && !raw.isNullOrBlank()) {
            // 交给调用方按缺失字段判定
        }
    }

    /** 单条 Legado 规则 → 我们的 DSL；不可转换返回 null 并记录原因 */
    private fun convertRule(raw: String?, where: String, ctx: Ctx): String? {
        val rule = raw?.trim().orEmpty()
        if (rule.isEmpty()) return null
        // 结构转换：JS 桥/XPath/无法识别的语法在此抛出，是真正不可转换 → 跳过该源
        val dsl = try {
            val (afterPut, putPipe) = splitPutRule(rule)
            val (base, hashPipe) = splitHashToPipe(afterPut, where, ctx)
            var d = convertBody(base.trim(), where)
            if (hashPipe != null) d += hashPipe
            if (putPipe != null) d += putPipe
            d
        } catch (e: Exception) {
            ctx.lastError = e.message
            ctx.warnings += "$where: 规则无法转换（${e.message?.take(60)}），已忽略「${rule.take(60)}」"
            return null
        }
        // 产物校验只记警告，不丢弃：Jsoup 扩展选择器(:eq/:containsOwn)与正则在
        // Android(ICU) 上比桌面 JVM 严格，校验误判不应拒绝整个源；运行时引擎对
        // 无效选择器本就容错（返回空结果）。
        runCatching { RuleParser.parse(dsl) }.onFailure {
            ctx.warnings += "$where: 规则校验警告（${it.message?.take(40)}），已保留"
        }
        return dsl
    }

    /**
     * 规则主体。默认语法（含 @css:）原样透传给 LegadoSelector —— 它的索引作用于「匹配集」，
     * 译成 CSS 选择器根本表达不了（排除 !、区间 [0:19]、多索引、逆序 [-1:0] 全都译不出来），
     * 这正是从前「导入成功、读起来是垃圾章节」的根源。
     */
    private fun convertBody(rule: String, where: String): String {
        // 整条规则只有 @put 时主体为空：用空字面量占位，管道（副作用）照常执行
        if (rule.isEmpty()) return "text:b64:"
        if (isPlainLegado(rule)) return legadoAtom(rule)

        // `规则@js:脚本` / `规则<js>脚本</js>`：Legado 里脚本作用于**整条规则的结果**，
        // 包括其中的 || 回退与 && 拼接。而我们 DSL 的优先级是 && > || > 管道 —— 直接拼出
        // `json:A || json:B | js:X` 只会把脚本挂在最后一个分支上，前面的分支命中就返回未加工的
        // 原始值（追书神器的目录地址只剩一个裸 id，拼出来必然 404）。DSL 没有分组语法，
        // 于是把脚本分发到每一个分支：分支为空时管道对空列表求值仍是空，回退语义不变。
        val trailing = splitTrailingJs(rule)
        if (trailing != null) {
            val (base, script) = trailing
            val pipe = jsPipe(script)
            return splitTop(base, "||").joinToString(" || ") { alt ->
                splitTop(alt.trim().replace("%%", "&&"), "&&")
                    .joinToString(" && ") { convertAtomic(it.trim(), where) + pipe }
            }
        }
        // 混用了 JS / JSONPath / 正则 / XPath / 模板：按 || 分段，用我们自己的 DSL 组合
        return splitTop(rule, "||").joinToString(" || ") { convertAlternative(it.trim(), where) }
    }

    /** 整条规则尾部的脚本；纯脚本（前段为空）返回 null，交给 convertAtomic 当原子规则处理 */
    private fun splitTrailingJs(rule: String): Pair<String, String>? {
        // <js> 块后面还接着规则时不能当「尾部脚本」处理，交给 convertAtomic 走流水线
        if (rule.contains("<js>")) {
            val start = rule.indexOf("<js>")
            val end = rule.indexOf("</js>", start)
            if (end < 0) throw LegadoUnsupported("JS 块未闭合")
            if (rule.substring(end + 5).isNotBlank()) return null
            val base = rule.substring(0, start).trim().trimEnd('@')
            return if (base.isEmpty()) null else base to rule.substring(start + 4, end).trim()
        }
        val at = rule.indexOf("@js:")
        return if (at > 0) rule.substring(0, at).trim() to rule.substring(at + 4) else null
    }

    private fun jsPipe(script: String): String {
        return " | js:b64:" + java.util.Base64.getEncoder()
            .encodeToString(script.toByteArray(Charsets.UTF_8))
    }

    /** 整条都是默认语法（无 JS / JSONPath / 正则 / XPath / 模板插值） */
    private fun isPlainLegado(rule: String): Boolean =
        !rule.contains("{{") &&
            !rule.contains("<js>") &&
            !rule.contains("@js:") &&
            !rule.contains("$.") &&
            !rule.startsWith("$[") &&
            !rule.startsWith("@json:", ignoreCase = true) &&
            !rule.startsWith("@XPath:", ignoreCase = true) &&
            !rule.startsWith("//") &&
            !rule.startsWith(":")

    /**
     * 剥出 `@put:{"变量名":"规则"}`。Legado 里它是规则串上的副作用标记：把内层规则在当前内容上
     * 求值的结果存进变量表，后续规则用 `@get:{变量名}` 取（目录→正文传参极常用，
     * 我们那份书源表里 75 个源在用）。转成我们的 `put:` 管道。
     */
    private fun splitPutRule(rule: String): Pair<String, String?> {
        if (!rule.contains("@put:")) return rule to null
        val matches = PUT_RULE.findAll(rule).toList()
        if (matches.isEmpty()) return rule to null
        var stripped = rule
        val bodies = mutableListOf<String>()
        for (m in matches) {
            stripped = stripped.replace(m.value, "")
            bodies += m.groupValues[1].trim().removePrefix("{").removeSuffix("}")
        }
        val spec = bodies.joinToString(",", prefix = "{", postfix = "}")
        val pipe = " | put:b64:" + java.util.Base64.getEncoder()
            .encodeToString(spec.toByteArray(Charsets.UTF_8))
        // 整条规则只有 @put 时，主体留空：管道照样执行（副作用），只是不产出值
        return stripped.trim() to pipe
    }

    private val PUT_RULE = Regex("@put:(\\{[^}]+\\})")

    /** XPath 里 | 是并集运算符，会被 DSL 切分器切坏 → 一律 base64 */
    private fun xpathAtom(path: String): String =
        "xpath:b64:" + java.util.Base64.getEncoder()
            .encodeToString(path.toByteArray(Charsets.UTF_8))

    /** 模板字面量；同样可能含 | 或 & */
    private fun literalAtom(rule: String): String =
        if (rule.none { it == '|' || it == '&' }) {
            "text:$rule"
        } else {
            "text:b64:" + java.util.Base64.getEncoder()
                .encodeToString(rule.toByteArray(Charsets.UTF_8))
        }

    /** 含 | 或 & 的规则会被 DSL 切分器切坏，故 base64 编码（沿用 js:b64: 的做法） */
    private fun legadoAtom(rule: String): String =
        if (rule.none { it == '|' || it == '&' }) {
            "legado:$rule"
        } else {
            "legado:b64:" + java.util.Base64.getEncoder()
                .encodeToString(rule.toByteArray(Charsets.UTF_8))
        }

    private fun convertAlternative(rule: String, where: String): String {
        val andParts = splitTop(rule.replace("%%", "&&"), "&&")
        if (andParts.size > 1) {
            return andParts.joinToString(" && ") { convertAtomic(it.trim(), where) }
        }
        return convertAtomic(rule, where)
    }

    /**
     * 脚本依赖的桥对象（java/cookie/cache/source/book/chapter）现已在 core 里实现，
     * 不再因为引用它们就整源跳过 —— 从前这一刀砍掉了 37 个书源。
     * 脚本跑不通时求值器按「不匹配」降级为空结果，和 Legado 的容错一致。
     */

    private fun convertAtomic(rule: String, where: String): String {
        // 整条 {{expr}}：Legado 视作 JS 表达式求值（绑定 baseUrl/result/key/page，与我们的 js: 规则一致）。
        // 最常见的用法是把详情页地址改写成目录页地址，如 {{baseUrl.replace(/detail/,"chapter")}}。
        if (rule.startsWith("{{") && rule.endsWith("}}") && rule.length > 4) {
            val expr = rule.substring(2, rule.length - 2)
            if (!expr.contains("{{")) {
                return attachJs("", expr.trim().ifEmpty { throw LegadoUnsupported("空 {{}} 规则") }, where)
            }
        }
        if (rule.startsWith("@XPath:", ignoreCase = true)) return xpathAtom(rule.substring(7).trim())
        if (rule.startsWith("//")) return xpathAtom(rule)
        // <js>...</js> 块：前段为基础规则（可为空），脚本转为 js 管道
        if (rule.contains("<js>")) {
            val jsStart = rule.indexOf("<js>")
            val jsEnd = rule.indexOf("</js>", jsStart)
            if (jsEnd < 0) throw LegadoUnsupported("JS 块未闭合")
            val script = rule.substring(jsStart + 4, jsEnd).trim()
            val base = rule.substring(0, jsStart).trim().trimEnd('@')
            val tail = rule.substring(jsEnd + 5).trim().trimStart('@')
            // Legado 把规则串按 <js> 切成若干段顺序流水：脚本产物是下一段的输入。
            // 从前见到「JS 块后还有规则」就整源跳过 —— 19 个书源卡在这。
            val head = attachJs(base, script, where)
            if (tail.isEmpty()) return head
            return head + " | rule:b64:" + java.util.Base64.getEncoder()
                .encodeToString(convertBody(tail, where).toByteArray(Charsets.UTF_8))
        }
        // rule@js:script 后缀（八一中文式）；整条 @js: 开头 = 纯脚本
        val jsSuffix = rule.indexOf("@js:")
        if (jsSuffix > 0) {
            return attachJs(rule.substring(0, jsSuffix), rule.substring(jsSuffix + 4), where)
        }
        if (rule.startsWith("@js:")) {
            return attachJs("", rule.substring(4), where)
        }
        // 含 {{}} 的规则作为模板字面量：求值期按 Legado 语义展开
        // （@/$. 开头 → 嵌套规则递归求值，其余 → JS 表达式）。
        // 从前只认 baseUrl 与 JSONPath，别的一概报「不支持」，光这一条卡掉 58 个书源。
        // 必须排在 JS 处理之后：脚本里的 {{$.x}} 插值是脚本的一部分，不能整条当模板
        // （Legado 同样是先按 <js>/@js: 切段，再在非脚本段里展开 {{}}）。
        if (rule.contains("{{")) {
            if (hasPostOptions(rule)) throw LegadoUnsupported("地址带 POST 选项，暂不支持")
            return literalAtom(rule)
        }
        return when {
            rule.startsWith("@json:", ignoreCase = true) -> "json:" + rule.substring(6).trim()
            rule.startsWith("$.") || rule.startsWith("$[") -> "json:$rule"
            rule.startsWith(":") -> "regex:" + escapeForDsl(rule.substring(1))
            else -> legadoAtom(rule)
        }
    }

    /**
     * 模板里的 {{}} 是否全都是求值期能填的变量（baseUrl / $.jsonpath）。
     * 含 JS 表达式（如 {{Date.now()}}、{{java.get(...)}}）的一律不算，交给上层报不支持。
     */
    /**
     * 「地址,{选项}」里带 method/body。引擎抓取前会把 `,{...}` 尾巴剥掉（见 resolveUrl），
     * 于是本该 POST 的请求会变成 GET —— 宁可报不支持，也不要产出一个「导入成功、读不了」的书源。
     */
    private fun hasPostOptions(rule: String): Boolean =
        Regex("""["']?(method|body)["']?\s*:""")
            .containsMatchIn(rule.substringAfter(",{", ""))

    /** 基础规则 + JS：base 为空时脚本作为原子规则，否则作为管道；base64 绕开 DSL 切分 */
    private fun attachJs(base: String, script: String, where: String): String {
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        return if (base.isEmpty()) {
            "js:b64:$encoded"
        } else {
            convertAtomic(base, where) + " | js:b64:$encoded"
        }
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
        // 地址整串是 JS 时，一个字都不能切：`,{` 往往落在 JS 字符串里
        // （`@js:url="https://m.wcxsw.org/search.php,{'body':'…'}"`），
        // 按老办法切在第一个 `,{` 会把地址拦腰截断。JS 的返回值与其自带的选项
        // 都留到运行期由引擎处理。
        val isJs = urlPart.contains("<js>", ignoreCase = true) ||
            urlPart.startsWith("@js:", ignoreCase = true)
        val optIdx = if (isJs) -1 else urlPart.indexOf(",{")
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
            Regex("\\{\\{([^}]*)\\}\\}").findAll(out).forEach { m ->
                val body = m.groupValues[1].trim()
                // 地址里的表达式同样交给求值期（Legado 的 {{}} 在 URL 里就是 JS），
                // 从前一律拒绝，把 {{Date.now()}}、{{cookie.getKey(...)}} 这类书源全挡在门外
                Unit
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

        val list = convertRule(ruleExplore.str("bookList"), "explore.list", ctx) ?: return emptyList()
        val fields = buildMap {
            putRule("title", ruleExplore.str("name"), "explore.title", ctx)
            putRule("bookUrl", ruleExplore.str("bookUrl"), "explore.bookUrl", ctx)
            putRule("author", ruleExplore.str("author"), "explore.author", ctx)
            putRule("coverUrl", ruleExplore.str("coverUrl"), "explore.coverUrl", ctx)
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
