package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.parsePage
import java.io.IOException
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.Elements

/*
 * 阅读来源脚本里直接用 Jsoup 的写法（`org.jsoup.Jsoup.parse(html).select("p").text()`）的**替身**。
 *
 * 阅读给脚本开了整个 JVM，来源作者顺手就拿 Jsoup 解析 ajax 回来的页面、改写正文、逐个翻译文字节点 ——
 * 两份书源合集里九十多个、订阅源合集里七十多个这么写（翻译类学术订阅源共用的那份脚本库就是满篇 Jsoup）。
 * 我们的沙箱只放行桥所在的包（RhinoScriptRuntime.BRIDGE_ONLY），`org` 根本不存在，这些规则一律落空。
 *
 * 同 JdkCompat 的取舍：**不把真的 Jsoup 交给脚本**。Jsoup 的对象能一路摸到 `Connection`（自己发请求、绕过私网防护）、
 * `java.util` 的集合和迭代器。这里照 Jsoup 的方法名写一层，真的节点攥在私有字段里：
 * - 节点（Document / Element / TextNode）一律是 [JsElement]，方法照 Element 的，用在文字节点上的给文字节点的语义
 *   （`text()`、`getWholeText()`），用不上的给空 —— 阅读里脚本拿到的就是这几样，不分；
 * - 一组节点是 [JsElements]：方法照 Jsoup 的 Elements；交给脚本时以 Array.prototype 为原型（[BridgeWrapFactory]），
 *   `els[0]`、`els.length`、`for…in`、`map` / `forEach` / `filter` 都能用；
 * - `Jsoup.connect(url)` 是 [JsConnection]，经来源脚本同一个网络出口（JsHttp）发出去：私网防护、取消、超时都在。
 */

/**
 * 交给脚本的「像数组的列表」：[BridgeWrapFactory] 把它包成以 Array.prototype 为原型的对象，下标、length、
 * 数组方法都能用（阅读那边脚本拿到的是 Java 的 List，Rhino 认下标和 length）
 */
interface JsArrayLike {
    fun size(): Int

    fun get(index: Int): Any?
}

/**
 * 一个 Jsoup 节点。名字沿用从前 `java.getElement` 交出去的那个类：现在 Jsoup 的节点都是它。
 * 改动节点（`text(…)`、`attr(k, v)`、`remove()`）改的是真的那棵树：同一份文档上后面的读取看得到，同阅读。
 */
class JsElement internal constructor(internal val node: Node) {

    /** 是元素才有；文字节点、注释是 null */
    internal val element: Element? get() = node as? Element

    // ---- 选取 ----

    fun select(css: String): JsElements = JsElements(element?.select(css).orEmpty())

    fun selectFirst(css: String): JsElement? = element?.selectFirst(css)?.let(::JsElement)

    fun `is`(css: String): Boolean = element?.`is`(css) == true

    fun closest(css: String): JsElement? = element?.closest(css)?.let(::JsElement)

    fun getAllElements(): JsElements = JsElements(element?.allElements.orEmpty())

    fun getElementById(id: String): JsElement? = element?.getElementById(id)?.let(::JsElement)

    fun getElementsByTag(tag: String): JsElements = JsElements(element?.getElementsByTag(tag).orEmpty())

    fun getElementsByClass(name: String): JsElements = JsElements(element?.getElementsByClass(name).orEmpty())

    fun getElementsByAttribute(key: String): JsElements = JsElements(element?.getElementsByAttribute(key).orEmpty())

    fun getElementsByAttributeValue(key: String, value: String): JsElements =
        JsElements(element?.getElementsByAttributeValue(key, value).orEmpty())

    // ---- 文字与 HTML ----

    fun text(): String = when (val n = node) {
        is Element -> n.text()
        is TextNode -> n.text()
        is DataNode -> n.wholeData
        else -> ""
    }

    fun text(value: String): JsElement {
        when (val n = node) {
            is Element -> n.text(value)
            is TextNode -> n.text(value)
            is DataNode -> n.setWholeData(value)
        }
        return this
    }

    fun ownText(): String = element?.ownText() ?: text()

    fun wholeText(): String = when (val n = node) {
        is Element -> n.wholeText()
        is TextNode -> n.wholeText
        else -> text()
    }

    fun getWholeText(): String = wholeText()

    fun isBlank(): Boolean = (node as? TextNode)?.isBlank ?: text().isBlank()

