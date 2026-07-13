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

/** 规则求值器；正则按 pattern 缓存预编译 */
class RuleEvaluator {

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
            expandTemplate(node.template, ctx.vars)
                .takeIf { it.isNotBlank() }
                ?.let { listOf(ctx.copy(element = null, json = it)) }
                ?: emptyList()
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
            expandTemplate(node.template, ctx.vars).let { if (it.isEmpty()) emptyList() else listOf(it) }
        is RuleNode.Fallback ->
            node.options.firstNotNullOfOrNull { o ->
                evalToStrings(o, ctx).takeIf { list -> list.any { it.isNotBlank() } }
            } ?: emptyList()
        is RuleNode.Concat -> node.parts.flatMap { evalToStrings(it, ctx) }
        is RuleNode.Pipe ->
            node.ops.fold(evalToStrings(node.source, ctx)) { acc, op -> applyOp(op, acc, ctx) }
                .filter { it.isNotEmpty() }
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
        is PipeOp.Join -> if (list.isEmpty()) emptyList() else listOf(list.joinToString(op.sep))
        is PipeOp.Prepend -> list.map { expandTemplate(op.s, ctx.vars) + it }
        is PipeOp.Append -> list.map { it + expandTemplate(op.s, ctx.vars) }
    }
}

// ---- 模板与编码工具（引擎/HTTP 层共用） ----

private val TEMPLATE_VAR = Regex("\\{\\{\\s*([^}|\\s]+)\\s*(?:\\|\\s*([^}]+?)\\s*)?}}")

/** 展开 {{var}} 与 {{var|encode[:charset]}}；未知变量替换为空串 */
internal fun expandTemplate(template: String, vars: Map<String, String>): String =
    TEMPLATE_VAR.replace(template) { m ->
        val value = vars[m.groupValues[1]] ?: ""
        val pipe = m.groupValues[2]
        when {
            pipe.isEmpty() -> value
            pipe == "encode" -> URLEncoder.encode(value, Charsets.UTF_8.name())
            pipe.startsWith("encode:") ->
                URLEncoder.encode(value, charsetOf(pipe.removePrefix("encode:")).name())
            else -> value
        }
    }

/** 字符集解析；GBK/GB2312 一律按 GB18030 处理 */
internal fun charsetOf(name: String): Charset = when (name.trim().lowercase()) {
    "gbk", "gb2312", "gb-2312", "gb_2312" -> Charset.forName("GB18030")
    else -> Charset.forName(name.trim())
}
