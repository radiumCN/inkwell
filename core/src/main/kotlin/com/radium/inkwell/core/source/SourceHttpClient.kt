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
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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
 * - 按 host 的内存 CookieJar
 * - 按 host 的令牌桶限速（suspend delay 实现）
 * - 403/429 指数退避重试 2 次
 * - 字符集三级探测：声明 > Content-Type > 前 8KB meta 嗅探；GBK/GB2312 按 GB18030 解码
 */
class SourceHttpClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val retryBaseDelayMs: Long = 500,
    /** 允许书源访问内网地址。**只该在测试里开**（MockWebServer 跑在 127.0.0.1 上） */
    allowPrivateNetwork: Boolean = false,
) {

    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .dns(PrivateNetworkGuardDns(allowPrivate = allowPrivateNetwork))
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
        url.toHttpUrlOrNull()?.let { cookieJar.clear(it.host) }
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
    ): FetchedPage {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("非法 URL: $url")
        if (rateLimit != null && rateLimit.intervalMs > 0) {
            buckets.computeIfAbsent(httpUrl.host) { TokenBucket(rateLimit) }.acquire()
        }
        var attempt = 0
        while (true) {
            val request = buildRequest(httpUrl, method, body, headers, charsetOverride)
            val response = execute(client.newCall(request))
            if (response.code in RETRY_CODES && attempt < MAX_RETRIES) {
                response.close()
                delay(retryBaseDelayMs shl attempt) // 指数退避：base、base*2
                attempt++
                continue
            }
            response.use { resp ->
                if (throwOnHttpError && !resp.isSuccessful) throw IOException("HTTP ${resp.code}: $url")
                val bytes = withContext(Dispatchers.IO) { readBounded(resp, url) }
                val charset = detectCharset(charsetOverride, resp.header("Content-Type"), bytes)
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
            if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
            if (k.equals("Content-Type", ignoreCase = true)) contentType = v
            b.header(k, v)
        }
        if (!hasUa) b.header("User-Agent", DEFAULT_UA)

        if (method.equals("POST", ignoreCase = true)) {
            val mediaType = (contentType ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
            val cs = charsetOverride?.let { runCatching { charsetOf(it) }.getOrNull() } ?: Charsets.UTF_8
            b.post((body ?: "").toByteArray(cs).toRequestBody(mediaType))
        } else {
            b.method(method.uppercase(), null)
        }
        return b.build()
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

/** 按 host 的内存 Cookie 存储。host 上限 48，超出按访问顺序淘汰，避免长会话无限涨。 */
private class MemoryCookieJar : CookieJar {

    private val lock = Any()
    private val store = object : LinkedHashMap<String, MutableList<Cookie>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<Cookie>>?): Boolean =
            size > 48
    }

    fun clear(host: String) {
        synchronized(lock) { store.remove(host) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { c ->
                list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val list = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            list.removeAll { it.expiresAt < now }
            return list.filter { it.matches(url) }
        }
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