    fun hasText(): Boolean = element?.hasText() ?: text().isNotBlank()

    fun html(): String = element?.html() ?: node.outerHtml()

    fun html(value: String): JsElement {
        element?.html(value)
        return this
    }

    fun outerHtml(): String = node.outerHtml()

    fun data(): String = element?.data() ?: (node as? DataNode)?.wholeData.orEmpty()

    fun `val`(): String = element?.`val`().orEmpty()

    fun `val`(value: String): JsElement {
        element?.`val`(value)
        return this
    }

    // ---- 属性 ----

    fun attr(key: String): String = node.attr(key)

    fun attr(key: String, value: String): JsElement {
        node.attr(key, value)
        return this
    }

    fun hasAttr(key: String): Boolean = node.hasAttr(key)

    fun removeAttr(key: String): JsElement {
        node.removeAttr(key)
        return this
    }

    fun absUrl(key: String): String = node.absUrl(key)

    fun attributes(): JsAttributes = JsAttributes(node.attributes())

    fun id(): String = element?.id().orEmpty()

    fun className(): String = element?.className().orEmpty()

    fun hasClass(name: String): Boolean = element?.hasClass(name) == true

    fun addClass(name: String): JsElement {
        element?.addClass(name)
        return this
    }

    fun removeClass(name: String): JsElement {
        element?.removeClass(name)
        return this
    }

    fun toggleClass(name: String): JsElement {
        element?.toggleClass(name)
        return this
    }

    fun tagName(): String = element?.tagName() ?: node.nodeName()

    fun nodeName(): String = node.nodeName()

    fun normalName(): String = element?.normalName() ?: node.nodeName()

    // ---- 上下左右 ----

    fun parent(): JsElement? = node.parent()?.let(::JsElement)

    fun hasParent(): Boolean = node.hasParent()

    fun parents(): JsElements = JsElements(element?.parents() ?: (node.parent() as? Element)?.let { listOf(it) + it.parents() }.orEmpty())

    fun children(): JsElements = JsElements(element?.children().orEmpty())

    fun child(index: Int): JsElement? = element?.children()?.getOrNull(index)?.let(::JsElement)

    fun childrenSize(): Int = element?.childrenSize() ?: 0

    fun childNodes(): JsElements = JsElements(node.childNodes())

    fun childNodeSize(): Int = node.childNodeSize()

    fun textNodes(): JsElements = JsElements(element?.textNodes().orEmpty())

    fun dataNodes(): JsElements = JsElements(element?.dataNodes().orEmpty())

    fun firstElementChild(): JsElement? = element?.firstElementChild()?.let(::JsElement)

    fun lastElementChild(): JsElement? = element?.lastElementChild()?.let(::JsElement)

    fun nextElementSibling(): JsElement? = element?.nextElementSibling()?.let(::JsElement)

    fun previousElementSibling(): JsElement? = element?.previousElementSibling()?.let(::JsElement)

    fun firstElementSibling(): JsElement? = element?.firstElementSibling()?.let(::JsElement)

    fun lastElementSibling(): JsElement? = element?.lastElementSibling()?.let(::JsElement)

    fun siblingElements(): JsElements = JsElements(element?.siblingElements().orEmpty())

    fun elementSiblingIndex(): Int = element?.elementSiblingIndex() ?: 0

    fun siblingIndex(): Int = node.siblingIndex()

    fun nextSibling(): JsElement? = node.nextSibling()?.let(::JsElement)

    fun previousSibling(): JsElement? = node.previousSibling()?.let(::JsElement)

    fun ownerDocument(): JsElement? = node.ownerDocument()?.let(::JsElement)

    fun root(): JsElement = JsElement(node.root())

    // 文档上的：不是文档的取它所在的文档（Jsoup 只有 Document 有这几个）

    fun body(): JsElement? = doc()?.body()?.let(::JsElement)

    fun head(): JsElement? = doc()?.head()?.let(::JsElement)

    fun title(): String = doc()?.title().orEmpty()

    fun location(): String = doc()?.location().orEmpty()

    fun createElement(tag: String): JsElement = JsElement(doc()?.createElement(tag) ?: Element(tag))

    private fun doc(): Document? = node as? Document ?: node.ownerDocument()

    // ---- 改动 ----

    fun append(html: String): JsElement {
        element?.append(html)
        return this
    }

    fun prepend(html: String): JsElement {
        element?.prepend(html)
        return this
    }

