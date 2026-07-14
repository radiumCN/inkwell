package com.radium.inkwell.core.source

import java.util.concurrent.ConcurrentHashMap

/**
 * 原生 Legado 规则串 → [RuleNode] 求值树。
 *
 * 与旧的导入期转换器（已删除）的根本区别：
 * - **运行期、按需**编译单条规则，而不是导入期把整源翻译成自研 DSL 字符串再存库。
 * - **不经字符串 DSL 往返**：直接产出 [RuleNode]，没有 base64 绕转义、没有 `||`/`&&` 被二次切分
 *   的优先级错位问题（那是转换器 bug 的主要来源）。
 * - **永不抛错**：无法识别的规则一律降级成 [RuleNode.Legado]（最坏是求值出空结果），
 *   而不是把整个书源判死。这与 Legado 自身「规则不匹配即空」的容错一致。
 *
 * 语法分派（与 Legado AnalyzeRule 对齐）：
 * - 尾部 `##正则##替换###` → 结果上的正则替换（[PipeOp.RegexReplace]）
 * - `@put:{"k":"规则"}` → 求值副作用存变量，`@get:{k}` 在求值期由 [RuleEvaluator] 回填
 * - `<js>…</js>` / `规则@js:脚本` → 规则结果喂给脚本，多段顺序流水
 * - `||` 回退（首个非空）、`&&` 拼接
 * - 原子：`@XPath:`/`//` XPath、`@json:`/`$.`/`$[` JSONPath、前导 `:` 正则、
 *   含 `{{}}` 的模板、其余交给 [LegadoSelector]（默认 Jsoup 层级 + `@css:` + 索引 + `%%`）
 */
object LegadoRuleAnalyzer {

    private val cache = ConcurrentHashMap<String, RuleNode>()

    /** 编译一条 Legado 规则；结果按原文缓存。空规则得到空字面量。 */
    fun analyze(rule: String): RuleNode = cache.getOrPut(rule) { build(rule.trim()) }

    private fun build(raw: String): RuleNode {
        if (raw.isEmpty()) return RuleNode.Literal("")
        val (afterPut, putSpecs) = peelPut(raw)
        val (core, replace) = peelHashReplace(afterPut)
        val node = buildCore(core.trim())
        val ops = buildList {
            replace?.let { add(it) }
            putSpecs.forEach { add(PipeOp.Put(it)) }
        }
        return if (ops.isEmpty()) node else RuleNode.Pipe(node, ops)
    }

    // ---------- <js> / @js: 顺序流水 ----------

    private fun buildCore(s: String): RuleNode =
        if (s.contains("<js>", ignoreCase = true) || s.contains("@js:", ignoreCase = true)) {
            buildJsPipeline(s)
        } else {
            buildAlternatives(s)
        }

    /**
     * Legado 把规则串按 `<js>` 切成若干段顺序执行：前一段的产物是下一段的输入。
     * `头<js>脚本</js>尾` = 「先按头规则选出结果 → 跑脚本 → 在脚本产物上再按尾规则求值」。
     * `头@js:脚本` = 头规则结果喂给脚本（脚本吃到行尾）。
     */
    private fun buildJsPipeline(s: String): RuleNode {
        // @js: 后缀：整行切成 头 + 脚本
        val jsAt = topLevelIndexOf(s, "@js:")
        if (jsAt >= 0 && !s.substring(0, jsAt).contains("<js>", ignoreCase = true)) {
            val head = s.substring(0, jsAt).trim()
            val script = s.substring(jsAt + 4)
            return attachJs(head, script, tail = "")
        }
        val start = s.indexOf("<js>", ignoreCase = true)
        if (start < 0) return buildAlternatives(s) // 只剩 @js: 已在上面处理
        val end = s.indexOf("</js>", start, ignoreCase = true)
        if (end < 0) {
            // 未闭合：把 <js> 之后全当脚本，降级但不判死
            return attachJs(s.substring(0, start).trim(), s.substring(start + 4), tail = "")
        }
        val head = s.substring(0, start).trim()
        val script = s.substring(start + 4, end).trim()
        val tail = s.substring(end + 5).trim().trimStart('@')
        return attachJs(head, script, tail)
    }

    /** 头规则 + 脚本(+ 尾规则)。头为空时脚本作用在页面文本上；尾非空时在脚本产物上再求值。 */
    private fun attachJs(head: String, script: String, tail: String): RuleNode {
        val ops = mutableListOf<PipeOp>()
        val source: RuleNode = if (head.isEmpty()) {
            RuleNode.Js(script) // 头为空：脚本吃页面/元素文本
        } else {
            ops += PipeOp.Js(script) // 头规则结果喂给脚本
            buildAlternatives(head)
        }
        // [PipeOp.Rule] 的规则串由 [RuleEvaluator] 交回本分析器求值，故直接存原生尾串。
        if (tail.isNotEmpty()) ops += PipeOp.Rule(tail)
        return if (ops.isEmpty()) source else RuleNode.Pipe(source, ops)
    }

