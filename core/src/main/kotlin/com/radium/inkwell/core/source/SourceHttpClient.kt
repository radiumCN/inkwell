package com.radium.inkwell.core.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/** 抓取结果；bodyText 已按探测到的字符集解码 */
class FetchedPage(
    val finalUrl: String,
    val statusCode: Int,
    val bodyText: String,
    val bodyBytes: ByteArray,
    val detectedCharset: String,
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
) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .cookieJar(MemoryCookieJar())
        .build()

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    @Throws(IOException::class)
    suspend fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        charsetOverride: String? = null,
        rateLimit: RateLimitRule? = null,
    ): FetchedPage {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("非法 URL: $url")
        if (rateLimit != null && rateLimit.intervalMs > 0) {
            buckets.computeIfAbsent(httpUrl.host) { TokenBucket(rateLimit) }.acquire()
        }
        var attempt = 0
        while (true) {
            val request = buildRequest(httpUrl, method, body, headers, charsetOverride)
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.code in RETRY_CODES && attempt < MAX_RETRIES) {
                response.close()
                delay(retryBaseDelayMs shl attempt) // 指数退避：base、base*2
                attempt++
                continue
            }
            response.use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $url")
                val bytes = withContext(Dispatchers.IO) { resp.body?.bytes() ?: ByteArray(0) }
                val charset = detectCharset(charsetOverride, resp.header("Content-Type"), bytes)
                return FetchedPage(
                    finalUrl = resp.request.url.toString(),
                    statusCode = resp.code,
                    bodyText = String(bytes, charset),
                    bodyBytes = bytes,
                    detectedCharset = charset.name(),
                )
            }
        }
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
            // OkHttp 只接受 ASCII 头值，非法值会抛 IllegalArgumentException 打死整次抓取。
            // 书源常把 Referer 写成 {{baseUrl}}，而书源 URL 里可能带 emoji 后缀 —— 丢掉该头即可，
            // 不该让整个书源不可用。
            if (!isValidHeaderValue(v)) return@forEach
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

    /** 三级字符集探测 */
    private fun detectCharset(override: String?, contentTypeHeader: String?, bytes: ByteArray): Charset {
        if (override != null) {
            try {
                return charsetOf(override)
            } catch (_: Exception) {
                // 非法声明，继续向下探测
            }
        }
        contentTypeHeader?.let { header ->
            CHARSET_PARAM.find(header)?.groupValues?.get(1)?.let { name ->
                try {
                    return charsetOf(name)
                } catch (_: Exception) {
                }
            }
        }
        // 前 8KB 按 latin1 解码后嗅探 <meta charset=...> / <meta ... content="...charset=...">
        val head = String(bytes, 0, minOf(bytes.size, 8192), Charsets.ISO_8859_1)
        META_CHARSET.find(head)?.groupValues?.get(1)?.let { name ->
            try {
                return charsetOf(name)
            } catch (_: Exception) {
            }
        }
        return Charsets.UTF_8
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
        private val CHARSET_PARAM = Regex("charset\\s*=\\s*\"?([\\w-]+)", RegexOption.IGNORE_CASE)
        private val META_CHARSET = Regex("<meta[^>]+charset\\s*=\\s*['\"]?([\\w-]+)", RegexOption.IGNORE_CASE)
    }
}

/** 按 host 的内存 Cookie 存储 */
private class MemoryCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.computeIfAbsent(url.host) { mutableListOf() }
        synchronized(list) {
            cookies.forEach { c ->
                list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        synchronized(list) {
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
