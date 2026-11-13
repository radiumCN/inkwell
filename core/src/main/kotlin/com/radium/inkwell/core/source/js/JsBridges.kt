package com.radium.inkwell.core.source.js

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.splitUrlOptions
import com.radium.inkwell.core.util.toHex
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 书源脚本可见的桥对象。
 *
 * Legado 的书源大量依赖 `java.*` / `cookie.*` / `cache.*` / `source.*` / `book.*`，
 * 缺了它们整源不可用（我们那份 876 源的书源表里有 37 个源直接卡在这）。
 * Rhino 会把这些 Kotlin 对象的公开方法反射暴露给脚本，所以桥就是普通的类。
 *
 * 只实现无平台依赖的那部分（编解码/加密/时间/网络/KV）—— 它是全部价值的大头，
 * 且可以整个待在 core 里。WebView 抓取、人机验证、字体反爬还原不在此列。
 */

/** 书源脚本能发的 HTTP 请求；由引擎注入，脚本里以 `java.ajax(...)` 等形式调用 */
interface JsHttp {
    /**
     * 同步抓取（脚本是同步的，只能阻塞）；失败返回 null。
     * @param followRedirects 阅读的 `java.get/post/head` 走 Jsoup、**不跟随**重定向（脚本拿 `.header("Location")`
     *   读跳转地址）；`java.ajax/connect` 跟随
     */
    fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): StrResponse?

    /** `java.ajaxAll`：一组请求一起发，结果按传入顺序；失败的那一格为 null。默认挨个发 */
    fun fetchAll(requests: List<JsHttpRequest>): List<StrResponse?> =
        requests.map { fetch(it.url, it.method, it.body, it.headers) }

    fun cookieOf(url: String): String
    fun setCookie(url: String, cookie: String)
    fun removeCookie(url: String)
}

/** `java.ajaxAll` 一组里的一个：地址后面的 `,{…}` 选项已经拆开 */
data class JsHttpRequest(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Legado `connect/get/post/head` 的返回值。
 *
 * 脚本习惯写 `java.get(url).body()` / `.statusCode()` / `.headers('Location')`。
 * 从前这里直接返回 String，一调 `.body()` 就 TypeError，整段 JS 挂掉。
 * [toString] 等于 body，纯拼接 / `String(resp)` 仍能当文本用。
 *
 * 阅读的 `java.get/post/head` 给的是 Jsoup 的 Response（`.header(name)`、`.cookies()`），`java.connect` 给的是
 * 自己的 StrResponse（`.raw()` 是 OkHttp 的 Response，脚本拿 `.raw().request().url()` 读跳转后的地址）。
 * 两边脚本常用的方法这里都有一份，名字照原样。
 */
class StrResponse(
    val bodyText: String = "",
    val status: Int = 0,
    val finalUrl: String = "",
    val headerMap: Map<String, List<String>> = emptyMap(),
    val cookieStr: String = "",
) {
    fun body(): String = bodyText
    fun errorBody(): String = bodyText
    fun code(): Int = status
    fun statusCode(): Int = status
    fun url(): String = finalUrl
    fun cookie(): String = cookieStr

    /** 抖音小说等：`java.get(url,{}).headers('Location')[0]` */
    fun headers(name: String): Array<String> {
        val values = headerMap.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()
        return values.toTypedArray()
    }

    /** Jsoup 的 `header(name)`：同名取第一个，没有为 null。书仓网、来看文学拿 POST 的 `Location` 当搜索结果页 */
    fun header(name: String): String? = headers(name).firstOrNull()

    /** Jsoup 的 `headers()`：头名 → 第一个值 */
    fun headers(): JsStringMap =
        JsStringMap(headerMap.entries.associate { (k, v) -> k to v.firstOrNull().orEmpty() })

    /**
     * Jsoup 的 `cookies()`：**这次响应** Set-Cookie 下发的 cookie，名 → 值。
     * 一读这几个站先回一段脚本挑战，`java.head(url).cookies()` 拿到 token / secret 再自己拼 Cookie
     */
    fun cookies(): JsStringMap = JsStringMap(responseCookies())

    fun cookie(name: String): String? = responseCookies()[name]

    private fun responseCookies(): Map<String, String> =
        headers("Set-Cookie").mapNotNull { line ->
            val pair = line.substringBefore(';')
            val eq = pair.indexOf('=')
            if (eq <= 0) null else pair.substring(0, eq).trim() to pair.substring(eq + 1).trim()
        }.toMap()

    fun isSuccessful(): Boolean = status in 200..299

    /** Jsoup 的 `execute().parse()`：响应体当页面解析（`<?xml` 开头的按 XML） */
    fun parse(): JsElement = JsoupShim.parsePageText(bodyText, finalUrl)

    fun contentType(): String? = header("Content-Type")

    /** 没有状态描述可给（HTTP/2 本来就没有），给空串 */
    fun message(): String = ""

    fun statusMessage(): String = ""

    /** OkHttp 的 Response：脚本只拿它读请求地址、状态和头，交回自己 */
    fun raw(): StrResponse = this

    /** `.raw().request().url()`：跳转之后最终请求的地址 */
    fun request(): JsRequest = JsRequest(finalUrl)

    override fun toString(): String = bodyText

    companion object {
        fun empty(): StrResponse = StrResponse()
    }
}

/** [StrResponse.request] 的返回值：只有地址 */
class JsRequest(private val url: String) {
    fun url(): String = url
    override fun toString(): String = url
}

/**
 * 交给脚本的字符串表，当 JS 对象用：`m.token`、`m['token']`、`m.get('token')`、`JSON.stringify(m)` 都行。
 *
 * 不把 `java.util.Map` 直接交出去：ClassShutter 只放行桥所在的包，脚本一碰 LinkedHashMap 就被挡下（这是故意的，
 * 见 RhinoScriptRuntime.BRIDGE_ONLY）。阅读那边是 Java 的 Map，脚本两种写法都有：`cookies().token` 和
 * `getLoginInfoMap().get('x')`，所以既是 JS 对象、又挂一个不可枚举的 `get`。
 */
class JsStringMap(private val map: Map<String, String>) : ScriptableObject() {
    init {
        map.forEach { (k, v) -> defineProperty(k, v, EMPTY) }
        if ("get" !in map) {
            defineProperty("get", object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>): Any? =
                    args.firstOrNull()?.let { map[Context.toString(it)] }
            }, DONTENUM)
        }
    }

    override fun getClassName(): String = "Object"

    // 没有原型（桥方法里拿不到脚本的作用域），`'' + m` 找不到 toString：直接给 JSON
    override fun getDefaultValue(typeHint: Class<*>?): Any = toString()

    override fun toString(): String =
        kotlinx.serialization.json.JsonObject(map.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()
}

