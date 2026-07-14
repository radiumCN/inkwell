package com.radium.inkwell.core.source

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * 规则求值上下文。
 * @param element HTML 上下文（页面 Document 或 list 规则选中的子元素）
 * @param json JSON 上下文（原始字符串或已解析对象）；regex/text 中间结果也放这里
 * @param baseUrl 页面最终 URL（重定向后），相对链接解析基准
 * @param vars 模板变量（keyword/page/baseUrl 等）
 * @param js 脚本桥（book/chapter/source/变量表）；书源脚本靠它拿上下文与传值
 */
data class EvalContext(
    val element: Element?,
    val json: Any?,
    val baseUrl: String,
    val vars: Map<String, String> = emptyMap(),
    val js: JsContext = JsContext(),
)

/**
 * 一次抓取链路里的脚本上下文。book/chapter 让脚本读到当前书与章节，
 * scriptVars 是 java.put/java.get 的变量表（Legado 里目录→正文传参极常用）。
 */
data class JsContext(
    /** 书源标识，脚本里的 source.getKey() */
    val sourceKey: String = "",
    val book: com.radium.inkwell.core.source.js.BookBridge =
        com.radium.inkwell.core.source.js.BookBridge(),
    val chapter: com.radium.inkwell.core.source.js.ChapterBridge =
        com.radium.inkwell.core.source.js.ChapterBridge(),
    /** java.put / java.get 的变量表 */
    val scriptVars: MutableMap<String, String> = ConcurrentHashMap(),
)

