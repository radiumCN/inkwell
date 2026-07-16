package com.radium.inkwell.core.source

import org.jsoup.nodes.Element

/**
 * Legado「默认规则」语法的求值器（按公开的规则语法独立实现）。
 *
 * 要害在于：**索引作用于「匹配集」** —— 某一段选择器在当前元素下选出的那个扁平结果列表 ——
 * 而不是 CSS 的兄弟序号（`:eq`/`:last-of-type` 是「在各自父节点里排第几」）。两者只在所有匹配
 * 恰好同父时才等价，且无法在转换期判定。这就是把规则译成 CSS 选择器补不完窟窿的根因，
 * 也是这里改为原样求值的原因。
 *
 * 语法：
 * - 组合：`A||B` 取首个非空、`A&&B` 依次拼接、`A%%B` 交错合并
 * - 层级：`段@段@段`，逐段在上一段的结果里继续选
 * - 选择：`class.名` / `id.名` / `tag.名` / `text.所含文字` / `children` / 其余按 CSS 选择器
 * - 索引：紧跟选择器之后，作用于匹配集
 *     - `.n`：取第 n 个（负数从末尾数）
 *     - `.a:b:c`：取第 a、b、c 个（此处 `:` 分隔的是多个索引，不是区间）
 *     - `!a:b`：排除第 a、b 个
 *     - `[n, a:b, a:b:step]`：方括号写法，此处 `:` 是区间；端点可省略、可为负
 *     - `[!...]`：方括号排除；`[-1:0]`：逆序
 * - 提取：取字符串时最后一段是提取器 `text` / `textNodes` / `ownText` / `html` / `all`，
 *   其余当作属性名
 * - 前缀：`@css:` 整条按 CSS 选择器处理，最后一个 `@` 之后是提取器
 */
object LegadoSelector {

    /** 列表规则：每一段都是选择器 */
    fun elements(root: Element, rule: String): List<Element> =
        combine(rule) { part -> selectPart(root, part) }

    /** 字符串规则：最后一段是提取器 */
    fun strings(root: Element, rule: String): List<String> =
        combine(rule) { part -> extractPart(root, part) }

    // ---------- 组合 ----------

    private enum class Combine { OR, AND, MIX }

    private fun <T> combine(rule: String, eval: (String) -> List<T>): List<T> {
        val op = firstTopLevelOp(rule) ?: return eval(rule.trim())
        val token = when (op) {
            Combine.OR -> "||"
            Combine.AND -> "&&"
            Combine.MIX -> "%%"
        }
        val results = mutableListOf<List<T>>()
        for (part in splitTopLevel(rule, token)) {
            val r = eval(part)
            if (r.isEmpty()) continue
            results += r
            if (op == Combine.OR) break // 取首个非空
        }
        if (results.isEmpty()) return emptyList()
        // 交错合并以首个结果的长度为准
        if (op == Combine.MIX) {
            val out = mutableListOf<T>()
            for (i in results[0].indices) {
                for (r in results) r.getOrNull(i)?.let { out += it }
            }
            return out
        }
        return results.flatten()
    }

