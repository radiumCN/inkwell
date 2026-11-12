package com.radium.inkwell.core.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import com.radium.inkwell.core.util.toHex

/**
 * 抓取日志出口。core 是纯 JVM，碰不到 android.util.Log，由 app 注入（见 AppModules）。
 *
 * 从前抓取失败一行日志都不留：发现页弹一句「加载失败: Connection reset」，开发者模式导出的
 * 日志里却找不到是哪个地址、哪一步、裹着什么异常被掐的 —— 只能对着报错猜。
 */
fun interface SourceHttpLogger {
    /** [warn] = 失败或重试；成功的那条为 false，量大，接成 debug 级 */
    fun log(warn: Boolean, message: String, error: Throwable?)

    companion object {
        val NONE = SourceHttpLogger { _, _, _ -> }
    }
}

/**
 * 阅读（Legado）的 `data:` 地址：不发请求，「响应体」是解码后字节的**十六进制**（AnalyzeUrl.getStrResponseAwait 就这么做），
 * 源里用 `java.hexDecodeToString(result)` 还原。订阅源常拿它当分类、当翻页参数：
 * `路线列表::data:;base64,MA==,{"type":0}`、`data:;base64,{{java.base64Encode("/zh/list/"+page)}},{"type":"x"}`。
 *
 * 从前当网址去请求，一律「非法 URL: data:;base64,…」。只认 base64 形式（Legado 同样只认它）；
 * 地址后面的 `,{…}` 选项调用方通常已经剥掉，没剥的这里也只取到逗号为止。不是 data: 地址返回 null。
 */
internal fun dataUriPage(url: String): FetchedPage? {
    if (!url.startsWith("data:", ignoreCase = true)) return null
    val payload = DATA_URI.find(url)?.groupValues?.get(1) ?: return null
    // MIME 解码器：跳过换行、空格这类非 base64 字符（源里拼出来的常带换行）
    val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(payload) }.getOrNull() ?: return null
    return FetchedPage(
        finalUrl = url,
        statusCode = 200,
        bodyText = bytes.toHex(),
        detectedCharset = Charsets.UTF_8.name(),
    )
}

/**
 * 阅读自己用的请求头，不发给站点：`proxy` 是阅读给这个来源设的代理（AnalyzeUrl 把它拿走），我们不做代理。
 * 翻译类订阅源的 header 脚本一律带着 `'proxy': ''`，原样发出去，IEEE 的防火墙直接回「Request Rejected」。
 * 静态抓取在这里滤掉；交给 WebView 渲染的，引擎用 [withoutLegadoOnlyHeaders] 先滤
 */
internal fun isLegadoOnlyHeader(name: String): Boolean = name.equals("proxy", ignoreCase = true)

internal fun Map<String, String>.withoutLegadoOnlyHeaders(): Map<String, String> = filterKeys { !isLegadoOnlyHeader(it) }

private val DATA_URI = Regex("^data:[^,]*?;base64,([^,]*)", RegexOption.IGNORE_CASE)

/** 抓取结果；bodyText 已按探测到的字符集解码。不再同时握一份 bodyBytes —— 解码后那份字节只是峰值翻倍。 */
class FetchedPage(
    val finalUrl: String,
    val statusCode: Int,
    val bodyText: String,
    val detectedCharset: String,
    /** 响应头（同名多值保留）；脚本里的 `StrResponse.headers(name)` 靠它 */
    val headers: Map<String, List<String>> = emptyMap(),
)

/**
 * 书源 HTTP 封装：
 * - 默认移动端 Chrome UA（调用方未提供时）
 * - 内存 CookieJar（按 cookie 的域/路径匹配，整站 cookie 子域名共享，见 [MemoryCookieJar]）
 * - 按 host 的令牌桶限速（suspend delay 实现）
 * - 403/429 指数退避重试 2 次；连接被对端掐断（RST / 半路断流）的 GET 再试 1 次
 * - 每次抓取的结果与失败经 [SourceHttpLogger] 吐出去（app 接到 logcat，开发者模式导出的日志里就有）
 * - 字符集三级探测：声明 > Content-Type > 前 8KB meta 嗅探；GBK/GB2312 按 GB18030 解码
 */
class SourceHttpClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val retryBaseDelayMs: Long = 500,
    /** 允许书源访问内网地址。**只该在测试里开**（MockWebServer 跑在 127.0.0.1 上） */
    allowPrivateNetwork: Boolean = false,
    private val logger: SourceHttpLogger = SourceHttpLogger.NONE,
) {

    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .guardPrivateNetwork(allowPrivate = allowPrivateNetwork)
        // 超时必须显式配：OkHttp 默认 connect/read/write 各 10 秒，且**没有 callTimeout**。
        // 没有总闸的话，一个每 9 秒吐一个字节的站能把这次调用无限期挂住 —— 而书源脚本那条路
        // 是 runBlocking 阻塞等待的（见 EngineJsHttp），挂住的是一整根 IO 线程，
        // 用户退出页面也放不回来。几十个源并发校验时足以把线程池坐穿。
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .writeTimeout(WRITE_TIMEOUT)
        // 总闸：一次抓取（含重定向、TLS 握手、读完整个 body）最多这么久
        .callTimeout(CALL_TIMEOUT)
        .build()

    /**
     * 不跟随重定向的那一份（脚本的 `java.get/post/head`，见 JsHttp.fetch）。由 [client] 派生：连接池、Cookie、
     * 私网防护（DNS 与拦截器）、超时都是同一套
     */
    private val noRedirectClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 书源脚本要读写 cookie（登录态、防盗链 token 都靠它） */
    fun cookieOf(url: String): String = url.toHttpUrlOrNull()
        ?.let { u -> cookieJar.loadForRequest(u).joinToString("; ") { "${it.name}=${it.value}" } }
        .orEmpty()

    fun setCookie(url: String, cookie: String) {
        val u = url.toHttpUrlOrNull() ?: return
        val cookies = cookie.split(';').mapNotNull { Cookie.parse(u, it.trim()) }
        cookieJar.saveFromResponse(u, cookies)
    }

    fun removeCookie(url: String) {
        url.toHttpUrlOrNull()?.let { cookieJar.clear(it) }
    }

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    @Throws(IOException::class)
    suspend fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        charsetOverride: String? = null,
        rateLimit: RateLimitRule? = null,
        /**
         * 引擎抓页面失败要抛（调用方当「这页没有」）。
         * 脚本里的 `java.get(url).statusCode()` 却是先拿响应再自己判断，
         * 4xx/5xx 也得把 body/code 交出去，不能在这里打死整段 JS。
         */
        throwOnHttpError: Boolean = true,
        /** false：3xx 原样交回（带着 Location），不往下跳 */
        followRedirects: Boolean = true,
    ): FetchedPage {
        dataUriPage(url)?.let { return it }
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("非法 URL: $url")
        if (rateLimit != null && rateLimit.intervalMs > 0) {
            buckets.computeIfAbsent(httpUrl.host) { TokenBucket(rateLimit) }.acquire()
        }
        var attempt = 0
        var disconnectRetried = false
        while (true) {
            val request = buildRequest(httpUrl, method, body, headers, charsetOverride)
            val startNs = System.nanoTime()
            val response = try {
                execute((if (followRedirects) client else noRedirectClient).newCall(request))
            } catch (e: IOException) {
                // 握手/发请求时被 RST（报错就是一句干巴巴的「Connection reset」）多半是瞬时的：
                // 连接池里的旧连接被服务端单方面关了、移动网络切基站。再试一次就好，用户却得
                // 自己点重试。只重试 GET/HEAD —— POST 可能已经送达，重放会把提交做两遍。
                // 一次为限：被防火墙或 WAF 稳定掐断的站，再试多少次都一样，只是让人多等。
                if (!disconnectRetried && method.isIdempotent() && e.isDisconnect()) {
                    disconnectRetried = true
                    logger.log(true, "$method $url 连接被断开（${e.describe()}），重试一次", e)
                    delay(retryBaseDelayMs)
                    continue
                }
                logger.log(true, "$method $url 请求失败（${elapsedMs(startNs)}ms）: ${e.describe()}", e)
                throw e
            }
            if (response.code in RETRY_CODES && attempt < MAX_RETRIES) {
                logger.log(true, "$method $url → HTTP ${response.code}，第 ${attempt + 1} 次退避重试", null)
                response.close()
                delay(retryBaseDelayMs shl attempt) // 指数退避：base、base*2
                attempt++
                continue
            }
            response.use { resp ->
                if (throwOnHttpError && !resp.isSuccessful) {
                    logger.log(true, "$method $url → HTTP ${resp.code}（${elapsedMs(startNs)}ms）", null)
                    throw IOException("HTTP ${resp.code}: $url")
                }
                val bytes = try {
                    withContext(Dispatchers.IO) { readBounded(resp, url) }
                } catch (e: IOException) {
                    logger.log(true, "$method $url → HTTP ${resp.code}，读响应体失败: ${e.describe()}", e)
                    throw e
                }
                val charset = detectCharset(charsetOverride, resp.header("Content-Type"), bytes)
                logger.log(
                    false,
                    "$method $url → HTTP ${resp.code}（${elapsedMs(startNs)}ms，${bytes.size} B，${charset.name()}）",
                    null,
                )
                return FetchedPage(
                    finalUrl = resp.request.url.toString(),
                    statusCode = resp.code,
                    bodyText = String(bytes, charset),
                    detectedCharset = charset.name(),
                    headers = resp.headers.toMultimap(),
                )
            }
        }
    }

    /**
     * 发请求，**取消立刻掐掉 Call**。
     *
     * 从前是 `withContext(IO) { call.execute() }` 配 `Job.invokeOnCompletion { call.cancel() }`。
     * 那个回调只在 Job **完成**时触发，而被 `execute()` 阻塞着的协程在请求回来之前完成不了 ——
     * 外层取消（翻页、退出、换源）传不进来，Call 要跑满 45 秒 callTimeout 才放线程。
     * 书源脚本那条路更糟：EngineJsHttp 的 runBlocking 等的就是这里，整根 IO 线程跟着陪。
     * 改成 enqueue + [suspendCancellableCoroutine]，取消走 `invokeOnCancellation`，是 cancelling
     * 一进入就触发的那种；同时不再为「等响应」额外占一根 IO 线程。
     */
    private suspend fun execute(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // 恢复与取消撞车时把响应关掉，别让连接泄漏在池外
                    cont.resume(response) { _, _, _ -> response.close() }
                }
            })
        }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    private fun String.isIdempotent(): Boolean =
        equals("GET", ignoreCase = true) || equals("HEAD", ignoreCase = true)

    /**
     * 连接被对端/中间设备掐断：RST（`SocketException: Connection reset`，TLS 握手时被掐会裹在
     * SSLException 里）或半路断流（`unexpected end of stream`，裹着 EOFException）。
     * 超时不算 —— `SocketTimeoutException` 不是 SocketException 的子类，已经干等了十几秒的
     * 请求再来一遍只会让人等双倍。
     */
    private fun IOException.isDisconnect(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.take(8)
            .any { it is SocketException || it is EOFException }

    /** 日志里要有异常类型：同样一句「Connection reset」，裸 Socket 与 SSL 握手里被掐是两回事 */
    private fun Throwable.describe(): String =
        generateSequence(this) { it.cause }.take(4)
            .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }

    /**
     * 有上限地读响应体。书源指向的是任意第三方 URL：规则写错指到大文件直链、或恶意源
     * 放一个 gzip 炸弹（透明解压后体积不受 Content-Length 约束），`bytes()` 整读会把进程
     * 直接打 OOM —— 后面还要 `String(bytes)` 与 Jsoup 各复制一份。正文页再长也远够不着这个数。
     */
    private fun readBounded(resp: Response, url: String): ByteArray {
        val declared = resp.body.contentLength()
        if (declared > MAX_BODY_BYTES) throw IOException("响应过大（${declared shr 20} MB）: $url")
        val source = resp.body.source()
        val sink = Buffer()
        while (true) {
            val read = source.read(sink, READ_CHUNK)
            if (read == -1L) break
            if (sink.size > MAX_BODY_BYTES) throw IOException("响应过大（超过 ${MAX_BODY_BYTES shr 20} MB）: $url")
        }
        return sink.readByteArray()
    }

    private fun buildRequest(
        httpUrl: HttpUrl,
        method: String,
        body: String?,
        headers: Map<String, String>,
        charsetOverride: String?,
    ): Request {
        val b = Request.Builder().url(httpUrl)
        var hasUa = false
        var contentType: String? = null
        headers.forEach { (k, v) ->
            // OkHttp 只接受 ASCII 的头名与头值，非法值会抛 IllegalArgumentException 打死整次抓取。
            // 头名若含中文（书源里偶有手误写成中文键）同样会抛 —— 直接跳过这条头。
            // 头值：书源常把 Referer 写成 {{baseUrl}}，而书源 URL 里可能带 emoji 后缀 —— 丢掉该头即可，
            // 不该让整个书源不可用。
            if (!isValidHeaderName(k) || !isValidHeaderValue(v)) return@forEach
            if (isLegadoOnlyHeader(k)) return@forEach
            if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
            if (k.equals("Content-Type", ignoreCase = true)) contentType = v
            b.header(k, v)
        }
        if (!hasUa) b.header("User-Agent", DEFAULT_UA)

        // PUT / PATCH 同 POST 带请求体（Jsoup 替身的 `.method(Connection.Method.PUT).requestBody(…)`）：
        // OkHttp 不许这两种不带体，从前一律当成无体的方法发，直接抛 IllegalArgumentException
        if (method.uppercase() in BODY_METHODS) {
            // 没声明 Content-Type 时按 body 的样子定：JSON 就发 application/json（与 Legado 一致）。
            // 从前一律当表单发，`,{"method":"POST","body":"{\"id\":1}"}` 这类接口源要么 415，
            // 要么服务端按表单解析拿不到参数 —— 搜索/目录直接是空的
            val mediaType = (contentType ?: defaultPostContentType(body)).toMediaTypeOrNull()
            val cs = charsetOverride?.let { runCatching { charsetOf(it) }.getOrNull() } ?: Charsets.UTF_8
            b.method(method.uppercase(), (body ?: "").toByteArray(cs).toRequestBody(mediaType))
        } else {
            b.method(method.uppercase(), null)
        }
        return b.build()
    }

    private fun defaultPostContentType(body: String?): String {
        val t = body?.trim().orEmpty()
        val looksJson = (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
        return if (looksJson) "application/json; charset=UTF-8" else "application/x-www-form-urlencoded"
    }

    /** OkHttp 允许的头值字符：可见 ASCII 与制表符 */
    private fun isValidHeaderValue(v: String): Boolean =
        v.all { it == '\t' || it in ' '..'~' }

    /** OkHttp 允许的头名字符：非空、可见 ASCII，且不含分隔符 `:`/空白 */
    private fun isValidHeaderName(k: String): Boolean =
        k.isNotEmpty() && k.all { it in '!'..'~' && it != ':' }

    /** 字符集探测：override > BOM > Content-Type > 前 8KB 嗅探（meta / XML prolog） */
    private fun detectCharset(override: String?, contentTypeHeader: String?, bytes: ByteArray): Charset {
        if (override != null) {
            try {
                return charsetOf(override)
            } catch (_: Exception) {
                // 非法声明，继续向下探测
            }
        }
        // BOM 是权威声明，早于内容嗅探：带 BOM 的 UTF-8/UTF-16 若按 meta 里的 GBK 解会整页乱码
        detectBom(bytes)?.let { return it }
        contentTypeHeader?.let { header ->
            CHARSET_PARAM.find(header)?.groupValues?.get(1)?.let { name ->
                try {
                    return charsetOf(name)
                } catch (_: Exception) {
                }
            }
        }
        // 前 8KB 按 latin1 解码后嗅探：HTML 的 <meta charset=...>，以及 RSS/XML 的 <?xml encoding="…"?>
        // （GBK/GB2312 的 RSS 源只在 prolog 里声明编码，不嗅 prolog 就整篇乱码）
        val head = String(bytes, 0, minOf(bytes.size, 8192), Charsets.ISO_8859_1)
        (META_CHARSET.find(head) ?: XML_ENCODING.find(head))?.groupValues?.get(1)?.let { name ->
            try {
                return charsetOf(name)
            } catch (_: Exception) {
            }
        }
        return Charsets.UTF_8
    }

    /** UTF-8 / UTF-16 的字节序标记（BOM）→ 对应字符集；无 BOM 返回 null */
    private fun detectBom(b: ByteArray): Charset? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            Charsets.UTF_8
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        else -> null
    }

    companion object {
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

        /**
         * 书源规则是照着这个 UA 抓到的页面写的，JS 渲染器必须沿用同一个，否则站点可能
         * 按 UA 给出另一套 DOM（创世中文网就会在移动 UA 下跳转到 m. 站），规则随即全部落空。
         */
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val RETRY_CODES = setOf(403, 429)
        private const val MAX_RETRIES = 2

        /**
         * 单次响应体上限。整本书最长的正文页（连载几万字 + 满页广告脚本）也就几百 KB，
         * 目录页最长的一类（几千章一页列完）约 1～2 MB；16 MB 是十倍余量，见 [readBounded]。
         */
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024
        private const val READ_CHUNK = 64L * 1024

        /**
         * 超时。小说站普遍慢，压太紧会把能用的源判死，所以比常规 API 客户端宽一档；
         * 但必须有限 —— 见构造里 client 的注释。
         *
         * [CALL_TIMEOUT] 是一次抓取的总闸。取 45 秒：正文页最慢的那批（带 JS 挑战、
         * 多次重定向的）实测在 20 秒内能回，留一倍余量；再长用户早已经放弃等待了。
         */
        private val CONNECT_TIMEOUT = 15.seconds.toJavaDuration()
        private val READ_TIMEOUT = 20.seconds.toJavaDuration()
        private val WRITE_TIMEOUT = 15.seconds.toJavaDuration()
        private val CALL_TIMEOUT = 45.seconds.toJavaDuration()
        private val CHARSET_PARAM = Regex("charset\\s*=\\s*\"?([\\w-]+)", RegexOption.IGNORE_CASE)
        private val META_CHARSET = Regex("<meta[^>]+charset\\s*=\\s*['\"]?([\\w-]+)", RegexOption.IGNORE_CASE)
        private val XML_ENCODING = Regex("<\\?xml[^>]+encoding\\s*=\\s*['\"]?([\\w-]+)", RegexOption.IGNORE_CASE)
    }
}