    // ---------- || 回退 / && 拼接 ----------

    private fun buildAlternatives(s: String): RuleNode {
        if (s.isEmpty()) return RuleNode.Literal("")
        // 整串是纯 Jsoup（含 @css: / 层级 / 索引 / %%）时，交给 LegadoSelector 整体处理，
        // 它自己就懂 ||/&&/%%，切开反而丢了 %% 语义。
        if (isPureJsoup(s)) return RuleNode.Legado(s)
        val alts = splitTop(s, "||")
        val nodes = alts.map { buildConcat(it.trim()) }
        return if (nodes.size == 1) nodes[0] else RuleNode.Fallback(nodes)
    }

    private fun buildConcat(s: String): RuleNode {
        val parts = splitTop(s, "&&")
        return if (parts.size == 1) atom(parts[0].trim())
        else RuleNode.Concat(parts.map { atom(it.trim()) })
    }

    // ---------- 原子规则 ----------

    private fun atom(a: String): RuleNode = when {
        a.isEmpty() -> RuleNode.Literal("")
        a.startsWith("@XPath:", ignoreCase = true) -> RuleNode.XPath(a.substring(7).trim())
        a.startsWith("//") -> RuleNode.XPath(a)
        a.startsWith("@json:", ignoreCase = true) -> RuleNode.JsonPath(a.substring(6).trim())
        a.startsWith("$.") || a.startsWith("$[") -> RuleNode.JsonPath(a)
        // 前导冒号 = 正则（与旧转换器同约定）；css 伪类 :eq 之类恒以 @css: 或标签打头，不会裸起 :
        a.startsWith(":") -> RuleNode.RegexRule(a.substring(1))
        a.contains("{{") -> RuleNode.Literal(a)
        else -> RuleNode.Legado(a) // 默认 Jsoup（LegadoSelector），含 @css:
    }

    // ---------- @put / ## 剥离 ----------

    private val PUT_RULE = Regex("@put:(\\{[^}]+\\})")

    /** 剥出所有 `@put:{...}`，返回去掉后的规则与各段 spec（原文 JSON，applyPut 再解析）。 */
    private fun peelPut(rule: String): Pair<String, List<String>> {
        if (!rule.contains("@put:")) return rule to emptyList()
        val specs = mutableListOf<String>()
        var stripped = rule
        for (m in PUT_RULE.findAll(rule)) {
            stripped = stripped.replace(m.value, "")
            specs += m.groupValues[1]
        }
        return stripped.trim() to specs
    }

    /** `规则##正则##替换###` → (规则, RegexReplace)。仅取首个 `##` 段；`###` 尾标记去掉。 */
    private fun peelHashReplace(rule: String): Pair<String, PipeOp.RegexReplace?> {
        val idx = topLevelIndexOf(rule, "##")
        if (idx < 0) return rule to null
        val base = rule.substring(0, idx)
        val rest = rule.substring(idx + 2).removeSuffix("###")
        val sep = rest.indexOf("##")
        val pattern = if (sep < 0) rest else rest.substring(0, sep)
        val replacement = if (sep < 0) "" else rest.substring(sep + 2)
        if (pattern.isBlank()) return base to null
        return base to PipeOp.RegexReplace(pattern, replacement)
    }

    // ---------- 判定与切分 ----------

    private fun isPureJsoup(s: String): Boolean =
        !s.contains("{{") &&
            !s.contains("<js>", ignoreCase = true) &&
            !s.contains("@js:", ignoreCase = true) &&
            !s.contains("$.") &&
            !s.startsWith("$[") &&
            !s.startsWith("@json:", ignoreCase = true) &&
            !s.startsWith("@XPath:", ignoreCase = true) &&
            !s.startsWith("//") &&
            !s.startsWith(":")

    /** 括号感知的顶层切分：`[...]`/`(...)` 内的分隔符不切；反斜杠转义跳过。 */
    private fun splitTop(text: String, delim: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> { i += 2; continue }
                c == '[' || c == '(' -> depth++
                c == ']' || c == ')' -> if (depth > 0) depth--
                depth == 0 && text.startsWith(delim, i) -> {
                    out += text.substring(start, i)
                    i += delim.length
                    start = i
                    continue
                }
            }
            i++
        }
        out += text.substring(start)
        return out.filter { it.isNotBlank() }
    }

    /** 顶层（括号外）首次出现 token 的下标，找不到返回 -1。 */
    private fun topLevelIndexOf(text: String, token: String): Int {
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> { i += 2; continue }
                c == '[' || c == '(' -> depth++
                c == ']' || c == ')' -> if (depth > 0) depth--
                depth == 0 && text.startsWith(token, i) -> return i
            }
            i++
        }
        return -1
    }
}