    /** 组合符与层级符都不该在 `[...]`/`(...)` 里被切开（属性选择器、伪类参数） */
    private fun firstTopLevelOp(s: String): Combine? {
        var depth = 0
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '[' || s[i] == '(' -> depth++
                s[i] == ']' || s[i] == ')' -> if (depth > 0) depth--
                depth == 0 && s.startsWith("||", i) -> return Combine.OR
                depth == 0 && s.startsWith("&&", i) -> return Combine.AND
                depth == 0 && s.startsWith("%%", i) -> return Combine.MIX
            }
            i++
        }
        return null
    }

    private fun splitTopLevel(s: String, token: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '[' || s[i] == '(' -> { depth++; i++ }
                s[i] == ']' || s[i] == ')' -> { if (depth > 0) depth--; i++ }
                depth == 0 && s.startsWith(token, i) -> {
                    out += s.substring(start, i)
                    i += token.length
                    start = i
                }
                else -> i++
            }
        }
        out += s.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ---------- 单条规则 ----------

    private fun isCss(part: String) = part.startsWith("@css:", ignoreCase = true)

    private fun selectPart(root: Element, part: String): List<Element> {
        if (isCss(part)) return cssSelect(root, part.substring(5).trim())
        var cur = listOf(root)
        for (seg in splitTopLevel(part, "@")) {
            cur = cur.flatMap { selectSegment(it, seg) }
            if (cur.isEmpty()) return emptyList()
        }
        return cur
    }

    private fun extractPart(root: Element, part: String): List<String> {
        if (isCss(part)) {
            val body = part.substring(5).trim()
            val at = body.lastIndexOf('@')
            // @css: 规则的提取器写在最后一个 @ 之后；没写就按 text
            return if (at < 0) extract(cssSelect(root, body), "text")
            else extract(cssSelect(root, body.substring(0, at).trim()), body.substring(at + 1))
        }
        val segs = splitTopLevel(part, "@")
        if (segs.isEmpty()) return emptyList()
        var cur = listOf(root)
        for (i in 0 until segs.size - 1) {
            cur = cur.flatMap { selectSegment(it, segs[i]) }
            if (cur.isEmpty()) return emptyList()
        }
        return extract(cur, segs.last())
    }

    private fun selectSegment(el: Element, segRaw: String): List<Element> {
        val seg = segRaw.trim()
        if (seg.isEmpty()) return listOf(el)
        val (selector, index) = splitIndex(seg)
        val base = baseElements(el, selector)
        return index?.apply(base) ?: base
    }

    /** 选择器无效时返回空集而不是抛错：书源里手写的 CSS 有的本就不合法，不该拖垮整条链路 */
    private fun cssSelect(el: Element, query: String): List<Element> =
        if (query.isEmpty()) listOf(el)
        else runCatching { el.select(query).toList() }.getOrDefault(emptyList())

    private fun baseElements(el: Element, selector: String): List<Element> {
        if (selector.isEmpty() || selector == "children") return el.children().toList()
        val dot = selector.indexOf('.')
        val head = if (dot < 0) selector else selector.substring(0, dot)
        val name = if (dot < 0) "" else selector.substring(dot + 1).trim()
        return when {
            // class 名里的 `.` 与空格都表示「同时具有这些 class」
            head == "class" && name.isNotEmpty() ->
                cssSelect(el, name.split(' ', '.').filter { it.isNotBlank() }.joinToString(".", prefix = "."))
            head == "id" && name.isNotEmpty() -> cssSelect(el, "#$name")
            head == "tag" && name.isNotEmpty() -> el.getElementsByTag(name).toList()
            head == "text" && name.isNotEmpty() -> el.getElementsContainingOwnText(name).toList()
            else -> cssSelect(el, selector)
        }
    }

    // ---------- 索引 ----------

    private class Span(val start: Int?, val end: Int?, val step: Int?)

    /** 索引筛选器；作用于匹配集。选取时按索引给出的顺序返回，故 `[-1:0]` 天然就是逆序 */
    private class IndexFilter(private val items: List<Any>, private val exclude: Boolean) {

        fun apply(base: List<Element>): List<Element> {
            val len = base.size
            if (len == 0) return base
            val picked = LinkedHashSet<Int>()
            for (item in items) {
                when (item) {
                    is Int -> normalize(item, len)?.let { picked += it }
                    is Span -> picked += expand(item, len)
                }
            }
            return if (exclude) base.filterIndexed { i, _ -> i !in picked }
            else picked.mapNotNull { base.getOrNull(it) }
        }

        /** 负数从末尾数；越界丢弃 */
        private fun normalize(i: Int, len: Int): Int? = when {
            i in 0 until len -> i
            i < 0 && -i <= len -> i + len
            else -> null
        }

        /** 区间：端点省略表示首/末，负数从末尾数，越界夹到边界；end < start 时逆序展开 */
        private fun expand(span: Span, len: Int): List<Int> {
            fun clamp(i: Int): Int = (if (i < 0) i + len else i).coerceIn(0, len - 1)
            val s = clamp(span.start ?: 0)
            val e = clamp(span.end ?: -1)
            val step = (span.step ?: 1).let { if (it == 0) 1 else kotlin.math.abs(it) }
            return if (e >= s) (s..e step step).toList() else (s downTo e step step).toList()
        }
    }

    private fun splitIndex(seg: String): Pair<String, IndexFilter?> {
        // 方括号写法：`选择器[1, 2:5:2]` / `选择器[!0,1]`
        if (seg.endsWith("]")) {
            val open = matchingOpen(seg)
            if (open >= 0) {
                val inner = seg.substring(open + 1, seg.length - 1).trim()
                val exclude = inner.startsWith("!")
                val items = parseIndexItems(if (exclude) inner.substring(1) else inner)
                // 解析不出索引说明这是 CSS 属性选择器（如 [href=x]），原样交给 CSS
                if (items != null) {
                    return seg.substring(0, open).trim() to IndexFilter(items, exclude)
                }
            }
        }
        // 阅读原写法：`选择器.-1` / `选择器!0:1:2`（此处 `:` 分隔的是多个索引，不是区间）
        var i = seg.length
        while (i > 0 && (seg[i - 1].isDigit() || seg[i - 1] == '-' || seg[i - 1] == ':')) i--
        if (i in 1 until seg.length && (seg[i - 1] == '.' || seg[i - 1] == '!')) {
            val nums = seg.substring(i).split(':').map { it.trim() }
            if (nums.all { it.toIntOrNull() != null }) {
                return seg.substring(0, i - 1).trim() to
                    IndexFilter(nums.map { it.toInt() as Any }, seg[i - 1] == '!')
            }
        }
        return seg to null
    }

    private fun matchingOpen(seg: String): Int {
        var depth = 0
        for (i in seg.length - 1 downTo 0) {
            when (seg[i]) {
                ']' -> depth++
                '[' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** 返回 null 表示这不是索引列表 */
    private fun parseIndexItems(body: String): List<Any>? {
        if (body.isBlank()) return null
        val items = mutableListOf<Any>()
        for (raw in body.split(',')) {
            val t = raw.trim()
            if (t.isEmpty()) return null
            if (t.contains(':')) {
                val parts = t.split(':')
                if (parts.size > 3) return null
                val nums = parts.map { p ->
                    val v = p.trim()
                    if (v.isEmpty()) null else (v.toIntOrNull() ?: return null)
                }
                items += Span(nums[0], nums.getOrNull(1), nums.getOrNull(2))
            } else {
                items += (t.toIntOrNull() ?: return null)
            }
        }
        return items
    }

    // ---------- 提取 ----------

    private fun extract(els: List<Element>, extractorRaw: String): List<String> =
        when (val name = extractorRaw.trim()) {
            "text" -> els.map { it.text() }.filter { it.isNotEmpty() }
            "ownText" -> els.map { it.ownText() }.filter { it.isNotEmpty() }
            // textNodes 的产物是**正文**，段落结构全靠换行与段首缩进承载，两者都不能碰：
            //
            // - 用 wholeText 而不是 text()：后者当场把换行归一化成空格。不少老站的正文既不用
            //   <p> 也不用 <br>，整章就是一个文本节点、靠原始换行分段 —— 换行一没，整章塌成一坨。
            // - trim 只剥 ASCII 空白（`it <= ' '`），**不能用 Kotlin 的 trim()**：后者按
            //   Character.isWhitespace 判断，而全角空格 U+3000 在 Java 里算空白，会被一起剥掉。
            //   段首那两个全角空格正是分段的唯一标记，剥了就再也认不出段落边界。
            //   Legado 原版用的也是 `trim { it <= ' ' }`，这里与它对齐。
            "textNodes" -> els.mapNotNull { el ->
                el.textNodes().map { node -> node.wholeText.trim { it <= ' ' } }
                    .filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n")
            }
            // html/all：整个匹配集合成一段 HTML（正文规则几乎都靠这个），html 还要去掉脚本与样式。
            // 克隆后再删，避免污染同一份文档 —— 后面的规则还要在上面求值。
            "html" -> els.map { it.clone() }
                .onEach { it.select("script, style").remove() }
                .joinToString("") { it.outerHtml() }
                .let { if (it.isEmpty()) emptyList() else listOf(it) }
            "all" -> els.joinToString("") { it.outerHtml() }
                .let { if (it.isEmpty()) emptyList() else listOf(it) }
            // 其余当属性名；属性常在上下两处导航里重复，去重。
            // （列表规则按条目逐项求值，每次 els 只含单个条目元素，去重不会跨条目丢合法重复值。）
            else -> els.map { it.attr(name) }.filter { it.isNotBlank() }.distinct()
        }
}