/**
 * 内存 Cookie 存储，语义照 RFC 6265 / OkHttp 的 [Cookie.matches]：域（含 `Domain=.example.com`
 * 这种整站 cookie）、路径、secure、过期一并判断。只在内存里，不落盘。
 *
 * 从前按「精确 host」分桶、只认 48 个 host：
 * - `www.` 上种下的 `Domain=.example.com` 到了 `m.` 就不带了。反爬站常是「种 cookie → 302 到子域名」，
 *   子域名见不到 cookie 又种一遍、再跳，循环到重定向上限，书源整个打不开。
 * - 一次搜索向上百个源扇出，48 个 host 一下就挤满，登录态、验证过的会话被按 LRU 挤掉。
 *
 * 现在不按 host 分桶，按条目总数封顶：超出时先清过期的，还不够再淘汰最早种下的。
 * 同名 + 同域 + 同路径的视为同一个，后种的替换先种的（RFC 6265 §5.3 第 11 步）。
 */
internal class MemoryCookieJar(private val maxCookies: Int = MAX_COOKIES) : CookieJar {

    private val lock = Any()

    /** 插入顺序 = 种下的先后（重种的挪到末尾），淘汰从头开始 */
    private val cookies = LinkedHashMap<String, Cookie>()

    val size: Int get() = synchronized(lock) { cookies.size }

