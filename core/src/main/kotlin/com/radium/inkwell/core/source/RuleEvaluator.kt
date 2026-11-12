package com.radium.inkwell.core.source

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
    /** 源的公共脚本库（阅读的 jsLib）：规则、模板、地址里的脚本都能调它定义的函数 */
    val jsLib: String? = null,
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

    private val regexCache = object : LinkedHashMap<String, Regex>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?): Boolean =
            size > 256
    }
    private fun regexOf(pattern: String): Regex = synchronized(regexCache) {
        regexCache.getOrPut(pattern) { Regex(pattern) }
    }

    /**
     * 给书源正则套上和净化同一道预算关卡。
     *
     * 规则里的正则（`##广告##`、`:regex`）和净化规则一样来自用户导入的第三方书源，
     * 一样能写出灾难性回溯。而回溯既不抛异常也不理会线程中断，`withTimeout` 对它无效 ——
     * 唯一的出口是在取字符时检查预算（见 [GuardedText]）。净化侧已经这么做了，这里从前没有：
     * 一条病态正则碰上长正文就把 IO 线程永久钉死，退出页面也停不下来。
     */
    private fun guarded(text: String): CharSequence = GuardedText(text, Budget())

    /** 正则超预算 = 这条规则没匹配上。降级交由调用方，超时本身不该外泄成"书源坏了" */
    private fun <T> withRegexBudget(pattern: String, fallback: T, block: () -> T): T =
        try {
            guardedRegex(pattern, block)
        } catch (_: RegexBudgetExceeded) {
            fallback
        }

    /**
     * 一次正则操作交给 [RegexWatchdog]：真机（ICU）上 [GuardedText] 拦不住回溯，
     * 只能让调用方别陪着卡死。本进程内超时过的 pattern 直接按超时处理，不再钉死新线程。
     */
    private fun <T> guardedRegex(pattern: String, block: () -> T): T {
        if (RegexWatchdog.isPoisoned(pattern)) throw RegexBudgetExceeded()
        return RegexWatchdog.run(current = { pattern }, block = block)
    }

    private val jsonConf: Configuration =
        Configuration.builder().options(Option.SUPPRESS_EXCEPTIONS).build()

    /** 求值为节点列表（list 规则用）；每个节点携带原上下文的 baseUrl/vars */
    fun evalToNodes(node: RuleNode, ctx: EvalContext): List<EvalContext> = when (node) {
        is RuleNode.Legado -> bareJsonPath(node.rule, ctx)?.let { evalToNodes(RuleNode.JsonPath(it), ctx) }
            ?: ctx.element
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
        is RuleNode.Js -> evalJs(node.script, ctx)?.let { stringToNodes(it, ctx) } ?: emptyList()
        is RuleNode.Fallback ->
            node.options.firstNotNullOfOrNull { o ->
                evalToNodes(o, ctx).takeIf { it.isNotEmpty() }
            } ?: emptyList()
        is RuleNode.Concat -> node.parts.flatMap { evalToNodes(it, ctx) }
        is RuleNode.Interleave -> interleave(node.parts.map { evalToNodes(it, ctx) })
        // 列表规则也可能带管道：`div@html<js>过滤</js>`（Js 段）、`<js>…</js>尾规则`（Rule 段）、
        // `##广告##`（正则替换）。从前这里直接 evalToNodes(source) 把 ops 全丢了 —— bookList/
        // chapterList 写成这类形态就得空列表。改为先走字符串管道求值（ops 在里面执行），
        // 再把每个产物按 Js 分支同样的逻辑转回节点（HTML→子元素 / JSON→条目 / 其余→标量）。
        is RuleNode.Pipe -> wholeJsonPipe(node, ctx) ?: evalToStrings(node, ctx).flatMap { stringToNodes(it, ctx) }
    }

    /**
     * JSON 列表后面接脚本的管道（`$.data.list[*]<js>…</js>$.[*]`）：照 Legado 的 getElements，
     * 脚本拿到的是**整组**结果（这里是一段 JSON 数组），后面的段也作用在脚本的返回值上。
     *
     * 从前逐条喂：每一条单独过脚本、再单独跑 `$.[*]`，一个对象的十来个字段各成一条 —— 晋江书单 100 个书单
     * 变成 1049 条全是空标题的「文章」。HTML 元素列表仍逐条走（[evalToStrings] 那条路），那边的现有来源靠它。
     * 不是这种形态（没有脚本段、源头不是 JSON、一条都没取到）返回 null，照旧逐条。
     */
    private fun wholeJsonPipe(node: RuleNode.Pipe, ctx: EvalContext): List<EvalContext>? {
        if (node.ops.none { it is PipeOp.Js }) return null
        val items = evalToNodes(node.source, ctx)
        if (items.isEmpty() || items.any { it.element != null }) return null
        val whole = jsonText(items.map { it.json })
        return node.ops.fold(listOf(whole)) { acc, op -> applyOp(op, acc, ctx) }
            .filter { it.isNotEmpty() }
            .flatMap { stringToNodes(it, ctx) }
    }

    /**
     * 把一段字符串产物（JS/管道输出）转成节点列表：
     * - `<…` 当 HTML 解析，取顶层子元素（列表规则常靠 JS 生成一段 HTML 再逐项抽取）
     * - `[…` 当 JSON 数组，**展开成逐条目节点**（Legado 对 NativeArray 同样展开；否则 `$.name`
     *   之类字段规则会落在整个数组上而非各条目）
     * - `{…` 当 JSON 对象整体进 json 上下文
     * - 其余当标量文本
     */
    private fun stringToNodes(out: String, ctx: EvalContext): List<EvalContext> {
        val t = out.trim()
        return when {
            t.isEmpty() -> emptyList()
            t.startsWith("[") -> expandJsonArray(t, ctx)
            t.startsWith("{") -> listOf(ctx.copy(element = null, json = t))
            t.startsWith("<") -> Jsoup.parse(t).body().children().map { ctx.copy(element = it, json = null) }
            else -> listOf(ctx.copy(element = null, json = t))
        }
    }

    /** 把 JSON 数组文本解析后展开成逐条目节点；解析失败则整段作单个 json 上下文兜底。 */
    private fun expandJsonArray(jsonArrayText: String, ctx: EvalContext): List<EvalContext> =
        when (val parsed = runCatching { jsonConf.jsonProvider().parse(jsonArrayText) }.getOrNull()) {
            is List<*> -> parsed.filterNotNull().map { ctx.copy(element = null, json = it) }
            else -> listOf(ctx.copy(element = null, json = jsonArrayText))
        }

    /** 求值为字符串列表；空串结果被丢弃 */
    fun evalToStrings(node: RuleNode, ctx: EvalContext): List<String> = when (node) {
        is RuleNode.Legado -> {
            val resolved = withGetVars(node.rule, ctx)
            // `@get:{n}` 整段就是取值（init 里 @put 之后的 name/author 常这么写）。
            // 代入后再当选择器会去找名为「魔道修真」的标签，必然空。
            if (isBareGetRule(node.rule)) {
                if (resolved.isEmpty()) emptyList() else listOf(resolved)
            } else {
                bareJsonPath(node.rule, ctx)?.let { jsonToStrings(readJsonPath(it, ctx)) }
                    ?: ctx.element?.let { LegadoSelector.strings(it, resolved) }.orEmpty()
            }
        }
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
        is RuleNode.Interleave -> interleave(node.parts.map { evalToStrings(it, ctx) })
        is RuleNode.Pipe ->
            node.ops.fold(evalToStrings(node.source, ctx)) { acc, op -> applyOp(op, acc, ctx) }
                .filter { it.isNotEmpty() }
    }

    /**
     * 模板里的 JSONPath 规则（`{{$.recommend_count||$.booknum}}`、`{{$.tags[*]##\n##•}}`）：照 Legado 的 getString
     * 当一条完整规则求值 —— `||` / `&&` / `%%` / `##` 都认；多条结果先用换行连起来，`##` 替换作用在连好的整串上
     * （所以 `##\n##•` 能把标签连成「言情•古代」）。
     *
     * 从前把整段当一条裸 JSONPath：`$.recommend_count||$.booknum` 匹配不到任何东西，晋江书单的「12 本 345 收藏」
     * 成了「本 收藏」；多条结果也只取第一条。
     */
    private fun nestedJsonRule(body: String, ctx: EvalContext): String {
        val node = LegadoRuleAnalyzer.analyze(body)
        if (node is RuleNode.Pipe && node.ops.all { it is PipeOp.RegexReplace || it is PipeOp.RegexReplaceFirst }) {
            val joined = evalToStrings(node.source, ctx).joinToString("\n")
            if (joined.isEmpty()) return ""
            return node.ops.fold(listOf(joined)) { acc, op -> applyOp(op, acc, ctx) }.firstOrNull().orEmpty()
        }
        return evalToStrings(node, ctx).joinToString("\n")
    }

    /** `%%`：各段结果轮流取一条（a1、b1、a2、b2…），短的取完就跳过（同 Legado） */
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return out
    }

    /** 求值为单个字符串；多条结果用换行拼接，空结果返回 null */
    fun evalToString(node: RuleNode, ctx: EvalContext): String? =
        evalToStrings(node, ctx).takeIf { it.isNotEmpty() }?.joinToString("\n")

    // ---- xpath ----

    /**
     * XPath 由 JsoupXpath 求值。Legado 的 @XPath 规则直接用它，语义一致。
     * 选择器非法或不匹配时返回空 —— 书源里手写的 XPath 质量参差，不该拖垮整条链路。
     *
     * 从已有 Element 建 JXNode，不要 `outerHtml()` 再整页重解析：那是把同一棵 DOM
     * 再序列化+解析一遍，长正文上既吃内存又吃 CPU。
     */
    private fun xpathNodes(path: String, ctx: EvalContext): List<Element> {
        val el = ctx.element ?: return emptyList()
        return runCatching {
            xpathSel(el, path).mapNotNull { if (it.isElement) it.asElement() else null }
        }.getOrDefault(emptyList())
    }

    private fun xpathStrings(path: String, ctx: EvalContext): List<String> {
        val el = ctx.element ?: return emptyList()
        return runCatching {
            xpathSel(el, path)
                .map { if (it.isElement) it.asElement().text() else it.asString() }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun xpathSel(el: Element, path: String): List<org.seimicrawler.xpath.JXNode> =
        org.seimicrawler.xpath.JXNode.create(el).sel(path)

    // ---- json ----

    /**
     * 纯 JSON 上下文（列表里的一条 JSON、脚本吐出来的对象）上的裸字段名：`title`、`data.list[*]`。
     * Legado 对 JSON 内容默认按 JSONPath 求值，jayway 自己会补上 `$.`；我们默认交给 Jsoup，上下文里又没有元素，
     * 从前一律空 —— 订阅源 `"ruleTitle": "title"` 这种写法，标题、链接全空。
     * 有元素的上下文（HTML 页面）、`@` 开头的、带模板的不动。
     */
    private fun bareJsonPath(rule: String, ctx: EvalContext): String? {
        val json = ctx.json ?: return null
        // 页面一级的上下文同时带着 Jsoup 文档和原文（BookSourceEngine.pageContext）：原文本身是 JSON 才按 JSONPath 走，
        // 同 Legado 的 isJSON —— 内容是 JSON 时，不带前缀的规则一律是 JSONPath（Jayway 自己补 `$.`）。
        // 从前这一级只认 `$.` 开头的：接口源把列表写成 `data.books`、详情 init 写成 `data.book`，
        // 被当成 CSS 选择器去 HTML 里找，一个节点都没有（七猫的接口源搜索、详情全空）
        if (ctx.element != null && !(json is String && looksLikeJson(json))) return null
        if (rule.isBlank() || rule.startsWith("@") || rule.contains("{{")) return null
        return "$." + rule.trim()
    }

    /** 同 Legado 的 `String.isJson()`：去掉首尾空白后由 `{…}` 或 `[…]` 包住。只看两头，不为判断复制几 MB 的响应体 */
    private fun looksLikeJson(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return false
        val last = text.indexOfLast { !it.isWhitespace() }
        return (text[first] == '{' && text[last] == '}') || (text[first] == '[' && text[last] == ']')
    }

    private fun readJsonPath(path: String, ctx: EvalContext): Any? {
        val doc = ctx.json ?: return null
        val parsed: Any = if (doc is String) {
            parseJsonCached(doc) ?: return null // 非 JSON 内容视为不匹配
        } else {
            doc
        }
        return try {
            JsonPath.using(jsonConf).parse(parsed).read<Any?>(path)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 最近解析过的 JSON 文本 → 解析结果，按**同一个 String 实例**认。
     *
     * 一页的上下文里 json 就是响应体那一个串（[BookSourceEngine] 的 pageContext）。详情接口常把整本目录
     * 一起返回（0.5–2MB），书名、作者、简介、目录地址……从前每个字段都把整份 JSON 重新解析一遍；
     * 目录页、正文页也至少两遍（列表规则 + 下一页规则）。按实例认而不是按内容：比内容就得再扫一遍全文，
     * 而不同页面的串不会是同一个实例。解析失败的也记下（[NOT_JSON]），免得每个字段再失败一次。
     *
     * 键是弱引用（WeakHashMap，命中时先比的是 ==）：页面上下文一丢，响应体那个串没人引用了，
     * 这条连同解析树一起被回收 —— 求值器是单例，从前封顶 [PARSED_JSON_KEEP] 条的强引用会把最后几页的
     * 解析树（原文的好几倍大）一直攥到进程结束。解析树里没有指回原文的引用，拴不住键。
     * 条数照样封顶，防一条抓取链路里同时活着太多页。跨线程共享：解析结果只读，没人改它。
     */
    private val parsedJson = java.util.WeakHashMap<String, Any>()

    private fun parseJsonCached(text: String): Any? {
        // 小串（列表项、正则抠出来的片段）每次都是新实例，进缓存只会把整页那份挤出去；它们解析起来也快
        if (text.length < PARSED_JSON_MIN_CHARS) {
            return runCatching { jsonConf.jsonProvider().parse(text) }.getOrNull()
        }
        val hit = synchronized(parsedJson) { parsedJson[text] }
        val parsed = hit ?: (runCatching { jsonConf.jsonProvider().parse(text) }.getOrNull() ?: NOT_JSON).also {
            synchronized(parsedJson) {
                if (parsedJson.size >= PARSED_JSON_KEEP) parsedJson.clear()
                parsedJson[text] = it
            }
        }
        return parsed.takeIf { it !== NOT_JSON }
    }

    private fun jsonToStrings(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.filterNotNull().map { scalarString(it) }.filter { it.isNotEmpty() }
        else -> listOf(scalarString(value)).filter { it.isNotEmpty() }
    }

    /**
     * jayway 读出来的对象/数组是 LinkedHashMap / List，`toString()` 得到的是 `{id=1, name=x}` ——
     * 不是 JSON。`$.data.list[*]` 的条目交给 `<js>JSON.parse(result).title</js>`、`:regex`、`$.obj`
     * 取整个对象，拿到的都是这种半截东西。对象/数组一律序列化成真正的 JSON 文本。
     */
    private fun scalarString(v: Any): String = when (v) {
        is String -> v
        is Map<*, *>, is List<*> -> jsonText(v)
        else -> v.toString()
    }

    // ---- regex ----

    private fun regexExtract(pattern: String, ctx: EvalContext): List<String> {
        val target = contextText(ctx)
        if (target.isEmpty()) return emptyList()
        // 书源里写坏的正则（Legado 认 ICU 语法，JVM 不一定认）编译会抛 PatternSyntaxException。
        // 下面的预算关卡只接 RegexBudgetExceeded，不兜这一种 —— 于是它会绕过分析器
        // 「永不抛错、降级为空」的承诺，直接把整章正文打成加载失败。按「未匹配」处理。
        val regex = runCatching { regexOf(pattern) }.getOrElse { return emptyList() }
        return withRegexBudget(pattern, emptyList()) {
            regex.findAll(guarded(target))
                .map { m -> if (m.groupValues.size > 1) m.groupValues[1] else m.value }
                .filter { it.isNotEmpty() }
                .toList()
        }
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
        // 入口判据用 contains("@js:") 而非仅 trimStart().startsWith：`头@js:脚本` 的中缀写法
        // （如 `https://a.com/s@js:result+'&p=1'`）同样是 JS 地址，Legado 里 @js: 可出现在任意位置。
        if (!url.contains("<js>", ignoreCase = true) && !url.contains("@js:", ignoreCase = true)) {
            return null
        }
        var result = url
        var start = 0
        var found = false
        for (m in URL_JS.findAll(url)) {
            found = true
            if (m.range.first > start) {
                // 头片段（两段 JS 之间或首段 JS 之前的文本）：把其中的 @result 替换成当前结果后，
                // 整体**取代** result，作为下一段脚本的输入 —— 这是 Legado analyzeJs 的语义。
                // 从前这里是 `result += 头片段`，等于把整条原始 URL 连同头一起喂给脚本再拼接，
                // 于是 `https://a.com/s<js>result</js>` 会把整串塞回去，得到一堆垃圾地址。
                val head = url.substring(start, m.range.first).trim()
                if (head.isNotEmpty()) result = head.replace("@result", result)
            }
            val script = m.groupValues[1].ifEmpty { m.groupValues[2] }
            result = runJs(script, ctx, result)
                ?: throw SourceException("地址脚本执行失败")
            start = m.range.last + 1
        }
        if (!found) return null
        if (url.length > start) {
            val tail = url.substring(start).trim()
            if (tail.isNotEmpty()) result = tail.replace("@result", result)
        }
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
        if (body.startsWith("$.") || body.startsWith("$[")) return nestedJsonRule(body, ctx)
        if (body.startsWith("//")) return "" // XPath，暂不支持
        if (body.startsWith("@")) {
            return evalToStrings(RuleNode.Legado(body), ctx).firstOrNull().orEmpty()
        }
        // baseUrl 先于 vars 查：vars 里那份是引擎在 varsOf 塞进来的**站点根**，而 Legado
        // （与本文件 KDoc）的 {{baseUrl}} 是**当前页地址**（重定向后的 finalUrl）。从前 vars 先命中，
        // 详情/目录/正文阶段一律解成站点根 —— 与同一条规则 JS 侧拿到的 baseUrl 都对不上。
        // 搜索/发现的 URL 阶段两者本就相同（urlContext 的 baseUrl 就是 source.baseUrl），不受影响。
        if (body == "baseUrl") return ctx.baseUrl
        // 已知变量 / 整数算术（{{(page-1)*20}}）
        ctx.vars[body]?.let { return it }
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

    private fun isBareGetRule(rule: String): Boolean =
        GET_VAR.matchEntire(rule.trim()) != null

    /**
     * `@put:{"k":"规则"}`：每条规则在当前内容上求值，结果存进变量表。
     *
     * spec 按宽松 JSON 解析（键不加引号、单引号都认，见 [parseLenientJsonObject]）：Legado 文档里的写法就是
     * `@put:{bid:"//book/@bid"}`，书源里单引号也常见。
     * 从前按严格 JSON 解析，这两种都解析失败、被静默丢掉，后面的 `@get:{bid}` 永远取空。
     */
    private fun applyPut(spec: String, ctx: EvalContext) {
        val obj = parseLenientJsonObject(spec) ?: return
        for ((key, value) in obj) {
            val ruleText = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val v = runCatching {
                evalToStrings(LegadoRuleAnalyzer.analyze(ruleText), ctx).firstOrNull()
            }.getOrNull().orEmpty()
            ctx.js.scriptVars[key] = v
        }
    }

    private fun varValue(name: String, ctx: EvalContext): String =
        if (name == "baseUrl") ctx.baseUrl else ctx.vars[name].orEmpty()

    /**
     * 规则当前作用对象的文本形式：`:regex` 匹配它，页面级 `<js>` / `@js:` 的 `result` 也是它。
     *
     * **整页**（以及把上一步产物重新当页面解析的子上下文）同时带着 Jsoup 文档和**原文**，
     * 这时必须给原文 —— Legado 的 `result` 就是响应体字符串本身。从前给的是 `outerHtml()`，
     * 拿到的是 Jsoup 重新序列化过的东西：JSON 接口被包进 `<html><body>`，`JSON.parse(result)`
     * 直接失败、目录为空；文本里的 `& < >` 变成实体，正则抠出来的书名和链接带着 `&amp;`；
     * 引号被规范化、标签被重新缩进，照着网页源码写的正则也对不上。
     *
     * 元素上下文（列表项、选择器结果）没有原文可给，取 outerHtml（可匹配属性与脚本内容）；
     * 其余取 JSON/文本值 —— JSON 对象/数组给序列化后的 JSON 文本，不是 Map 的 toString（见 [scalarString]）。
     */
    private fun contextText(ctx: EvalContext): String {
        val raw = ctx.json as? String
        if (raw != null && ctx.element is Document) return raw
        return ctx.element?.outerHtml() ?: raw ?: ctx.json?.let { scalarString(it) } ?: ""
    }

    // ---- 管道 ----

    private fun applyOp(op: PipeOp, list: List<String>, ctx: EvalContext): List<String> = when (op) {
        is PipeOp.RegexReplace -> list.map {
            // 替换串里的 `$` 可能是非法组引用（Legado 书源里屡见），别让它打死整条链路。
            // 超预算（灾难性回溯）一并落在这里：保留原文，等同于这条替换没生效
            runCatching {
                guardedRegex(op.pattern) { regexOf(op.pattern).replace(guarded(it), op.replacement) }
            }.getOrDefault(it)
        }
        is PipeOp.RegexReplaceFirst -> list.map { s ->
            // Legado replaceFirst：取首个匹配、在其匹配区间内替换；未命中则清空（抽取语义）
            // 编译也放进 runCatching：坏正则同样按「未命中」清空，别抛出去
            runCatching {
                val re = regexOf(op.pattern)
                guardedRegex(op.pattern) {
                    re.find(guarded(s))?.let { re.replaceFirst(it.value, op.replacement) } ?: ""
                }
            }.getOrDefault("")
        }
        is PipeOp.Put -> {
            applyPut(op.spec, ctx)
            list // 只有副作用
        }
        is PipeOp.Rule -> {
            // 上一步的产物是一段新内容（HTML 或 JSON），在它上面重新求值
            val node = runCatching { LegadoRuleAnalyzer.analyze(op.rule) }.getOrNull()
            if (node == null) list
            else list.flatMap { s ->
                val sub = ctx.copy(element = Jsoup.parse(s, ctx.baseUrl), json = s)
                runCatching { evalToStrings(node, sub) }.getOrDefault(emptyList())
            }
        }
        is PipeOp.Js -> list.mapNotNull { s -> runJs(scriptOf(op.script, ctx), ctx, input = s) }
    }

    /**
     * 脚本里的 `{{…}}` 先在当前内容上展开，再跑脚本（Legado 的 SourceRule 对脚本段一样拆 `{{}}`）：
     * `<js>"…/getDetail?listid={{$.listid}}"+token</js>` 的 `{{$.listid}}` 是这一条的值。
     * 从前原样留着，链接里就是一串字面的 `{{$.listid}}`，点开必然打不开。
     */
    private fun scriptOf(script: String, ctx: EvalContext): String =
        if (script.contains("{{")) expandTemplate(script, ctx) else script

    // ---- js ----

    /** 原子 js：result = 上下文文本（页面/元素/JSON） */
    private fun evalJs(script: String, ctx: EvalContext): String? =
        runJs(scriptOf(script, ctx), ctx, input = contextText(ctx))

    /**
     * 绑定书源脚本可见的全部上下文：变量（result/src/baseUrl/key/page）+ 桥对象
     * （java/cookie/cache/source/book/chapter）。Rhino 会把这些 Kotlin 对象反射成 JS 对象。
     *
     * 脚本异常按「不匹配」降级为空结果 —— 书源里的脚本质量参差，一处报错不该打死整条链路；
     * 未注入引擎则明确报不支持。
     */
    private fun runJs(script: String, ctx: EvalContext, input: String): String? {
        val runtime = scriptRuntime
            ?: throw UnsupportedRuleException("该来源需要 JS 引擎，当前版本不支持")
        val store = sourceStores.getOrPut(ctx.js.sourceKey) { ConcurrentHashMap() }
        val java = com.radium.inkwell.core.source.js.JavaBridge(
            jsHttp, jsCache, ctx.js.scriptVars, query = ruleQuery(ctx),
        )
        return try {
            runtime.eval(
                script,
                mapOf(
                    "result" to input,
                    "src" to input,
                    "baseUrl" to ctx.baseUrl,
                    "key" to (ctx.vars["keyword"] ?: ""),
                    "page" to (ctx.vars["page"]?.toIntOrNull() ?: 1),
                    "title" to ctx.js.chapter.title,
                    "java" to java,
                    "cookie" to com.radium.inkwell.core.source.js.CookieBridge(jsHttp),
                    "cache" to jsCache,
                    "source" to com.radium.inkwell.core.source.js.SourceBridge(ctx.js.sourceKey, store),
                    "book" to ctx.js.book,
                    "chapter" to ctx.js.chapter,
                ),
                jsLib = ctx.js.jsLib,
            )
        } catch (e: Exception) {
            // 取消不是「脚本报错」：吞掉的话，被取消的那次调用还会带着空结果把剩下的条目一个个求值完
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /** `java.getString(rule)`：在当前页上下文上再跑一条规则。 */
    private fun ruleQuery(ctx: EvalContext) = object : com.radium.inkwell.core.source.js.JsRuleQuery {
        override fun getString(rule: String): String =
            runCatching { evalToString(LegadoRuleAnalyzer.analyze(rule), ctx) }.getOrNull().orEmpty()

        override fun getStringList(rule: String): Array<String> =
            runCatching { evalToStrings(LegadoRuleAnalyzer.analyze(rule), ctx).toTypedArray() }
                .getOrDefault(emptyArray())

        override fun getElement(rule: String): com.radium.inkwell.core.source.js.JsElement? =
            getElements(rule).firstOrNull()

        override fun getElements(rule: String): Array<com.radium.inkwell.core.source.js.JsElement> =
            runCatching {
                evalToNodes(LegadoRuleAnalyzer.analyze(rule), ctx)
                    .mapNotNull { it.element?.let { el -> com.radium.inkwell.core.source.js.JsElement(el) } }
                    .toTypedArray()
            }.getOrDefault(emptyArray())
    }
}

// ---- JSON 工具 ----

/** `@put` spec、地址选项用：宽松模式接受不加引号的键（Legado 文档写法） */
private val LENIENT_JSON = kotlinx.serialization.json.Json { isLenient = true }

/** [RuleEvaluator] 的 JSON 解析缓存里「这串不是 JSON」的占位 */
private val NOT_JSON = Any()

/** 同时留几页的解析结果：一条抓取链路里交替求值的通常就是列表页和它的子上下文 */
private const val PARSED_JSON_KEEP = 4

/** 多长的 JSON 文本才进解析缓存 */
private const val PARSED_JSON_MIN_CHARS = 4_096

/**
 * 书源里手写的 JSON 对象（`@put:{…}`、地址的 `,{…}` 选项）：键可以不加引号，字符串可以用单引号 ——
 * Legado 用 GSON 的 lenient 模式解析，这些都认。解析不成对象返回 null。
 *
 * 不能「先按原文解析、失败再把单引号换成双引号」：kotlinx 的 lenient 模式会把 `'bid'` 当成
 * **连引号一起**的裸字符串照单全收，解析「成功」了，键却是 `'bid'`，后面按 `bid` 取永远是空 ——
 * `,{'charset':'gbk'}` 的编码、`@put:{'bid':'…'}` 的变量就是这么无声无息丢掉的。所以先规整引号再解析。
 */
internal fun parseLenientJsonObject(text: String): kotlinx.serialization.json.JsonObject? =
    runCatching { LENIENT_JSON.parseToJsonElement(singleToDoubleQuotes(text)) as? kotlinx.serialization.json.JsonObject }
        .getOrNull()
        ?: runCatching { LENIENT_JSON.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()

/**
 * 单引号字符串 → JSON 双引号字符串。双引号串原样保留（里面的 `'` 是内容）；单引号串里的 `"` 转义、
 * `\'` 还原成 `'`。比裸 `replace('\'', '"')` 多认一种常见写法：`{'body':'{"a":1}'}` 这种单引号包着 JSON 的。
 */
private fun singleToDoubleQuotes(text: String): String {
    if ('\'' !in text) return text
    val sb = StringBuilder(text.length + 8)
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '"' -> {
                val start = i++
                while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                i = minOf(i + 1, text.length)
                sb.append(text, start, i)
            }
            '\'' -> {
                sb.append('"')
                i++
                while (i < text.length && text[i] != '\'') {
                    val c = text[i]
                    when {
                        c == '\\' && i + 1 < text.length -> {
                            if (text[i + 1] == '\'') sb.append('\'') else sb.append(c).append(text[i + 1])
                            i += 2
                            continue
                        }
                        c == '"' -> sb.append("\\\"")
                        else -> sb.append(c)
                    }
                    i++
                }
                sb.append('"')
                i++
            }
            else -> sb.append(text[i++])
        }
    }
    return sb.toString()
}

/** jayway 解析出的 Map/List/标量 → JSON 文本（键序保持；数字、布尔原样，其余按字符串） */
private fun jsonText(v: Any): String = toJsonElement(v).toString()

private fun toJsonElement(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is Map<*, *> -> kotlinx.serialization.json.JsonObject(
        v.entries.associate { (k, x) -> k.toString() to toJsonElement(x) }
    )
    is List<*> -> kotlinx.serialization.json.JsonArray(v.map { toJsonElement(it) })
    is Number -> kotlinx.serialization.json.JsonPrimitive(v)
    is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
    else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
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
    val substituted = ARITH_IDENT.replace(expr) { m ->
        vars[m.value] ?: run { unknownVar = true; "" }
    }
    if (unknownVar || !substituted.matches(ARITH_EXPR)) return null
    return try {
        ArithmeticParser(substituted).parse()?.toString()
    } catch (_: Exception) {
        null
    }
}

// 每个非变量的 {{…}} 都要走一遍 evalArithmetic（常是每个条目一次），别在函数里现编
private val ARITH_IDENT = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
private val ARITH_EXPR = Regex("[0-9+\\-*/()\\s]+")

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
