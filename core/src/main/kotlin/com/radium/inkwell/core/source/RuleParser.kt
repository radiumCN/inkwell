package com.radium.inkwell.core.source

import org.jsoup.select.QueryParser
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** 规则语法错误；position 是原始规则字符串中的出错下标 */
class RuleSyntaxException(val position: Int, message: String) :
    Exception("规则语法错误(位置 $position): $message")

/** 书源用到了当前版本不支持的能力（如 js 引擎） */
class UnsupportedRuleException(message: String) : Exception(message)

/**
 * 选择器 mini-DSL 的 AST。
 * 语法：`引擎:查询[@提取器] [| 后处理...]`，`||` 回退（前者空则试后者），`&&` 拼接。
 */
sealed interface RuleNode {
    /** Jsoup CSS 选择器；query 为空表示当前上下文元素自身 */
    data class Css(val query: String, val extractor: CssExtractor) : RuleNode

    /** jayway JSONPath */
    data class JsonPath(val path: String) : RuleNode

    /** Legado 默认语法，原样交给 [LegadoSelector] 求值（索引作用于匹配集，CSS 表达不了） */
    data class Legado(val rule: String) : RuleNode

    /** 对上下文（元素取 outerHtml，可匹配属性/脚本；否则取文本）做正则提取，$1 优先 */
    data class RegexRule(val pattern: String) : RuleNode

    /** 字面量/模板，支持 {{var}} */
    data class Literal(val template: String) : RuleNode

    /** JS 脚本规则；script 为解码后的源码，需注入 ScriptRuntime 才能求值 */
    data class Js(val script: String) : RuleNode

    /** 后处理管道 */
    data class Pipe(val source: RuleNode, val ops: List<PipeOp>) : RuleNode

    /** 回退：依次尝试，取第一个非空结果 */
    data class Fallback(val options: List<RuleNode>) : RuleNode

    /** 拼接：各部分结果顺序合并 */
    data class Concat(val parts: List<RuleNode>) : RuleNode
}

sealed interface CssExtractor {
    data object Text : CssExtractor
    data object OwnText : CssExtractor
    data object Html : CssExtractor
    data object OuterHtml : CssExtractor
    data object Href : CssExtractor
    data object Src : CssExtractor
    data class Attr(val name: String) : CssExtractor
}

sealed interface PipeOp {
    /** `regex:PATTERN REPLACEMENT`（首个未转义空格分隔，无空格则替换为空串） */
    data class RegexReplace(val pattern: String, val replacement: String) : PipeOp

    /** `match:PATTERN` 保留匹配（捕获组 1 优先），不匹配的条目丢弃 */
    data class Match(val pattern: String) : PipeOp
    data object Trim : PipeOp
    data object StripTags : PipeOp
    data object First : PipeOp
    data object Last : PipeOp
    data class Index(val n: Int) : PipeOp

    /** `select:CSS` 在当前节点内继续选择（仅对节点列表有意义，字符串管道里为空操作） */
    data class Select(val query: String) : PipeOp
    data class Join(val sep: String) : PipeOp
    data class Prepend(val s: String) : PipeOp
    data class Append(val s: String) : PipeOp

    /** 对每个结果执行 JS（绑定 result），返回值替换该结果 */
    data class Js(val script: String) : PipeOp
}

/** 手写规则解析器；解析结果按规则原文缓存 */
object RuleParser {

    private val cache = ConcurrentHashMap<String, RuleNode>()

    @Throws(RuleSyntaxException::class, UnsupportedRuleException::class)
    fun parse(rule: String): RuleNode {
        cache[rule]?.let { return it }
        val node = parseConcat(rule, 0)
        cache[rule] = node
        return node
    }

    // ---- 分层解析：Concat(&&) > Fallback(||) > Pipe(|) > Atom ----

    private data class Segment(val text: String, val offset: Int)