    /** 删掉会发给这个 host 的全部 cookie（脚本的 `cookie.removeCookie(url)`） */
    fun clear(url: HttpUrl) {
        synchronized(lock) {
            cookies.values.removeAll { domainMatches(it, url.host) }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (c in cookies) {
                val key = keyOf(c)
                this.cookies.remove(key)
                // 已过期的 Set-Cookie（Max-Age=0 / 过去的 Expires）就是服务端在删 cookie：删掉旧的、不存新的
                if (c.expiresAt > now) this.cookies[key] = c
            }
            if (this.cookies.size > maxCookies) evict(now)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val out = ArrayList<Cookie>()
            val it = cookies.values.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c.expiresAt <= now) {
                    it.remove()
                    continue
                }
                if (c.matches(url)) out += c
            }
            return out
        }
    }

    private fun evict(now: Long) {
        cookies.values.removeAll { it.expiresAt <= now }
        val it = cookies.values.iterator()
        while (cookies.size > maxCookies && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    /** host-only 的只认同一个 host；整站 cookie 认本域及其子域（与 [Cookie.matches] 的域判定一致） */
    private fun domainMatches(c: Cookie, host: String): Boolean =
        if (c.hostOnly) host == c.domain
        else host == c.domain || (host.endsWith(c.domain) && host[host.length - c.domain.length - 1] == '.')

    private fun keyOf(c: Cookie): String = "${c.name}\u0000${c.domain}\u0000${c.path}"

    companion object {
        /**
         * 条目总数上限。正常一个站几个到十几个 cookie，宽搜索扇出几百个源也就一两千条；
         * 每条几百字节，封顶时不过一两 MB。
         */
        const val MAX_COOKIES = 3000
    }
}

/** 令牌桶：每 intervalMs 补 1 个令牌，容量 burst；无令牌时挂起等待 */
private class TokenBucket(cfg: RateLimitRule) {

    private val intervalMs = cfg.intervalMs.coerceAtLeast(1)
    private val burst = cfg.burst.coerceAtLeast(1)
    private val mutex = Mutex()
    private var tokens = burst.toDouble()
    private var lastRefill = System.currentTimeMillis()

    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                val now = System.currentTimeMillis()
                tokens = minOf(burst.toDouble(), tokens + (now - lastRefill).toDouble() / intervalMs)
                lastRefill = now
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    ((1.0 - tokens) * intervalMs).toLong() + 1
                }
            }
            if (waitMs <= 0) return
            delay(waitMs)
        }
    }
}