/**
 * 脚本里回过头用规则语法抽当前页（`java.getString(rule)`）。
 * 求值器注入；单测不注入时这些方法返回空，缺方法本身不会把脚本打死。
 */
interface JsRuleQuery {
    fun getString(rule: String): String
    fun getStringList(rule: String): Array<String>
    fun getElement(rule: String): JsElement?
    fun getElements(rule: String): JsElements

    /**
     * `java.setContent(内容, baseUrl?)`：之后的 getString / getElement 落在这份内容上（字符串当新页面，
     * getElements 拿到的元素就落在那个元素上）。起点、来看文学重新 ajax 一遍页面再接着抽；优特逐个元素抽字段
     */
    fun withContent(content: Any?, baseUrl: String?): JsRuleQuery = this

    /** `getString(规则, 内容, true)` 的「是地址」：按当前页补成绝对地址 */
    fun absoluteUrl(url: String): String = url
}


/** 带 TTL 的 KV；`cache.*` 与 `java.cache*` 用。条目上限 256，get 时过期删除，put 时顺手淘汰。 */
class JsCache {
    private class Entry(val value: String, val expireAt: Long)

    private val lock = Any()
    private val map = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > 256
    }

    /** @param saveTime 秒；<= 0 表示不过期 */
    @JvmOverloads
    fun put(key: String, value: String, saveTime: Int = 0): String {
        val expire = if (saveTime > 0) System.currentTimeMillis() + saveTime * 1000L else Long.MAX_VALUE
        synchronized(lock) {
            evictExpired()
            map[key] = Entry(value, expire)
        }
        return value
    }

    fun get(key: String): String? = synchronized(lock) {
        val e = map[key] ?: return null
        if (e.expireAt < System.currentTimeMillis()) {
            map.remove(key)
            return null
        }
        e.value
    }

    fun delete(key: String) {
        synchronized(lock) {
            map.remove(key)
            memory.remove(key)
        }
    }

    // 阅读的 putMemory / getFromMemory：存的是脚本里的对象本身（不转字符串），进程内、不过期，攒满了挤掉最久没用的。
    // 八叉书库拿它记下解过一次的站点列表

    private val memory = object : LinkedHashMap<String, Any?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any?>?): Boolean = size > MEMORY_ENTRIES
    }

    fun putMemory(key: String, value: Any?) {
        synchronized(lock) { memory[key] = value }
    }

    fun getFromMemory(key: String): Any? = synchronized(lock) { memory[key] }

    fun deleteMemory(key: String) {
        synchronized(lock) { memory.remove(key) }
    }

    private companion object {
        const val MEMORY_ENTRIES = 64
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expireAt < now) it.remove()
        }
    }
}