    fun appendText(text: String): JsElement {
        element?.appendText(text)
        return this
    }

    fun prependText(text: String): JsElement {
        element?.prependText(text)
        return this
    }

    fun appendChild(child: JsElement): JsElement {
        element?.appendChild(child.node)
        return this
    }

    fun prependChild(child: JsElement): JsElement {
        element?.prependChild(child.node)
        return this
    }

    fun appendElement(tag: String): JsElement? = element?.appendElement(tag)?.let(::JsElement)

    fun prependElement(tag: String): JsElement? = element?.prependElement(tag)?.let(::JsElement)

    fun after(html: String): JsElement {
        node.after(html)
        return this
    }

    fun after(other: JsElement): JsElement {
        node.after(other.node)
        return this
    }

    fun before(html: String): JsElement {
        node.before(html)
        return this
    }

    fun before(other: JsElement): JsElement {
        node.before(other.node)
        return this
    }

    fun wrap(html: String): JsElement {
        node.wrap(html)
        return this
    }

    fun unwrap(): JsElement? = node.unwrap()?.let(::JsElement)

    fun replaceWith(other: JsElement) {
        node.replaceWith(other.node)
    }

    fun remove() {
        node.remove()
    }

    fun empty(): JsElement {
        element?.empty()
        return this
    }

    fun clone(): JsElement = JsElement(node.clone())

    /** 同 Jsoup：节点的 HTML（文字节点是转义过的文字）。脚本 `String(el)`、拼字符串拿到的就是它 */
    override fun toString(): String = node.outerHtml()
}

/**
 * 一组节点（Jsoup 的 Elements，`textNodes()` / `childNodes()` 给的那种也是它）。
 * 全是元素时方法直接交给 Jsoup 的 Elements，语义一字不差；混着文字节点时逐个算。
 */
class JsElements internal constructor(private val nodes: List<Node>) : JsArrayLike {

    private val elements: Elements? by lazy(LazyThreadSafetyMode.NONE) {
        if (nodes.all { it is Element }) Elements(nodes.map { it as Element }) else null
    }

    private fun wrapped(): List<JsElement> = nodes.map(::JsElement)

    override fun size(): Int = nodes.size

    override fun get(index: Int): JsElement = JsElement(nodes[index])

    fun isEmpty(): Boolean = nodes.isEmpty()

    fun first(): JsElement? = nodes.firstOrNull()?.let(::JsElement)

    fun last(): JsElement? = nodes.lastOrNull()?.let(::JsElement)

    fun eq(index: Int): JsElements = JsElements(listOfNotNull(nodes.getOrNull(index)))

    fun subList(from: Int, to: Int): JsElements = JsElements(nodes.subList(from, to))

    /** Rhino 1.9 的 Java 数组以 Array.prototype 为原型：`toArray().map(…)` 照常 */
    fun toArray(): Array<JsElement> = wrapped().toTypedArray()

    fun iterator(): JsIterator<Node> = JsIterator(nodes.iterator(), ::JsElement)

    // ---- 读 ----

    fun text(): String = elements?.text() ?: wrapped().joinToString(" ") { it.text() }

    fun eachText(): Array<String> = (elements?.eachText() ?: wrapped().map { it.text() }.filter { it.isNotEmpty() }).toTypedArray()

    fun html(): String = elements?.html() ?: wrapped().joinToString("\n") { it.html() }

    fun outerHtml(): String = elements?.outerHtml() ?: wrapped().joinToString("\n") { it.outerHtml() }

    fun attr(key: String): String = elements?.attr(key) ?: nodes.firstOrNull { it.hasAttr(key) }?.attr(key).orEmpty()

    fun eachAttr(key: String): Array<String> =
        (elements?.eachAttr(key) ?: nodes.filter { it.hasAttr(key) }.map { it.attr(key) }).toTypedArray()

    fun hasAttr(key: String): Boolean = nodes.any { it.hasAttr(key) }

    fun hasClass(name: String): Boolean = elements?.hasClass(name) ?: false

    fun hasText(): Boolean = wrapped().any { it.hasText() }

    fun `is`(css: String): Boolean = elements?.`is`(css) ?: false

    fun `val`(): String = elements?.`val`().orEmpty()

    // ---- 再选 ----

    fun select(css: String): JsElements = JsElements(elements?.select(css) ?: onlyElements().select(css))

