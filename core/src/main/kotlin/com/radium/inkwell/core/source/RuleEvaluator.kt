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
 */
data class EvalContext(
    val element: Element?,
    val json: Any?,
    val baseUrl: String,
    val vars: Map<String, String> = emptyMap(),
)

/** 规则求值器；正则按 pattern 缓存预编译；scriptRuntime 为空时 js 规则报不支持 */
class RuleEvaluator(
    private val scriptRuntime: com.radium.inkwell.core.source.js.ScriptRuntime? = null,
) {

    private val regexCache = ConcurrentHashMap<String, Regex>()
    private fun regexOf(pattern: String): Regex = regexCache.getOrPut(pattern) { Regex(pattern) }

    private val jsonConf: Configuration =
        Configuration.builder().options(Option.SUPPRESS_EXCEPTIONS).build()

    /** 求值为节点列表（list 规则用）；每个节点携带原上下文的 baseUrl/vars */
    fun evalToNodes(node: RuleNode, ctx: EvalContext): List<EvalContext> = when (node) {
        is RuleNode.Css ->
            selectElements(node.query, ctx).map { ctx.copy(element = it, json = null) }
        is RuleNode.JsonPath -> when (val r = readJsonPath(node.path, ctx)) {
            null -> emptyList()
            is List<*> -> r.filterNotNull().map { ctx.copy(element = null, json = it) }
            else -> listOf(ctx.copy(element = null, json = r))
        }
        is RuleNode.RegexRule ->
            regexExtract(node.pattern, ctx).map { ctx.copy(element = null, json = it) }
        is RuleNode.Literal ->
            expandRuleTemplate(node.template, ctx)
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
        is RuleNode.JsonPath -> jsonToStrings(readJsonPath(node.path, ctx))
        is RuleNode.RegexRule -> regexExtract(node.pattern, ctx)
        is RuleNode.Literal ->
            expandRuleTemplate(node.template, ctx).let { if (it.isEmpty()) emptyList() else listOf(it) }
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
    private fun expandRuleTemplate(template: String, ctx: EvalContext): String =
        expandTemplate(template, ctx.vars + ("baseUrl" to ctx.baseUrl)) { path ->
            jsonToStrings(readJsonPath(path, ctx)).firstOrNull().orEmpty()
        }

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
     * 绑定 Legado 常用变量子集：result/src/baseUrl/key/page。
     * 脚本异常按"不匹配"降级为空结果；未注入引擎则明确报不支持。
     */
    private fun runJs(script: String, ctx: EvalContext, input: String): String? {
        val runtime = scriptRuntime
            ?: throw UnsupportedRuleException("该书源需要 JS 引擎，当前版本不支持")
        return try {
            runtime.eval(
                script,
                mapOf(
                    "result" to input,
                    "src" to input,
                    "baseUrl" to ctx.baseUrl,
                    "key" to (ctx.vars["keyword"] ?: ""),
                    "page" to (ctx.vars["page"]?.toIntOrNull() ?: 1),
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
internal fun isJsonPathVar(name: String): Boolean = name.startsWith("$.") || name.startsWith("$[")

/**
 * 展开 {{var}} 与 {{var|encode[:charset]}}；未知变量替换为空串。
 *
 * @param jsonPath {{$.x}} 的求值器。HTTP 层拼请求地址时没有页面上下文，传 null 即可；
 *   规则求值时由 RuleEvaluator 接到当前页的 JSON 上（详情页是 JSON API 的书源常这么写目录地址）。
 */
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