/**
 * 书源级键值 + 用户变量，脚本里是 `source`。
 *
 * 目前只在进程内存活。跨会话持久化（登录 token 那类）要落库，属于后续工作 ——
 * 大多数规则的 put/get 都发生在同一条抓取链路内，进程内足够。
 *
 * 阅读交给脚本的 `source` 是书源对象本身，脚本会直接读它的字段：`source.bookSourceUrl` 拼 Referer，
 * 还有一批来源把公用函数写在「来源说明」里、规则开头 `eval(String(source.bookSourceComment))` 加载 ——
 * 没有这个字段，eval 的是字符串 "undefined"，后面一调那些函数就 ReferenceError，整条规则落空。
 * 写在登录脚本里、`eval(String(source.loginUrl))` 加载的同理；别的字段见 [SourceFacts]。
 */
class SourceBridge(
    private val key: String,
    private val store: MutableMap<String, String> = ConcurrentHashMap(),
    private val facts: SourceFacts = SourceFacts(),
) {
    @JvmField val bookSourceUrl: String = key
    @JvmField val bookSourceName: String = facts.name
    @JvmField val bookSourceComment: String = facts.comment
    @JvmField val bookSourceGroup: String = facts.group
    @JvmField val bookSourceType: Int = facts.type
    @JvmField val loginUrl: String? = facts.loginUrl
    @JvmField val header: String? = facts.header
    @JvmField val jsLib: String? = facts.jsLib
    @JvmField val searchUrl: String? = facts.searchUrl
    @JvmField val exploreUrl: String? = facts.exploreUrl

    // 订阅源在阅读里是 RssSource，字段名不带 book：`source.sourceUrl` 拼 Referer（翻译类订阅源的 header 脚本、登录界面都这么写）
    @JvmField val sourceUrl: String = key
    @JvmField val sourceName: String = facts.name
    @JvmField val sourceComment: String = facts.comment

    /**
     * 阅读的 BaseSource.loginUi() 是个**方法**（把登录界面的 JSON 解成一排控件），同名的字段被它盖住 ——
     * 所以在阅读里 `typeof source.loginUi == 'function'`。书山聚合、大灰狼聚合、光遇聚合、魔丸这一批聚合源
     * 靠这一句认「是不是官方阅读」：不是函数就当成某个改版 App，搜索、目录走另一套接口，在我们这儿一章目录都解不出来。
     * 我们不画登录界面，返回界面的原文
     */
    fun loginUi(): String? = facts.loginUi

    // 规则集：`source.ruleSearch.author` 拿搜索列表的作者规则，脚本里 java.getString 再跑一遍（八叉书库）。
    // 用 getter 而不是字段：只有读到的那一个才建表
    fun getRuleSearch(): JsStringMap? = facts.ruleSets["ruleSearch"]?.let(::JsStringMap)

    fun getRuleExplore(): JsStringMap? = facts.ruleSets["ruleExplore"]?.let(::JsStringMap)

    fun getRuleBookInfo(): JsStringMap? = facts.ruleSets["ruleBookInfo"]?.let(::JsStringMap)

    fun getRuleToc(): JsStringMap? = facts.ruleSets["ruleToc"]?.let(::JsStringMap)

    fun getRuleContent(): JsStringMap? = facts.ruleSets["ruleContent"]?.let(::JsStringMap)

    fun getKey(): String = key

    fun put(k: String, value: String): String {
        store[k] = value
        return value
    }

    fun get(k: String): String = store[k].orEmpty()

    fun getVariable(): String = store["\$variable"].orEmpty()

    fun putVariable(v: String?): String {
        store["\$variable"] = v.orEmpty()
        return v.orEmpty()
    }

    fun setVariable(v: String?): String = putVariable(v)

    // ---- 登录信息 ----
    // 不做登录界面：没人填过，就是阅读里「还没登录」的样子 —— getLoginInfo / getLoginInfoMap 为 null。
    // 从前这几个方法不存在，一调就 ReferenceError：霹雳书屋的 jsLib 拼地址前先读一下登录信息，整个源搜不了；
    // 订阅源的 header 脚本包在 try 里倒是不死，但请求头一个也没算出来。
    // 脚本自己存进来的（一读先过一道脚本挑战、算出 Cookie 后 putLoginHeader）照存，只在进程内

    fun getLoginInfo(): String? = store[LOGIN_INFO]

    fun getLoginInfoMap(): JsStringMap? = getLoginInfo()?.let(::jsonStringMap)

    fun putLoginInfo(info: String?): Boolean {
        if (info.isNullOrBlank()) store.remove(LOGIN_INFO) else store[LOGIN_INFO] = info
        return true
    }

    fun removeLoginInfo() {
        store.remove(LOGIN_INFO)
    }

    fun getLoginHeader(): String? = store[LOGIN_HEADER]

    fun getLoginHeaderMap(): JsStringMap? = getLoginHeader()?.let(::jsonStringMap)

    fun putLoginHeader(header: String?) {
        if (header.isNullOrBlank()) store.remove(LOGIN_HEADER) else store[LOGIN_HEADER] = header
    }

    fun removeLoginHeader() {
        store.remove(LOGIN_HEADER)
    }

    private fun jsonStringMap(json: String): JsStringMap? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
    }.getOrNull()?.let { obj ->
        JsStringMap(obj.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString() })
    }

    companion object {
        private const val LOGIN_INFO = "\$loginInfo"

        /** 引擎读它给这个来源的请求补头（RuleEvaluator.loginHeaders） */
        const val LOGIN_HEADER = "\$loginHeader"
    }
}