    fun not(css: String): JsElements = JsElements(elements?.not(css) ?: onlyElements().not(css))

    fun parents(): JsElements = JsElements(onlyElements().parents())

    fun next(): JsElements = JsElements(onlyElements().next())

    fun next(css: String): JsElements = JsElements(onlyElements().next(css))

    fun nextAll(): JsElements = JsElements(onlyElements().nextAll())

    fun prev(): JsElements = JsElements(onlyElements().prev())

    fun prev(css: String): JsElements = JsElements(onlyElements().prev(css))

    fun prevAll(): JsElements = JsElements(onlyElements().prevAll())

    fun textNodes(): JsElements = JsElements(onlyElements().flatMap { it.textNodes() })

    fun children(): JsElements = JsElements(onlyElements().flatMap { it.children() })

    private fun onlyElements(): Elements = elements ?: Elements(nodes.filterIsInstance<Element>())

    // ---- 改动（同 Jsoup：作用在每一个上，返回这一组）----

    fun attr(key: String, value: String): JsElements = each { it.attr(key, value) }

    fun removeAttr(key: String): JsElements = each { it.removeAttr(key) }

    fun addClass(name: String): JsElements = each { it.addClass(name) }

    fun removeClass(name: String): JsElements = each { it.removeClass(name) }

    fun toggleClass(name: String): JsElements = each { it.toggleClass(name) }

    fun html(value: String): JsElements = each { it.html(value) }

    fun `val`(value: String): JsElements = each { it.`val`(value) }

    fun append(html: String): JsElements = each { it.append(html) }

    fun prepend(html: String): JsElements = each { it.prepend(html) }

    fun before(html: String): JsElements = each { it.before(html) }

    fun after(html: String): JsElements = each { it.after(html) }

    fun wrap(html: String): JsElements = each { it.wrap(html) }

    fun unwrap(): JsElements = each { it.unwrap() }

    fun empty(): JsElements = each { it.empty() }

    fun remove(): JsElements = each { it.remove() }

    private inline fun each(action: (JsElement) -> Unit): JsElements {
        wrapped().forEach(action)
        return this
    }

    /** 同 Jsoup 的 Elements：各自的 HTML，换行连起来 */
    override fun toString(): String = outerHtml()
}

/** `el.attributes()`：脚本拿它遍历、删属性（翻译脚本库清理图片属性：`attrs.iterator()` + `remove()`） */
class JsAttributes internal constructor(private val attrs: org.jsoup.nodes.Attributes) {
    fun size(): Int = attrs.size()

    fun get(key: String): String = attrs.get(key)

    fun hasKey(key: String): Boolean = attrs.hasKey(key)

    fun put(key: String, value: String): JsAttributes {
        attrs.put(key, value)
        return this
    }

    fun remove(key: String) {
        attrs.remove(key)
    }

    fun asList(): Array<JsAttribute> = attrs.asList().map(::JsAttribute).toTypedArray()

    fun iterator(): JsIterator<org.jsoup.nodes.Attribute> = JsIterator(attrs.iterator(), ::JsAttribute)

    fun html(): String = attrs.html()

    override fun toString(): String = attrs.html()
}

class JsAttribute internal constructor(private val attr: org.jsoup.nodes.Attribute) {
    fun getKey(): String = attr.key

    fun getValue(): String = attr.value

    fun setValue(value: String): String = attr.setValue(value)

    fun html(): String = attr.html()

    override fun toString(): String = attr.html()
}

/** Java 式的迭代器（`hasNext()` / `next()` / `remove()`）；交出去的元素先经 [wrap] 换成替身，`remove()` 删的是底下那个 */
class JsIterator<T> internal constructor(private val it: Iterator<T>, private val wrap: (T) -> Any?) {
    fun hasNext(): Boolean = it.hasNext()

    fun next(): Any? = wrap(it.next())

    fun remove() {
        (it as? MutableIterator<T>)?.remove() ?: throw UnsupportedOperationException("remove")
    }
}

/**
 * `Jsoup.connect(url)`：照 Jsoup 的 Connection 记下请求，`get()` / `post()` / `execute()` 时经来源脚本的网络出口发出去
 * （[JsHttp]：私网防护、外层取消、超时都在）。`timeout` / `ignoreContentType` / `maxBodySize` 记了也不用：超时、体积上限用我们自己的。
 * 同 Jsoup：状态码 ≥ 400 抛错（`ignoreHttpErrors(true)` 例外），脚本自己 try 着
 */
