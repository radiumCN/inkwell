package com.radium.inkwell.core.source


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

    /**
     * 按原文缓存编译结果，有上限。从前是无界的 ConcurrentHashMap：脚本里动态拼出来的规则
     * （`java.getString('$.list['+i+'].name')`）、`@put` 里的规则也都进缓存，几千个源长期用下来只增不减。
     */
    private val cache = object : LinkedHashMap<String, RuleNode>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RuleNode>?): Boolean =
            size > CACHE_SIZE
    }
    private const val CACHE_SIZE = 4096

    /** 编译一条 Legado 规则；结果按原文缓存。空规则得到空字面量。 */
    fun analyze(rule: String): RuleNode {
        synchronized(cache) { cache[rule] }?.let { return it }
        // 锁外编译：重了也只是多编一次，结果一样
        val built = build(rule.trim())
        return synchronized(cache) { cache.getOrPut(rule) { built } }
    }

    private fun build(raw: String): RuleNode {
        if (raw.isEmpty()) return RuleNode.Literal("")
        val (afterPut, putSpecs) = peelPut(raw)
        val node = buildCore(afterPut.trim())
        if (putSpecs.isEmpty()) return node
        val puts = putSpecs.map { PipeOp.Put(it) }
        return if (node is RuleNode.Pipe) RuleNode.Pipe(node.source, node.ops + puts) else RuleNode.Pipe(node, puts)
    }

    // ---------- <js> / @js: 顺序流水 ----------

    /**
     * 与 Legado 的 splitSourceRule 同序：**先**按 `<js>…</js>` / `@js:…` 切出脚本段，
     * `##` 只在脚本段之外的规则段里解析。从前反过来 —— 整条先剥 `##` 再切脚本：
     * `class.intro@text##简介：@js:result.trim()` 的整个尾巴（连同脚本）成了正则；
     * `@js:var s='a##b';s` 的脚本被 `##` 拦腰截断。
     */
    private fun buildCore(s: String): RuleNode =
        if (s.contains("<js>", ignoreCase = true) || s.contains("@js:", ignoreCase = true)) {
            buildJsPipeline(s)
        } else {
            buildRuleSegment(s)
        }

    /** 不含脚本的一段规则：剥出尾部 `##` 替换后编译主体 */
    private fun buildRuleSegment(s: String): RuleNode {
        val (core, replace) = peelSegmentHash(s)
        val node = buildAlternatives(core)
        return if (replace == null) node else RuleNode.Pipe(node, listOf(replace))
    }

    /**
     * Legado 的 Regex 模式（前导 `:`）不把 `##` 当替换指令 —— 那是正则本身的内容，不能剥。
     * 主体为空的 `##正则`（脚本段后面紧跟的替换）原样返回空主体，由调用方把替换接到上一段的产物上。
     */
    private fun peelSegmentHash(s: String): Pair<String, PipeOp?> {
        val t = s.trim()
        if (t.startsWith(":")) return t to null
        val (core, replace) = peelHashReplace(t)
        return core.trim() to replace
    }

    /**
     * Legado 把规则串按 `<js>` / `@js:` 切成若干段顺序执行：前一段的产物是下一段的输入。
     * `头<js>脚本</js>尾` = 「先按头规则选出结果 → 跑脚本 → 在脚本产物上再按尾规则求值」。
     * `头@js:脚本` = 头规则结果喂给脚本（脚本吃到行尾）。
     *
     * 规则段里的 `##` 只作用于这一段：头段的替换在喂给脚本之前做；脚本之后的 `##正则`（主体为空）
     * 替换的是脚本产物 —— Legado 里空主体的段不求值、只做替换，语义相同。
     */
    private fun buildJsPipeline(s: String): RuleNode {
        val segments = splitJsSegments(s)
        var source: RuleNode? = null
        val ops = mutableListOf<PipeOp>()
        for ((isJs, text) in segments) {
            if (isJs) {
                if (source == null) source = RuleNode.Js(text) // 头为空：脚本吃页面/元素文本
                else ops += PipeOp.Js(text) // 前一段的结果喂给脚本
                continue
            }
            val (core, replace) = peelSegmentHash(text)
            if (source == null) {
                // 头规则段。只有 `##` 没有主体时 Legado 不求值、直接替换当前内容，等价于先取上下文原文
                source = if (core.isEmpty()) RuleNode.Js("result") else buildAlternatives(core)
            } else {
                // 脚本之后的尾规则段：在脚本产物上再求值。[PipeOp.Rule] 的规则串由 [RuleEvaluator]
                // 交回本分析器求值，故直接存原生尾串（去掉 `</js>@` 连接用的前导 `@`）。
                val tail = core.trimStart('@').trim()
                if (tail.isNotEmpty()) ops += PipeOp.Rule(tail)
            }
            replace?.let { ops += it }
        }
        val head = source ?: return RuleNode.Literal("")
        return if (ops.isEmpty()) head else RuleNode.Pipe(head, ops)
    }

    /**
     * 按脚本段切分：`<js>…</js>` 是一段脚本（未闭合则吃到行尾），顶层 `@js:` 之后整行都是脚本。
     * 返回 (是否脚本, 文本)；空白的规则段丢弃。
     */
    private fun splitJsSegments(s: String): List<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        var rest = s
        while (rest.isNotEmpty()) {
            val jsAt = topLevelIndexOf(rest, "@js:")
            val blockAt = rest.indexOf("<js>", ignoreCase = true)
            if (blockAt >= 0 && (jsAt < 0 || blockAt < jsAt)) {
                rest.substring(0, blockAt).takeIf { it.isNotBlank() }?.let { out += false to it }
                val end = rest.indexOf("</js>", blockAt + 4, ignoreCase = true)
                if (end < 0) {
                    // 未闭合：把 <js> 之后全当脚本，降级但不判死
                    out += true to rest.substring(blockAt + 4)
                    break
                }
                out += true to rest.substring(blockAt + 4, end).trim()
                rest = rest.substring(end + 5)
            } else if (jsAt >= 0) {
                rest.substring(0, jsAt).takeIf { it.isNotBlank() }?.let { out += false to it }
                out += true to rest.substring(jsAt + 4)
                break
            } else {
                if (rest.isNotBlank()) out += false to rest
                break
            }
        }
        return out
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

    /**
     * `&&` 拼接 / `%%` 交替合并。两种混写时照 Legado 的 splitRule：按**先出现**的那种切，各段再递归切另一种
     * （`a&&b%%c` = 拼接(a, 交替(b, c))）。
     *
     * 从前只认 `&&`：JSONPath / XPath 里的 `%%` 原样留在路径里，什么都匹配不到 —— 晋江书单
     * `$.data.recommend[*]%%$.data.find[*]` 整个分类一条都没有（纯 Jsoup 规则交给 LegadoSelector，它本来就懂 `%%`）。
     */
    private fun buildConcat(s: String): RuleNode {
        val and = topLevelIndexOf(s, "&&")
        val mix = topLevelIndexOf(s, "%%")
        val delim = when {
            and < 0 && mix < 0 -> return atom(s.trim())
            mix < 0 -> "&&"
            and < 0 -> "%%"
            else -> if (and < mix) "&&" else "%%"
        }
        val parts = splitTop(s, delim)
        if (parts.size == 1) return buildConcat(parts[0].trim())
        val nodes = parts.map { buildConcat(it.trim()) }
        return if (delim == "&&") RuleNode.Concat(nodes) else RuleNode.Interleave(nodes)
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

    /**
     * 剥出所有 `@put:{...}`，返回去掉后的规则与各段 spec（原文 JSON，applyPut 再解析）。
     *
     * 不用正则 `@put:(\{[^}]+\})`：JSON 值里含 `{{page}}` 这类嵌套花括号时 `[^}]+` 会在第一个
     * `}` 处截断，把 spec 切坏。改成花括号配对扫描（Legado splitPutRule 同思路）。并且**跳过
     * `<js>…</js>` 段与 `@js:` 之后** —— 脚本体里出现的 `@put:` 是脚本内容，不是规则语法。
     */
    private fun peelPut(rule: String): Pair<String, List<String>> {
        if (!rule.contains("@put:", ignoreCase = true)) return rule to emptyList()
        val specs = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < rule.length) {
            // <js>…</js> 原样保留：脚本体内的 @put: / 花括号不参与规则解析
            if (rule.startsWith("<js>", i, ignoreCase = true)) {
                val end = rule.indexOf("</js>", i + 4, ignoreCase = true)
                val stop = if (end < 0) rule.length else end + 5
                sb.append(rule, i, stop)
                i = stop
                continue
            }
            // @js: 之后整行都是脚本（与 Legado 的脚本切分一致），里面的 @put: 同样不是规则语法
            if (rule.startsWith("@js:", i, ignoreCase = true)) {
                sb.append(rule, i, rule.length)
                break
            }
            if (rule.startsWith("@put:", i, ignoreCase = true) && rule.getOrNull(i + 5) == '{') {
                val close = matchBrace(rule, i + 5)
                if (close >= 0) {
                    specs += rule.substring(i + 5, close + 1)
                    i = close + 1
                    continue
                }
            }
            sb.append(rule[i])
            i++
        }
        return sb.toString().trim() to specs
    }

    /** 从 `{`（下标 open）起按花括号配对找到匹配的 `}`；`{{page}}` 这类嵌套一并计入。找不到返回 -1。 */
    private fun matchBrace(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    /**
     * `规则##正则##替换###` → (规则, RegexReplace / RegexReplaceFirst)。
     *
     * 与 Legado 一致，按顶层 `##` 切出的段数决定语义（`###` = 末尾 `##` + 空段 `#`）：
     * - `规则##正则`          → 全局删除匹配（替换为空）
     * - `规则##正则##替换`     → 全局替换
     * - `规则##正则##替换###`  → 取**首个**匹配、在其内 replaceFirst；无匹配则结果为空串（抽取用）
     *
     * 切分跳过 `<js>…</js>`（脚本体内的 `##` 不是替换指令，否则脚本会被腰斩）与 `[]`/`()`。
     */
    private fun peelHashReplace(rule: String): Pair<String, PipeOp?> {
        val parts = splitTopHash(rule)
        if (parts.size < 2) return rule to null
        val base = parts[0]
        val pattern = parts[1]
        if (pattern.isBlank()) return base to null
        val replacement = parts.getOrNull(2).orEmpty()
        val op = if (parts.size > 3) PipeOp.RegexReplaceFirst(pattern, replacement)
        else PipeOp.RegexReplace(pattern, replacement)
        return base to op
    }

    /**
     * 顶层（`[]`/`()` 外、非 `<js>` 段、非 `{{…}}` 模板）按 `##` 切分，**保留空段** —— 段数决定 replace 语义。
     * 模板里的 `##` 属于里面那条规则（`{{$.tags[*]##\n##•}}` 把多条结果连成「a•b」），Legado 是先展开模板再切 `##`。
     */
    private fun splitTopHash(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text.startsWith("<js>", i, ignoreCase = true)) {
                val end = text.indexOf("</js>", i + 4, ignoreCase = true)
                i = if (end < 0) text.length else end + 5
                continue
            }
            val tpl = templateEnd(text, i)
            if (tpl > 0) {
                i = tpl
                continue
            }
            val c = text[i]
            when {
                c == '\\' -> { i += 2; continue }
                c == '[' || c == '(' -> { depth++; i++ }
                c == ']' || c == ')' -> { if (depth > 0) depth--; i++ }
                depth == 0 && text.startsWith("##", i) -> {
                    out += text.substring(start, i)
                    i += 2
                    start = i
                }
                else -> i++
            }
        }
        out += text.substring(start)
        return out
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

    /**
     * 括号感知的顶层切分：`[...]`/`(...)` 内、`{{…}}` 模板里的分隔符不切；反斜杠转义跳过。
     *
     * 模板要跳过：`{{$.recommend_count||$.booknum}}` 里的 `||` 是模板里那条规则的回退，不是整条规则的。
     * 从前从中间切开，「{{$.level}}级 {{$.recommend_count」成了一个分支，晋江书单的日期行只剩半截。
     */
    private fun splitTop(text: String, delim: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < text.length) {
            val tpl = templateEnd(text, i)
            if (tpl > 0) {
                i = tpl
                continue
            }
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

    /** 顶层（括号外、非 `<js>` 段）首次出现 token 的下标，找不到返回 -1。 */
    private fun topLevelIndexOf(text: String, token: String): Int {
        var depth = 0
        var i = 0
        while (i < text.length) {
            // <js>…</js> 段整体跳过：脚本体内出现的 token（如 @js:）不是顶层规则语法
            if (text.startsWith("<js>", i, ignoreCase = true)) {
                val end = text.indexOf("</js>", i + 4, ignoreCase = true)
                i = if (end < 0) text.length else end + 5
                continue
            }
            val tpl = templateEnd(text, i)
            if (tpl > 0) {
                i = tpl
                continue
            }
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

    /**
     * [i] 处是 `{{` 时，返回与它配对的 `}}` 之后的下标（嵌套的 `{{…}}` 一并计入）；不是模板开头、或者没配上，返回 -1
     * —— 没配上的 `{{` 照普通字符走，别把后面整条规则都吞进去。
     */
    private fun templateEnd(text: String, i: Int): Int {
        if (!text.startsWith("{{", i)) return -1
        var depth = 0
        var j = i
        while (j < text.length) {
            when {
                text.startsWith("{{", j) -> { depth++; j += 2 }
                text.startsWith("}}", j) -> {
                    depth--
                    j += 2
                    if (depth == 0) return j
                }
                else -> j++
            }
        }
        return -1
    }
}