/**
 * 来源对象上脚本会直接读的字段（阅读交给脚本的 `source` 就是来源对象本身），每个来源算一次
 * （BookSourceRule.scriptFacts），每次求值新建的 [SourceBridge] 共用它。订阅源只给名字。
 */
class SourceFacts(
    val name: String = "",
    val comment: String = "",
    val group: String = "",
    val type: Int = 0,
    val loginUrl: String? = null,
    val loginUi: String? = null,
    val header: String? = null,
    val jsLib: String? = null,
    val searchUrl: String? = null,
    val exploreUrl: String? = null,
    /** 规则集按阅读的字段名：`ruleSearch` → { bookList, name, … }；没写的规则集、规则不在里面 */
    val ruleSets: Map<String, Map<String, String>> = emptyMap(),
)

/** Cookie，脚本里是 `cookie` */
class CookieBridge(private val http: JsHttp?) {
    fun getCookie(url: String): String = http?.cookieOf(url).orEmpty()

    fun getKey(url: String, key: String): String =
        getCookie(url).split(';')
            .firstOrNull { it.substringBefore('=').trim() == key }
            ?.substringAfter('=')?.trim()
            .orEmpty()

    fun setCookie(url: String, cookie: String) {
        http?.setCookie(url, cookie)
    }

    fun removeCookie(url: String) {
        http?.removeCookie(url)
    }
}

/**
 * 书籍/章节上下文，脚本里是 `book` / `chapter`。
 * 字段用 @JvmField 暴露成公开字段，Rhino 才能像 `book.name` 那样直接读写。
 */
/**
 * 脚本里的 `book`。阅读交给脚本的是书对象本身，字段照它的 Book 起名、都能改：聚合源在目录脚本里写
 * `book.type = 8`（按书的类型选接口），一个字段不存在，赋值就抛错、整条目录规则落空（书山聚合、大灰狼、魔丸）。
 * 改了只在这一次抓取里有效，不回写书架；阅读设置类的方法（`setUseReplaceRule`、`setReverseToc`…）是空的。
 */
class BookBridge(
    @JvmField var name: String = "",
    @JvmField var author: String = "",
    @JvmField var bookUrl: String = "",
    @JvmField var tocUrl: String = "",
    @JvmField var kind: String = "",
    @JvmField var intro: String = "",
    @JvmField var coverUrl: String = "",
    /** 来源地址 / 名字（阅读的 origin / originName） */
    @JvmField var origin: String = "",
    @JvmField var originName: String = "",
) {
    /** 阅读的 BookType：8 是文字书（音频 32、图片 64），来源脚本按它挑接口 */
    @JvmField var type: Int = 8
    @JvmField var wordCount: String? = null
    @JvmField var latestChapterTitle: String? = null
    @JvmField var totalChapterNum: Int = 0
    @JvmField var durChapterTitle: String? = null
    @JvmField var durChapterIndex: Int = 0
    @JvmField var durChapterPos: Int = 0
    @JvmField var customTag: String? = null
    @JvmField var customCoverUrl: String? = null
    @JvmField var customIntro: String? = null
    @JvmField var charset: String? = null
    @JvmField var canUpdate: Boolean = true

    private val variables = ConcurrentHashMap<String, String>()

    fun putVariable(key: String, value: String?): String {
        variables[key] = value.orEmpty()
        return value.orEmpty()
    }

    fun getVariable(key: String): String = variables[key].orEmpty()

    /** 阅读的「自定义变量」就是键 custom 的那个变量 */
    fun putCustomVariable(value: String?) {
        putVariable("custom", value)
    }

    fun getCustomVariable(): String = getVariable("custom")

    fun getRealAuthor(): String = author

    fun getDisplayCover(): String = customCoverUrl?.takeIf { it.isNotEmpty() } ?: coverUrl

    fun getDisplayIntro(): String = customIntro?.takeIf { it.isNotEmpty() } ?: intro

    // 阅读里改的是这本书的阅读设置；我们的设置不归来源管，照阅读的默认值回答

    fun setUseReplaceRule(useReplaceRule: Boolean) = Unit

    fun getUseReplaceRule(): Boolean = true

    fun setReverseToc(reverseToc: Boolean) = Unit

    fun getReverseToc(): Boolean = false

    fun setReSegment(reSegment: Boolean) = Unit

    fun getReSegment(): Boolean = false

    fun setImageStyle(imageStyle: String?) = Unit

    fun getImageStyle(): String? = null

    fun setSplitLongChapter(splitLongChapter: Boolean) = Unit

    fun getSplitLongChapter(): Boolean = true
}