class JsConnection internal constructor(private val http: JsHttp?, private var url: String) {
    private val headers = LinkedHashMap<String, String>()
    private val cookies = LinkedHashMap<String, String>()
    private val data = ArrayList<Pair<String, String>>()
    private var body: String? = null
    private var method = "GET"
    private var followRedirects = true
    private var ignoreHttpErrors = false
    private var postCharset = "UTF-8"

    fun url(value: String): JsConnection = apply { url = value }

    fun header(key: String, value: String): JsConnection = apply { headers[key] = value }

    fun headers(map: Map<*, *>?): JsConnection = apply { map.pairs().forEach { (k, v) -> headers[k] = v } }

    fun userAgent(value: String): JsConnection = header("User-Agent", value)

    fun referrer(value: String): JsConnection = header("Referer", value)

    fun cookie(key: String, value: String): JsConnection = apply { cookies[key] = value }

    fun cookies(map: Map<*, *>?): JsConnection = apply { map.pairs().forEach { (k, v) -> cookies[k] = v } }

    fun data(key: String, value: String): JsConnection = apply { data += key to value }

    fun data(map: Map<*, *>?): JsConnection = apply { data += map.pairs() }

    fun requestBody(value: String?): JsConnection = apply { body = value }

    /** `Connection.Method.PUT` 在替身里就是字符串 "PUT" */
    fun method(value: String): JsConnection = apply { method = value.uppercase() }

    fun timeout(millis: Int): JsConnection = this

    fun ignoreContentType(ignore: Boolean): JsConnection = this

    fun ignoreHttpErrors(ignore: Boolean): JsConnection = apply { ignoreHttpErrors = ignore }

    fun followRedirects(follow: Boolean): JsConnection = apply { followRedirects = follow }

    fun maxBodySize(bytes: Int): JsConnection = this

    fun postDataCharset(charset: String): JsConnection = apply { postCharset = charset }

    fun get(): JsElement {
        method = "GET"
        return execute().parse()
    }

    fun post(): JsElement {
        method = "POST"
        return execute().parse()
    }

    fun execute(): StrResponse {
        val client = http ?: throw IOException("来源脚本没有网络出口")
        val form = data.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val sendsBody = method == "POST" || method == "PUT" || method == "PATCH"
        var target = url
        var payload: String? = null
        val h = LinkedHashMap(headers)
        if (sendsBody) {
            payload = body ?: form
            if (body == null && form.isNotEmpty() && h.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                h["Content-Type"] = "application/x-www-form-urlencoded; charset=$postCharset"
            }
        } else if (form.isNotEmpty()) {
            target += (if ('?' in target) "&" else "?") + form
        }
        if (cookies.isNotEmpty()) {
            val own = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val key = h.keys.firstOrNull { it.equals("Cookie", ignoreCase = true) } ?: "Cookie"
            h[key] = listOfNotNull(h[key], own).joinToString("; ")
        }
        val resp = client.fetch(target, method, payload, h, followRedirects)
            ?: throw IOException("请求失败: $target")
        if (!ignoreHttpErrors && resp.status >= 400) throw IOException("HTTP ${resp.status}: $target")
        return resp
    }

    private fun enc(s: String) = URLEncoder.encode(s, postCharset)

    private fun Map<*, *>?.pairs(): List<Pair<String, String>> =
        this?.entries?.mapNotNull { (k, v) -> if (k == null || v == null) null else k.toString() to v.toString() }.orEmpty()
}

/** 脚本里 `Jsoup.parse` / `parseBodyFragment` 和 `new TextNode(…)` 的落点（经 JdkCompat 的替身调过来） */
internal object JsoupShim {
    fun parse(html: String, baseUri: String, xml: Boolean): JsElement =
        JsElement(if (xml) Jsoup.parse(html, baseUri, Parser.xmlParser()) else Jsoup.parse(html, baseUri))

    fun parseBodyFragment(html: String, baseUri: String): JsElement = JsElement(Jsoup.parseBodyFragment(html, baseUri))

    fun textNode(text: String): JsElement = JsElement(TextNode(text))

    fun element(tag: String): JsElement = JsElement(Element(tag))

    /** `StrResponse.parse()`：同页面抓取，`<?xml` 开头的按 XML */
    fun parsePageText(text: String, baseUri: String): JsElement = JsElement(parsePage(text, baseUri))
}