/** 规则求值器；正则按 pattern 缓存预编译；scriptRuntime 为空时 js 规则报不支持 */
class RuleEvaluator(
    private val scriptRuntime: com.radium.inkwell.core.source.js.ScriptRuntime? = null,
    /** 书源脚本的 HTTP 出口（java.ajax/get/post、cookie 读写）；不注入则这些方法返回空 */
    private val jsHttp: com.radium.inkwell.core.source.js.JsHttp? = null,
) {

    private val jsCache = com.radium.inkwell.core.source.js.JsCache()

    /** 书源级 KV（source.put/get），按书源留存，跨抓取链路可见 */
    private val sourceStores =
        ConcurrentHashMap<String, MutableMap<String, String>>()

    private val regexCache = ConcurrentHashMap<String, Regex>()
    private fun regexOf(pattern: String): Regex = regexCache.getOrPut(pattern) { Regex(pattern) }

    private val jsonConf: Configuration =
        Configuration.builder().options(Option.SUPPRESS_EXCEPTIONS).build()

    /** 求值为节点列表（list 规则用）；每个节点携带原上下文的 baseUrl/vars */
    fun evalToNodes(node: RuleNode, ctx: EvalContext): List<EvalContext> = when (node) {
        is RuleNode.Css ->
            selectElements(node.query, ctx).map { ctx.copy(element = it, json = null) }
        is RuleNode.Legado -> ctx.element
            ?.let { LegadoSelector.elements(it, withGetVars(node.rule, ctx)) }
            ?.map { ctx.copy(element = it, json = null) }
            .orEmpty()
        is RuleNode.XPath -> xpathNodes(node.path, ctx).map { ctx.copy(element = it, json = null) }
        is RuleNode.JsonPath -> when (val r = readJsonPath(node.path, ctx)) {
            null -> emptyList()
            is List<*> -> r.filterNotNull().map { ctx.copy(element = null, json = it) }
            else -> listOf(ctx.copy(element = null, json = r))
        }
        is RuleNode.RegexRule ->
            regexExtract(node.pattern, ctx).map { ctx.copy(element = null, json = it) }
        is RuleNode.Literal ->
            expandTemplate(node.template, ctx)
                .takeIf { it.isNotBlank() }
                ?.let { listOf(ctx.copy(element = null, json = it)) }
                ?: emptyList()
        is RuleNode.Js -> evalJs(node.script, ctx)?.let { out ->
            // list 规则的 JS 结果：JSON 形态进 json 上下文，HTML 形态解析后取子元素
            val t = out.trim()
            when {
                t.isEmpty() -> emptyList()
                t.startsWith("[") || t.startsWith("{") ->
                    listOf(ctx.copy(element = null, json = t))
                t.startsWith("<") -> Jsoup.parse(t).body().children()
                    .map { ctx.copy(element = it, json = null) }
                else -> listOf(ctx.copy(element = null, json = t))
            }
        } ?: emptyList()
        is RuleNode.Fallback ->
            node.options.firstNotNullOfOrNull { o ->
                evalToNodes(o, ctx).takeIf { it.isNotEmpty() }
            } ?: emptyList()
        is RuleNode.Concat -> node.parts.flatMap { evalToNodes(it, ctx) }
        is RuleNode.Pipe -> node.ops.fold(evalToNodes(node.source, ctx)) { acc, op ->
            when (op) {
                PipeOp.First -> acc.take(1)
                PipeOp.Last -> acc.takeLast(1)
                is PipeOp.Index -> listOfNotNull(acc.getOrNull(op.n))
                is PipeOp.Select -> acc.flatMap { c ->
                    c.element?.select(op.query)?.map { c.copy(element = it, json = null) }.orEmpty()
                }
                else -> acc // 字符串类管道对节点列表无意义，忽略
            }
        }
    }

    /** 求值为字符串列表；空串结果被丢弃 */
    fun evalToStrings(node: RuleNode, ctx: EvalContext): List<String> = when (node) {
        is RuleNode.Css ->
            selectElements(node.query, ctx)
                .map { extract(it, node.extractor) }
                .filter { it.isNotEmpty() }
        is RuleNode.Legado ->
            ctx.element?.let { LegadoSelector.strings(it, withGetVars(node.rule, ctx)) }.orEmpty()
        is RuleNode.XPath -> xpathStrings(node.path, ctx)
        is RuleNode.JsonPath -> jsonToStrings(readJsonPath(node.path, ctx))
        is RuleNode.RegexRule -> regexExtract(node.pattern, ctx)
        is RuleNode.Literal ->
            expandTemplate(node.template, ctx).let { if (it.isEmpty()) emptyList() else listOf(it) }
        is RuleNode.Js ->
            evalJs(node.script, ctx)?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        is RuleNode.Fallback ->
            node.options.firstNotNullOfOrNull { o ->
                evalToStrings(o, ctx).takeIf { list -> list.any { it.isNotBlank() } }
            } ?: emptyList()
        is RuleNode.Concat -> node.parts.flatMap { evalToStrings(it, ctx) }
        is RuleNode.Pipe -> {
            val src = node.source
            if (src is RuleNode.Css && node.ops.any { it is PipeOp.Select }) {
                // 含 select 的管道（「选中 → 取第 n 个 → 下钻」）必须按节点求值：
                // select 之前的都是节点操作，提取器作用在最终选中的节点上，其余管道再按字符串处理。
                val split = node.ops.indexOfLast { it is PipeOp.Select } + 1
                val nodes = evalToNodes(RuleNode.Pipe(src, node.ops.take(split)), ctx)
                val strings = nodes.mapNotNull { it.element?.let { el -> extract(el, src.extractor) } }
                    .filter { it.isNotEmpty() }
                node.ops.drop(split).fold(strings) { acc, op -> applyOp(op, acc, ctx) }
                    .filter { it.isNotEmpty() }
            } else {
                node.ops.fold(evalToStrings(node.source, ctx)) { acc, op -> applyOp(op, acc, ctx) }
                    .filter { it.isNotEmpty() }
            }
        }
    }

    /** 求值为单个字符串；多条结果用换行拼接，空结果返回 null */
    fun evalToString(node: RuleNode, ctx: EvalContext): String? =
        evalToStrings(node, ctx).takeIf { it.isNotEmpty() }?.joinToString("\n")

    // ---- css ----

    private fun selectElements(query: String, ctx: EvalContext): List<Element> {
        val el = ctx.element ?: return emptyList()
        return if (query.isEmpty()) listOf(el) else el.select(query).toList()
    }

    private fun extract(el: Element, extractor: CssExtractor): String = when (extractor) {
        CssExtractor.Text -> el.text()
        CssExtractor.OwnText -> el.ownText()
        CssExtractor.Html -> el.html()
        CssExtractor.OuterHtml -> el.outerHtml()
        CssExtractor.Href -> el.attr("href")
        CssExtractor.Src -> el.attr("src")
        is CssExtractor.Attr -> el.attr(extractor.name)
    }

    // ---- xpath ----

    /**
     * XPath 由 JsoupXpath 求值。Legado 的 @XPath 规则直接用它，语义一致。
     * 选择器非法或不匹配时返回空 —— 书源里手写的 XPath 质量参差，不该拖垮整条链路。
     */
    private fun xpathNodes(path: String, ctx: EvalContext): List<Element> {
        val el = ctx.element ?: return emptyList()
        return runCatching {
            org.seimicrawler.xpath.JXDocument.create(el.outerHtml())
                .selN(path)
                .mapNotNull { if (it.isElement) it.asElement() else null }
        }.getOrDefault(emptyList())
    }

    private fun xpathStrings(path: String, ctx: EvalContext): List<String> {
        val el = ctx.element ?: return emptyList()
        return runCatching {
            org.seimicrawler.xpath.JXDocument.create(el.outerHtml())
                .selN(path)
                .map { if (it.isElement) it.asElement().text() else it.asString() }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    // ---- json ----

    private fun readJsonPath(path: String, ctx: EvalContext): Any? {
        val doc = ctx.json ?: return null
        val parsed: Any = try {
            if (doc is String) jsonConf.jsonProvider().parse(doc) else doc
        } catch (_: Exception) {
            return null // 非 JSON 内容视为不匹配
        }
        return try {
            JsonPath.using(jsonConf).parse(parsed).read<Any?>(path)
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonToStrings(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.filterNotNull().map { scalarString(it) }.filter { it.isNotEmpty() }
        else -> listOf(scalarString(value)).filter { it.isNotEmpty() }
    }

    private fun scalarString(v: Any): String = if (v is String) v else v.toString()

    // ---- regex ----

    private fun regexExtract(pattern: String, ctx: EvalContext): List<String> {
        val target = contextText(ctx)
        if (target.isEmpty()) return emptyList()
        return regexOf(pattern).findAll(target)
            .map { m -> if (m.groupValues.size > 1) m.groupValues[1] else m.value }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * 规则里的字面量模板。与 Legado 对齐两点：
     * - {{$.x}} 在当前页的 JSON 上求值（JSON API 型书源常这样拼目录地址）
     * - {{baseUrl}} 是当前页地址，不是站点根 —— 例如 {{baseUrl}}/catalog/ 依赖前者
     */
    /**
     * 展开规则/地址里的 `{{}}`。
     *
     * Legado 的语义（AnalyzeRule.makeUpRule）：内容以 `@` / `$.` / `$[` / `//` 开头 →
     * 当**嵌套规则**递归求值；否则 → 当 **JS 表达式**跑。我们从前只认 baseUrl 和 JSONPath，
     * 其余一律报「不支持」，光这一条就卡掉了 58 个书源。
     *
     * 用括号配对扫描而非正则：JS 表达式里会出现 `}` 与 `|`，正则切不干净。
     */
    /**
     * 地址本身就是 JS：`<js>…</js>` 或 `@js:…`，脚本的返回值才是真正的地址。
     * 26 个书源里有 10 个这么写（起点、69书吧、思路客…），从前我们把整串当字面路径发出去，
     * 于是请求打到 `站点/@js:url=%22https://…`，一律 404/403 —— 换源「找不到这本书」多半死在这。
     *
     * 与 Legado 的 analyzeJs 一致：多段 js 依次求值，上一段的结果作为下一段的 `result` 输入。
     * 返回 null 表示这条地址不含 JS。
     */
    fun evalUrlJs(url: String, ctx: EvalContext): String? {
        if (!url.contains("<js>", ignoreCase = true) && !url.trimStart().startsWith("@js:", ignoreCase = true)) {
            return null
        }
        var result = url
        var start = 0
        var found = false
        for (m in URL_JS.findAll(url)) {
            found = true
            if (m.range.first > start) result += url.substring(start, m.range.first).trim()
            val script = m.groupValues[1].ifEmpty { m.groupValues[2] }
            result = runJs(script, ctx, result)
                ?: throw SourceException("地址脚本执行失败")
            start = m.range.last + 1
        }
        if (!found) return null
        if (url.length > start) result += url.substring(start)
        return result
    }

    fun expandTemplate(templateRaw: String, ctx: EvalContext): String {
        // @get:{k} 与 {{}} 都可能出现在同一条规则/地址里，先取变量再展开模板
        val template = withGetVars(templateRaw, ctx)
        if (!template.contains("{{")) return template
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val start = template.indexOf("{{", i)
            if (start < 0) {
                out.append(template, i, template.length)
                break
            }
            out.append(template, i, start)
            val close = matchingClose(template, start)
            if (close < 0) { // 没配对上，原样留着
                out.append(template, start, template.length)
                break
            }
            out.append(evalTemplateExpr(template.substring(start + 2, close).trim(), ctx))
            i = close + 2
        }
        return out.toString()
    }

    /** 从 `{{` 起找配对的 `}}`（按双花括号计数，JS 里的对象字面量是单花括号，不受影响） */
    private fun matchingClose(s: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < s.length - 1) {
            when {
                s.startsWith("{{", i) -> { depth++; i += 2 }
                s.startsWith("}}", i) -> {
                    depth--
                    if (depth == 0) return i
                    i += 2
                }
                else -> i++
            }
        }
        return -1
    }

    private fun evalTemplateExpr(body: String, ctx: EvalContext): String {
        // 我们自己产出的编码模板：{{keyword|encode:gbk}}（搜索地址要按站点编码转关键词）
        val pipeAt = body.indexOf('|')
        if (pipeAt > 0) {
            val name = body.substring(0, pipeAt).trim()
            val pipe = body.substring(pipeAt + 1).trim()
            if (pipe == "encode" || pipe.startsWith("encode:")) {
                val v = varValue(name, ctx)
                val cs = if (pipe == "encode") Charsets.UTF_8 else charsetOf(pipe.removePrefix("encode:"))
                return URLEncoder.encode(v, cs.name())
            }
        }
        // 嵌套规则（Legado：@ / $. / $[ / // 开头）
        if (body.startsWith("$.") || body.startsWith("$[")) {
            return evalToStrings(RuleNode.JsonPath(body), ctx).firstOrNull().orEmpty()
        }
        if (body.startsWith("//")) return "" // XPath，暂不支持
        if (body.startsWith("@")) {
            return evalToStrings(RuleNode.Legado(body), ctx).firstOrNull().orEmpty()
        }
        // 已知变量 / 整数算术（{{(page-1)*20}}）
        ctx.vars[body]?.let { return it }
        if (body == "baseUrl") return ctx.baseUrl
        evalArithmetic(body, ctx.vars)?.let { return it }
        // 其余按 JS 表达式求值 —— 这是 Legado 的默认分支。
        // 没有注入 JS 引擎时按空串处理：模板里的未知变量不该打死整条规则
        // （js: 原子规则仍会明确报不支持，那才是真的非它不可）。
        if (scriptRuntime == null) return ""
        return runJs(body, ctx, contextText(ctx)).orEmpty()
    }

    /**
     * `@get:{变量名}` 在求值期替换成变量值（Legado 在规则串组装期做同样的事）。
     * 变量由 `@put:{}` / `java.put()` 写入。
     */
    private fun withGetVars(rule: String, ctx: EvalContext): String =
        if (!rule.contains("@get:")) rule
        else GET_VAR.replace(rule) { m -> ctx.js.scriptVars[m.groupValues[1].trim()].orEmpty() }

    /** `@put:{"k":"规则"}`：每条规则在当前内容上求值，结果存进变量表 */
    private fun applyPut(spec: String, ctx: EvalContext) {
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(spec)
                    as kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return
        for ((key, value) in obj) {
            val ruleText = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val v = runCatching {
                evalToStrings(RuleParser.parse(convertPutValue(ruleText)), ctx).firstOrNull()
            }.getOrNull().orEmpty()
            ctx.js.scriptVars[key] = v
        }
    }

    /** @put 的 value 是一条 Legado 规则原文，按前缀分派到对应引擎 */
    private fun convertPutValue(rule: String): String = when {
        rule.startsWith("$.") || rule.startsWith("$[") -> "json:$rule"
        rule.startsWith("@js:") -> "js:" + rule.substring(4)
        else -> "legado:$rule"
    }

    private fun varValue(name: String, ctx: EvalContext): String =
        ctx.vars[name] ?: if (name == "baseUrl") ctx.baseUrl else ""

    /** 元素上下文取 outerHtml（可匹配属性与脚本内容），否则取 JSON/文本值 */
    private fun contextText(ctx: EvalContext): String =
        ctx.element?.outerHtml() ?: (ctx.json as? String ?: ctx.json?.toString() ?: "")

    // ---- 管道 ----

    private fun applyOp(op: PipeOp, list: List<String>, ctx: EvalContext): List<String> = when (op) {
        is PipeOp.RegexReplace -> list.map { regexOf(op.pattern).replace(it, op.replacement) }
        is PipeOp.Match -> list.mapNotNull { s ->
            regexOf(op.pattern).find(s)?.let { m ->
                if (m.groupValues.size > 1) m.groupValues[1] else m.value
            }
        }
        PipeOp.Trim -> list.map { it.trim() }
        PipeOp.StripTags -> list.map { Jsoup.parse(it).text() }
        PipeOp.First -> list.take(1)
        PipeOp.Last -> list.takeLast(1)
        is PipeOp.Index -> listOfNotNull(list.getOrNull(op.n))
        is PipeOp.Select -> list // 节点级操作，字符串管道里无意义
        is PipeOp.Put -> {
            applyPut(op.spec, ctx)
            list // 只有副作用
        }
        is PipeOp.Rule -> {
            // 上一步的产物是一段新内容（HTML 或 JSON），在它上面重新求值
            val node = runCatching { RuleParser.parse(op.rule) }.getOrNull()
            if (node == null) list
            else list.flatMap { s ->
                val sub = ctx.copy(element = Jsoup.parse(s, ctx.baseUrl), json = s)
                runCatching { evalToStrings(node, sub) }.getOrDefault(emptyList())
            }
        }
        is PipeOp.Join -> if (list.isEmpty()) emptyList() else listOf(list.joinToString(op.sep))
        is PipeOp.Prepend -> list.map { expandTemplate(op.s, ctx.vars) + it }
        is PipeOp.Append -> list.map { it + expandTemplate(op.s, ctx.vars) }
        is PipeOp.Js -> list.mapNotNull { s -> runJs(op.script, ctx, input = s) }
    }

    // ---- js ----

    /** 原子 js：result = 上下文文本（页面/元素/JSON） */
    private fun evalJs(script: String, ctx: EvalContext): String? =
        runJs(script, ctx, input = contextText(ctx))

    /**
     * 绑定书源脚本可见的全部上下文：变量（result/src/baseUrl/key/page）+ 桥对象
     * （java/cookie/cache/source/book/chapter）。Rhino 会把这些 Kotlin 对象反射成 JS 对象。
     *
     * 脚本异常按「不匹配」降级为空结果 —— 书源里的脚本质量参差，一处报错不该打死整条链路；
     * 未注入引擎则明确报不支持。
     */
    private fun runJs(script: String, ctx: EvalContext, input: String): String? {
        val runtime = scriptRuntime
            ?: throw UnsupportedRuleException("该书源需要 JS 引擎，当前版本不支持")
        val store = sourceStores.getOrPut(ctx.js.sourceKey) { ConcurrentHashMap() }
        val java = com.radium.inkwell.core.source.js.JavaBridge(jsHttp, jsCache, ctx.js.scriptVars)
        return try {
            runtime.eval(
                script,
                mapOf(
                    "result" to input,
                    "src" to input,
                    "baseUrl" to ctx.baseUrl,
                    "key" to (ctx.vars["keyword"] ?: ""),
                    "page" to (ctx.vars["page"]?.toIntOrNull() ?: 1),
                    "java" to java,
                    "cookie" to com.radium.inkwell.core.source.js.CookieBridge(jsHttp),
                    "cache" to jsCache,
                    "source" to com.radium.inkwell.core.source.js.SourceBridge(ctx.js.sourceKey, store),
                    "book" to ctx.js.book,
                    "chapter" to ctx.js.chapter,
                ),
            )
        } catch (e: Exception) {
            null
        }
    }
}

// ---- 模板与编码工具（引擎/HTTP 层共用） ----

// 变量名段允许内部空格（算术表达式如 {{page - 1}}）
// 花括号一律转义：Android 的 ICU 正则引擎不接受裸 { }，桌面 JVM 却当字面量放行
private val TEMPLATE_VAR = Regex("\\{\\{\\s*([^}|]+?)\\s*(?:\\|\\s*([^}]+?)\\s*)?\\}\\}")

/** 模板变量是否为 JSONPath（{{$.book_id}}），需在当前页 JSON 上求值而非查 vars */
private val GET_VAR = Regex("@get:\\{([^}]+)\\}")

internal fun isJsonPathVar(name: String): Boolean = name.startsWith("$.") || name.startsWith("$[")

/**
 * 展开 {{var}} 与 {{var|encode[:charset]}}；未知变量替换为空串。
 *
 * @param jsonPath {{$.x}} 的求值器。HTTP 层拼请求地址时没有页面上下文，传 null 即可；
 *   规则求值时由 RuleEvaluator 接到当前页的 JSON 上（详情页是 JSON API 的书源常这么写目录地址）。
 */
private val URL_JS = Regex("<js>([\\s\\S]*?)</js>|@js:([\\s\\S]*)", RegexOption.IGNORE_CASE)

internal fun expandTemplate(
    template: String,
    vars: Map<String, String>,
    jsonPath: ((String) -> String)? = null,
): String =
    TEMPLATE_VAR.replace(template) { m ->
        val name = m.groupValues[1]
        // JSONPath > 变量直取 > 算术表达式（如 {{(page-1)*50}}）
        val value = when {
            jsonPath != null && isJsonPathVar(name) -> jsonPath(name)
            else -> vars[name] ?: evalArithmetic(name, vars) ?: ""
        }
        val pipe = m.groupValues[2]
        when {
            pipe.isEmpty() -> value
            pipe == "encode" -> URLEncoder.encode(value, Charsets.UTF_8.name())
            pipe.startsWith("encode:") ->
                URLEncoder.encode(value, charsetOf(pipe.removePrefix("encode:")).name())
            else -> value
        }
    }

/**
 * 整数算术表达式求值（+ - * / 与括号），变量先按 vars 替换。
 * 任一变量未知或语法非法返回 null（模板处按空串处理）。
 */
internal fun evalArithmetic(expr: String, vars: Map<String, String>): String? {
    var unknownVar = false
    val substituted = Regex("[a-zA-Z_][a-zA-Z0-9_]*").replace(expr) { m ->
        vars[m.value] ?: run { unknownVar = true; "" }
    }
    if (unknownVar || !substituted.matches(Regex("[0-9+\\-*/()\\s]+"))) return null
    return try {
        ArithmeticParser(substituted).parse()?.toString()
    } catch (_: Exception) {
        null
    }
}

/** 递归下降：expr = term (±term)* ; term = factor (乘除 factor)* ; factor = num | (expr) | -factor */
private class ArithmeticParser(private val text: String) {
    private var pos = 0

    fun parse(): Long? {
        val v = expr() ?: return null
        skipSpace()
        return if (pos == text.length) v else null
    }

    private fun expr(): Long? {
        var v = term() ?: return null
        while (true) {
            skipSpace()
            when (peek()) {
                '+' -> { pos++; v += term() ?: return null }
                '-' -> { pos++; v -= term() ?: return null }
                else -> return v
            }
        }
    }

    private fun term(): Long? {
        var v = factor() ?: return null
        while (true) {
            skipSpace()
            when (peek()) {
                '*' -> { pos++; v *= factor() ?: return null }
                '/' -> {
                    pos++
                    val d = factor() ?: return null
                    if (d == 0L) return null
                    v /= d
                }
                else -> return v
            }
        }
    }

    private fun factor(): Long? {
        skipSpace()
        return when {
            peek() == '(' -> {
                pos++
                val v = expr()
                skipSpace()
                if (peek() != ')') return null
                pos++
                v
            }
            peek() == '-' -> { pos++; factor()?.let { -it } }
            else -> {
                val start = pos
                while (pos < text.length && text[pos].isDigit()) pos++
                if (pos == start) null else text.substring(start, pos).toLongOrNull()
            }
        }
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun skipSpace() {
        while (pos < text.length && text[pos] == ' ') pos++
    }
}

/** 字符集解析；GBK/GB2312 一律按 GB18030 处理 */
internal fun charsetOf(name: String): Charset = when (name.trim().lowercase()) {
    "gbk", "gb2312", "gb-2312", "gb_2312" -> Charset.forName("GB18030")
    else -> Charset.forName(name.trim())
}