/** 脚本里的 `chapter`，字段照阅读的 BookChapter */
class ChapterBridge(
    @JvmField var title: String = "",
    @JvmField var url: String = "",
    @JvmField var index: Int = 0,
) {
    @JvmField var bookUrl: String = ""
    @JvmField var baseUrl: String = ""
    /** 阅读里是更新时间这类附加信息 */
    @JvmField var tag: String? = null
    @JvmField var wordCount: String? = null

    private val variables = ConcurrentHashMap<String, String>()

    fun putVariable(key: String, value: String?): String {
        variables[key] = value.orEmpty()
        return value.orEmpty()
    }

    fun getVariable(key: String): String = variables[key].orEmpty()
}

/**
 * 脚本里的 `java`。方法名与 Legado 的 JsExtensions 保持一致，否则书源里的脚本调不通。
 * Rhino 按参数个数区分重载，所以 `java.get(k)`（取变量）与 `java.get(url, headers)`（发请求）能共存。
 */
class JavaBridge(
    private val http: JsHttp?,
    private val cache: JsCache,
    private val vars: MutableMap<String, String>,
    private val query: JsRuleQuery? = null,
    private val chinese: ChineseConverter = ChineseConverter.NONE,
    /** [androidId] 给的设备号，见 [ScriptRuntime.androidId] */
    private val deviceId: () -> String = { "" },
) {

    /** getString / getElement 落在哪份内容上；[setContent] 换掉它 */
    private var content: JsRuleQuery? = query

    // ---- 网络 ----

    /**
     * 与 Legado 一致：地址可自带 `,{'method':'POST','body':…,'headers':…}` 选项，
     * 且**失败时返回错误文本而不是 null** —— 书源脚本普遍直接写 `java.ajax(url).match(…)`，
     * 返回 null 会在 JS 里抛 TypeError，把整条脚本连同书源一起搞死（思路客、大文学等一批源就死在这）。
     */
    fun ajax(url: String): String = fetchWithOptions(url, "GET", null, emptyMap()).body()

    /**
     * 一组地址一起抓，结果按传入的顺序（阅读的 `java.ajaxAll`）：漫画、图集的正文要把十几页挨个取回来拼图，
     * 翻译类订阅源把整篇文章的段落分几批发给翻译接口。地址同样可以带 `,{…}` 选项；抓不回来的那一格是空响应，
     * 不是 null —— 脚本直接 `res[i].body()`。
     * [skipRateLimit] 是阅读的「这批不按来源的并发率排队」，我们这里本来就不排，忽略。
     */
    @JvmOverloads
    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean = false): Array<StrResponse> {
        val client = http ?: return Array(urlList.size) { StrResponse.empty() }
        // 选项照 ajax 那样整块带上：翻译类订阅源的每一格都是 `…,{"method":"post","body":…}`，丢了就是一排 405
        val requests = urlList.map { raw ->
            val split = splitUrlOptions(raw)
            val opt = split?.second
            JsHttpRequest(split?.first ?: raw, opt?.method ?: "GET", opt?.body, opt?.headers.orEmpty())
        }
        val results = client.fetchAll(requests)
        return Array(urlList.size) { i -> results.getOrNull(i) ?: StrResponse.empty() }
    }

    fun connect(url: String): StrResponse = fetchWithOptions(url, "GET", null, emptyMap())

    fun connect(url: String, header: String?): StrResponse =
        fetchWithOptions(url, "GET", null, parseHeaders(header))

    private fun fetchWithOptions(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        followRedirects: Boolean = true,
    ): StrResponse {
        val split = splitUrlOptions(url)
        val bare = split?.first ?: url
        val opt = split?.second
        return http?.fetch(
            bare,
            method = opt?.method ?: method,
            body = opt?.body ?: body,
            headers = headers + opt?.headers.orEmpty(),
            followRedirects = followRedirects,
        ) ?: StrResponse.empty()
    }

    // ---- 交互（我们没有 UI 回调，作空实现）----
    // 缺一个方法，Rhino 就是 ReferenceError，整个书源直接判死。Legado 有的这些入口
    // 宁可给空壳：脚本照常往下走，最坏是拿不到人机验证后的页面，而不是整源不可用。

    fun startBrowser(url: String, title: String?): String = ""

    fun startBrowserAwait(url: String, title: String?): String = ""

    fun getVerificationCode(url: String): String = ""

    // 阅读里是拉起浏览器 / 别的 App 打开链接（目录的章节地址、简介里的「打开网页」都有这么写的）；后台抓取没有界面可拉

    fun openUrl(url: String) = Unit

    fun openUrl(url: String, mimeType: String?) = Unit

    // 阅读的 get / post / head 走 Jsoup、**不跟随重定向**：书仓网、来看文学 POST 搜索后读 `.header("Location")`
    // 拿结果页地址。从前跟着跳了过去，拿到的是结果页本身，Location 是空的，搜索地址拼成「站点/null」。
    // 带超时的那几个重载（第三、四个参数是毫秒）照样认，超时用我们自己的

    fun get(url: String, headers: Map<*, *>?): StrResponse =
        fetchWithOptions(url, "GET", null, toStringMap(headers), followRedirects = false)

    fun get(url: String, headers: Map<*, *>?, timeout: Int): StrResponse = get(url, headers)

    fun post(url: String, body: String?, headers: Map<*, *>?): StrResponse =
        fetchWithOptions(url, "POST", body, toStringMap(headers), followRedirects = false)

    fun post(url: String, body: String?, headers: Map<*, *>?, timeout: Int): StrResponse = post(url, body, headers)

    fun head(url: String, headers: Map<*, *>?): StrResponse =
        fetchWithOptions(url, "HEAD", null, toStringMap(headers), followRedirects = false)

    fun head(url: String, headers: Map<*, *>?, timeout: Int): StrResponse = head(url, headers)

    // ---- 用规则语法回抽当前页（混合写法：先 JS 判断再用 CSS）----

    fun getString(rule: String): String = content?.getString(rule).orEmpty()

    // 阅读的 getString / getStringList 带两个可省的参数：在哪份内容上取（`java.getString('.content_txt@html', html)`，
    // 爱丽丝书屋的正文这么写）、取到的是不是地址（是就补成绝对地址）。从前只有一个参数的，两个参数一调就找不到方法
    fun getString(rule: String, content: Any?): String = on(content)?.getString(rule).orEmpty()

    fun getString(rule: String, content: Any?, isUrl: Boolean): String {
        val q = on(content) ?: return ""
        val s = q.getString(rule)
        return if (isUrl && s.isNotEmpty()) q.absoluteUrl(s) else s
    }

    /** 阅读的 `getString(规则, unescape)`：第二个参数是「要不要反转义 HTML」，我们取到的本来就是文字 */
    fun getString(rule: String, unescape: Boolean): String = getString(rule)

    fun getStringList(rule: String): Array<String> = content?.getStringList(rule) ?: emptyArray()

    fun getStringList(rule: String, content: Any?): Array<String> = on(content)?.getStringList(rule) ?: emptyArray()

    fun getStringList(rule: String, content: Any?, isUrl: Boolean): Array<String> {
        val q = on(content) ?: return emptyArray()
        val list = q.getStringList(rule)
        return if (isUrl) list.map { if (it.isEmpty()) it else q.absoluteUrl(it) }.toTypedArray() else list
    }

    /** 传了内容就落在那份内容上（不改 [setContent] 设下的当前内容），没传落在当前内容上 */
    private fun on(content: Any?): JsRuleQuery? =
        if (content == null) this.content else (this.content ?: query)?.withContent(content, null)

    fun getElement(rule: String): JsElement? = content?.getElement(rule)

    /** 同阅读给的是 Elements：`size()` / `get(i)` / `text()`，下标和 length 也认（见 BridgeWrapFactory） */
    fun getElements(rule: String): JsElements = content?.getElements(rule) ?: JsElements(emptyList())

    /** 脚本里 `org.jsoup.Jsoup.connect(url)` 的落点（Jsoup 替身转过来）：经这个来源的网络出口发 */
    fun jsoupConnect(url: String): JsConnection = JsConnection(http, url)

    /**
     * 换一份内容给后面的 getString / getElement 用（阅读的 `java.setContent`）：字符串当新页面，
     * getElements 拿到的元素就落在那个元素上。从前没有这个方法，起点「过了 cookie 验证重新 ajax 一遍」那条路一走就是 ReferenceError
     */
    fun setContent(content: Any?) = setContent(content, null)

    fun setContent(content: Any?, baseUrl: String?) {
        this.content = query?.withContent(content, baseUrl)
    }

    // ---- 变量（同一条抓取链路内传值）----

    fun put(key: String, value: String): String {
        vars[key] = value
        return value
    }

    fun get(key: String): String = vars[key].orEmpty()

    fun getCookie(url: String): String = http?.cookieOf(url).orEmpty()

    /** 阅读的 `java.getCookie(地址, 名字)`：只要其中一个 Cookie 的值；名字给 null 同一个参数的。光遇聚合的目录每章都这么取一次 */
    fun getCookie(url: String, key: String?): String = key?.let { CookieBridge(http).getKey(url, it) } ?: getCookie(url)

    // ---- 编解码 ----

    fun base64Encode(str: String): String =
        Base64.getEncoder().encodeToString(str.toByteArray(Charsets.UTF_8))

    fun base64Decode(str: String): String = String(decodeBase64(str), Charsets.UTF_8)

    fun base64DecodeToByteArray(str: String): ByteArray = decodeBase64(str)

    fun md5Encode(str: String): String = digest("MD5", str.toByteArray(Charsets.UTF_8))

    /** Legado 的 16 位 MD5：取 32 位的中间 16 位 */
    fun md5Encode16(str: String): String = md5Encode(str).substring(8, 24)

    /** 同阅读：`java.digestHex(数据, 算法)`。从前参数是反的（算法在前），照阅读写的脚本拿到的是一个报错 */
    fun digestHex(data: String, algorithm: String): String =
        digest(algorithm, data.toByteArray(Charsets.UTF_8))

    fun hexEncodeToString(str: String): String =
        str.toByteArray(Charsets.UTF_8).toHex()

    fun hexDecodeToString(hex: String): String = String(hexToBytes(hex), Charsets.UTF_8)

    fun hexDecodeToByteArray(hex: String): ByteArray = hexToBytes(hex)

    fun strToBytes(str: String): ByteArray = str.toByteArray(Charsets.UTF_8)

    fun strToBytes(str: String, charset: String): ByteArray = str.toByteArray(charsetOf(charset))

    fun bytesToStr(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    fun bytesToStr(bytes: ByteArray, charset: String): String = String(bytes, charsetOf(charset))

    fun encodeURI(str: String): String = URLEncoder.encode(str, "UTF-8")

    fun encodeURI(str: String, enc: String): String = URLEncoder.encode(str, enc)

    // ---- 对称加密（书源常用 AES / DES 解正文、算签名）----
    // 同阅读：这几个都经 createSymmetricCrypto 走，字符串密文自动认十六进制 / Base64（见 [SymmetricCrypto]）。
    // 从前 aesDecodeToString 把密文串的 UTF-8 字节直接当密文，接口返回的密文都是编过码的，解不开；
    // 非 CBC 的模式（CFB、CTR……）也一律不带 IV

    fun createSymmetricCrypto(transformation: String, key: String): SymmetricCrypto =
        createSymmetricCrypto(transformation, key, null)

    fun createSymmetricCrypto(transformation: String, key: String, iv: String?): SymmetricCrypto =
        SymmetricCrypto(transformation, key.toByteArray(Charsets.UTF_8), iv?.toByteArray(Charsets.UTF_8))

    fun createSymmetricCrypto(transformation: String, key: ByteArray): SymmetricCrypto =
        createSymmetricCrypto(transformation, key, null)

    fun createSymmetricCrypto(transformation: String, key: ByteArray, iv: ByteArray?): SymmetricCrypto =
        SymmetricCrypto(transformation, key, iv)

    fun aesDecodeToString(data: String, key: String, transformation: String, iv: String): String =
        createSymmetricCrypto(transformation, key, iv).decryptStr(data)

    fun aesBase64DecodeToString(data: String, key: String, transformation: String, iv: String): String =
        createSymmetricCrypto(transformation, key, iv).decryptStr(data)

    fun aesEncodeToBase64String(data: String, key: String, transformation: String, iv: String): String =
        createSymmetricCrypto(transformation, key, iv).encryptBase64(data)

    fun desDecodeToString(data: String, key: String, transformation: String, iv: String): String =
        createSymmetricCrypto(transformation, key, iv).decryptStr(data)

    fun desBase64DecodeToString(data: String, key: String, transformation: String, iv: String): String =
        createSymmetricCrypto(transformation, key, iv).decryptStr(data)

    fun desEncodeToString(data: String, key: String, transformation: String, iv: String): String =
        String(createSymmetricCrypto(transformation, key, iv).encrypt(data), Charsets.UTF_8)

    fun desEncodeToBase64String(data: String, key: String, transformation: String, iv: String): String =
        createSymmetricCrypto(transformation, key, iv).encryptBase64(data)

    // ---- HMAC 签名：名字照阅读（HMacHex / HMacBase64），算法写 HmacMD5、HMacSHA256……大小写都认 ----

    @Suppress("FunctionName")
    fun HMacHex(data: String, algorithm: String, key: String): String = hmac(data, algorithm, key).toHex()

    @Suppress("FunctionName")
    fun HMacBase64(data: String, algorithm: String, key: String): String =
        Base64.getEncoder().encodeToString(hmac(data, algorithm, key))

    // ---- 时间/文本/杂项 ----

    fun timeFormat(time: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(time))

    /**
     * 阅读的 `java.timeFormatUTC(毫秒, 格式, 偏移)`：偏移照阅读当**毫秒**（SimpleTimeZone 的 rawOffset）——
     * 有来源写成 `8`（想的是 8 小时），照阅读出的就是 UTC 时间，规则是照那个结果写的。格式写错同阅读抛出去
     */
    fun timeFormatUTC(time: Long, format: String, sh: Int): String =
        java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
            .apply { timeZone = java.util.SimpleTimeZone(sh, "UTC") }
            .format(java.util.Date(time))

    fun htmlFormat(str: String): String = org.jsoup.Jsoup.parse(str).text()

    /** 繁体 → 简体（阅读的 `java.t2s`）；平台没给转换器时原样返回，见 [ChineseConverter] */
    fun t2s(text: String?): String = text?.let(chinese::toSimplified).orEmpty()

    /** 简体 → 繁体：台湾站搜索前先把关键词转成繁体（`searchkey={{java.s2t(key)}}`） */
    fun s2t(text: String?): String = text?.let(chinese::toTraditional).orEmpty()

    /**
     * 「第一百二十三章 xxx」→「第123章」（阅读的 `java.toNumChapter`）：返回的**只有**换成数字的那一段「第N章」，
     * 标题其余部分不要 —— 来源就是这么用的：`.replace(/第([零一二…]+)章/g, java.toNumChapter(result))`。
     * 没有「第…章」原样返回；章号认不出（「第1卷 第3章」那种懒惰匹配到的一大段）给回匹配到的原文，阅读这时写成「第-1章」，没必要学
     */
    fun toNumChapter(s: String?): String? {
        s ?: return null
        val m = TITLE_NUM.find(s) ?: return s
        val n = com.radium.inkwell.core.source.detect.ChapterTitles.parseNumber(m.groupValues[2].filterNot { it.isWhitespace() })
            ?: return m.value
        return "${m.groupValues[1]}$n${m.groupValues[3]}"
    }

    fun randomUUID(): String = UUID.randomUUID().toString()

    /**
     * 设备号（阅读的 `java.androidId()`，那边给系统的 ANDROID_ID）：接口源拿它当 deviceid 放进请求头（Lofter），
     * 从前没有这个方法，header 脚本一调就 ReferenceError，这个来源的请求一个也发不出去。给的是什么见 [ScriptRuntime.androidId]。
     *
     * **别加 `java.deviceID()`**：那是 iOS 上的阅读类 App 才有的方法，聚合源拿「调不调得通它」认 App ——
     * 加上就被当成 iOS，搜索、目录走苹果那套接口
     */
    fun androidId(): String = deviceId()

    /**
     * 阅读给的是系统 WebView 的 UA；我们抓取、渲染一律用 [SourceHttpClient.DEFAULT_UA]（站点按 UA 给不同的页面，
     * 规则是照同一份写的），这里也给它。header 脚本里 `"User-Agent": java.getWebViewUA()` 的来源（晋江、太极小说）靠它
     */
    fun getWebViewUA(): String = SourceHttpClient.DEFAULT_UA

    /** 书源用它打调试日志；返回原值方便串在表达式里 */
    fun log(msg: Any?): Any? = msg

    fun toast(msg: Any?): Any? = msg

    fun longToast(msg: Any?): Any? = msg

    fun cacheGet(key: String): String? = cache.get(key)

    @JvmOverloads
    fun cachePut(key: String, value: String, saveTime: Int = 0): String = cache.put(key, value, saveTime)

    // ---- 内部 ----

    private fun hmac(data: String, algorithm: String, key: String): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithm))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).toHex()

    private fun decodeBase64(str: String): ByteArray = decodeBase64Lenient(str)

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").filter { !it.isWhitespace() }
        if (clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun charsetOf(name: String) =
        runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)

    private fun toStringMap(m: Map<*, *>?): Map<String, String> =
        m?.entries?.associate { (k, v) -> k.toString() to v.toString() }.orEmpty()

    /** header 允许写成 JSON 字符串 */
    private fun parseHeaders(header: String?): Map<String, String> {
        val t = header?.trim().orEmpty()
        if (t.isEmpty()) return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(t)
                .let { it as kotlinx.serialization.json.JsonObject }
                .entries.associate { (k, v) ->
                    k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                }
        }.getOrDefault(emptyMap())
    }
}

/** 阅读的 AppPattern.titleNumPattern */
private val TITLE_NUM = Regex("(第)(.+?)(章)")

/** 书源里的 base64 常缺 padding 或用 URL 变体，宽松处理；实在解不开给空数组 */
internal fun decodeBase64Lenient(str: String): ByteArray {
    val clean = str.trim().replace("\n", "").replace("\r", "")
    return runCatching { Base64.getDecoder().decode(padded(clean)) }
        .recoverCatching { Base64.getUrlDecoder().decode(padded(clean)) }
        .recoverCatching { Base64.getMimeDecoder().decode(clean) }
        .getOrElse { ByteArray(0) }
}

private fun padded(s: String): String = when (s.length % 4) {
    2 -> "$s=="
    3 -> "$s="
    else -> s
}