    private fun parseConcat(text: String, offset: Int): RuleNode {
        val segs = split(text, offset, "&&")
        if (segs.size == 1) return parseFallback(segs[0])
        return RuleNode.Concat(segs.map { parseFallback(it) })
    }

    private fun parseFallback(seg: Segment): RuleNode {
        val segs = split(seg.text, seg.offset, "||")
        if (segs.size == 1) return parsePiped(segs[0])
        return RuleNode.Fallback(segs.map { parsePiped(it) })
    }

    private fun parsePiped(seg: Segment): RuleNode {
        val segs = split(seg.text, seg.offset, "|").map(::trimmed)
        val atom = parseAtom(segs[0])
        if (segs.size == 1) return atom
        return RuleNode.Pipe(atom, segs.drop(1).map { parsePipeOp(it) })
    }

    /** 按分隔符切分，跳过反斜杠转义（正则中的 \| \& 不参与切分） */
    private fun split(text: String, offset: Int, delim: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\') {
                i += 2
                continue
            }
            if (text.startsWith(delim, i)) {
                // 单个 | 兜底避开 ||
                if (delim == "|" && i + 1 < text.length && text[i + 1] == '|') {
                    i += 2
                    continue
                }
                out += Segment(text.substring(start, i), offset + start)
                i += delim.length
                start = i
            } else {
                i++
            }
        }
        out += Segment(text.substring(start), offset + start)
        return out
    }

    private fun trimmed(seg: Segment): Segment {
        var s = 0
        var e = seg.text.length
        while (s < e && seg.text[s].isWhitespace()) s++
        while (e > s && seg.text[e - 1].isWhitespace()) e--
        return Segment(seg.text.substring(s, e), seg.offset + s)
    }

    // ---- 原子规则 ----

    private fun parseAtom(seg: Segment): RuleNode {
        val t = seg.text
        if (t.isEmpty()) throw RuleSyntaxException(seg.offset, "规则为空")
        return when {
            t.startsWith("css:") -> parseCss(t.substring(4), seg.offset + 4)
            t.startsWith("json:") -> {
                val path = t.substring(5).trim()
                if (path.isEmpty()) throw RuleSyntaxException(seg.offset + 5, "JSONPath 为空")
                RuleNode.JsonPath(path)
            }
            t.startsWith("regex:") -> {
                val pattern = t.substring(6)
                if (pattern.isEmpty()) throw RuleSyntaxException(seg.offset + 6, "正则为空")
                checkRegex(pattern, seg.offset + 6)
                RuleNode.RegexRule(pattern)
            }
            t.startsWith("legado:") -> RuleNode.Legado(decodeBody(t.substring(7), seg.offset + 7))
            t.startsWith("text:") -> RuleNode.Literal(t.substring(5))
            t.startsWith("js:") -> RuleNode.Js(decodeBody(t.substring(3), seg.offset + 3))
            t.startsWith("@js:") -> RuleNode.Js(decodeBody(t.substring(4), seg.offset + 4))
            else -> parseCss(t, seg.offset)
        }
    }

    private fun parseCss(body: String, offset: Int): RuleNode.Css {
        // 找最后一个未转义的 @ 作为提取器分隔
        var at = -1
        var i = 0
        while (i < body.length) {
            if (body[i] == '\\') {
                i += 2
                continue
            }
            if (body[i] == '@') at = i
            i++
        }
        if (at >= 0) {
            val extractor = parseExtractor(body.substring(at + 1).trim())
            if (extractor != null) {
                return RuleNode.Css(validQuery(body.substring(0, at).trim(), offset), extractor)
            }
        }
        // 无提取器或非已知提取器：整体作为查询，默认 @text
        return RuleNode.Css(validQuery(body.trim(), offset), CssExtractor.Text)
    }

    private val ATTR = Regex("^attr\\((.+)\\)$")

    private fun parseExtractor(s: String): CssExtractor? = when (s) {
        "text" -> CssExtractor.Text
        "ownText" -> CssExtractor.OwnText
        "html" -> CssExtractor.Html
        "outerHtml" -> CssExtractor.OuterHtml
        "href" -> CssExtractor.Href
        "src" -> CssExtractor.Src
        else -> ATTR.find(s)?.let { CssExtractor.Attr(it.groupValues[1].trim()) }
    }

    /** 提前校验 CSS 选择器合法性；空查询表示当前元素自身 */
    private fun validQuery(query: String, offset: Int): String {
        if (query.isNotEmpty()) {
            try {
                QueryParser.parse(query)
            } catch (e: Exception) {
                throw RuleSyntaxException(offset, "CSS 选择器错误: ${e.message}")
            }
        }
        return query
    }

    // ---- 管道操作 ----

    private fun parsePipeOp(seg: Segment): PipeOp {
        val t = seg.text
        return when {
            t == "trim" -> PipeOp.Trim
            t == "stripTags" -> PipeOp.StripTags
            t == "first" -> PipeOp.First
            t == "last" -> PipeOp.Last
            t.startsWith("regex:") -> {
                val rest = t.substring(6)
                val sp = firstUnescapedSpace(rest)
                val pattern = if (sp < 0) rest else rest.substring(0, sp)
                val replacement = if (sp < 0) "" else rest.substring(sp + 1)
                if (pattern.isEmpty()) throw RuleSyntaxException(seg.offset + 6, "正则为空")
                checkRegex(pattern, seg.offset + 6)
                PipeOp.RegexReplace(pattern, replacement)
            }
            t.startsWith("match:") -> {
                val pattern = t.substring(6)
                if (pattern.isEmpty()) throw RuleSyntaxException(seg.offset + 6, "正则为空")
                checkRegex(pattern, seg.offset + 6)
                PipeOp.Match(pattern)
            }
            t.startsWith("select:") -> {
                val q = t.substring(7).trim()
                if (q.isEmpty()) throw RuleSyntaxException(seg.offset + 7, "select 选择器为空")
                PipeOp.Select(validQuery(q, seg.offset + 7))
            }
            t.startsWith("index:") -> {
                val n = t.substring(6).trim().toIntOrNull()
                    ?: throw RuleSyntaxException(seg.offset + 6, "index 需要整数")
                PipeOp.Index(n)
            }
            t.startsWith("join:") -> PipeOp.Join(t.substring(5))
            t.startsWith("prepend:") -> PipeOp.Prepend(t.substring(8))
            t.startsWith("append:") -> PipeOp.Append(t.substring(7))
            t.startsWith("js:") -> PipeOp.Js(decodeBody(t.substring(3), seg.offset + 3))
            else -> throw RuleSyntaxException(seg.offset, "未知管道操作: $t")
        }
    }

    /**
     * 规则体解码：`b64:` 前缀为 base64（转换器产物，绕开 |/&& 切分），
     * 否则原样使用（手写规则，注意内容里不能出现裸 | 或 &&）。
     * js 脚本与 legado 规则共用。
     */
    private fun decodeBody(body: String, offset: Int): String {
        val t = body.trim()
        if (t.isEmpty()) throw RuleSyntaxException(offset, "规则内容为空")
        return if (t.startsWith("b64:")) {
            try {
                String(java.util.Base64.getDecoder().decode(t.substring(4)), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw RuleSyntaxException(offset, "base64 解码失败")
            }
        } else t
    }

    private fun firstUnescapedSpace(s: String): Int {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\') {
                i += 2
                continue
            }
            if (s[i] == ' ') return i
            i++
        }
        return -1
    }

    private fun checkRegex(pattern: String, position: Int) {
        try {
            Pattern.compile(pattern)
        } catch (e: PatternSyntaxException) {
            throw RuleSyntaxException(position, "正则错误: ${e.message}")
        }
    }
}
