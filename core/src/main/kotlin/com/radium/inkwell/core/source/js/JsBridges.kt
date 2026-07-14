package com.radium.inkwell.core.source.js

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import com.radium.inkwell.core.source.splitUrlOptions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    /** 同步抓取（脚本是同步的，只能阻塞）；失败返回 null */
    fun fetch(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): String?
    fun cookieOf(url: String): String
    fun setCookie(url: String, cookie: String)
    fun removeCookie(url: String)
}

/** 带 TTL 的 KV；`cache.*` 与 `java.cache*` 用 */
class JsCache {
    private class Entry(val value: String, val expireAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    /** @param saveTime 秒；<= 0 表示不过期 */
    @JvmOverloads
    fun put(key: String, value: String, saveTime: Int = 0): String {
        val expire = if (saveTime > 0) System.currentTimeMillis() + saveTime * 1000L else Long.MAX_VALUE
        map[key] = Entry(value, expire)
        return value
    }

    fun get(key: String): String? {
        val e = map[key] ?: return null
        if (e.expireAt < System.currentTimeMillis()) {
            map.remove(key)
            return null
        }
        return e.value
    }

    fun delete(key: String) {
        map.remove(key)
    }
}

/**
 * 书源级键值 + 用户变量，脚本里是 `source`。
 *
 * 目前只在进程内存活。跨会话持久化（登录 token 那类）要落库，属于后续工作 ——
 * 大多数规则的 put/get 都发生在同一条抓取链路内，进程内足够。
 */
class SourceBridge(private val key: String, private val store: MutableMap<String, String> = ConcurrentHashMap()) {

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
}

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
class BookBridge(
    @JvmField var name: String = "",
    @JvmField var author: String = "",
    @JvmField var bookUrl: String = "",
    @JvmField var tocUrl: String = "",
    @JvmField var kind: String = "",
    @JvmField var intro: String = "",
    @JvmField var coverUrl: String = "",
) {
    private val variables = ConcurrentHashMap<String, String>()

    fun putVariable(key: String, value: String?): String {
        variables[key] = value.orEmpty()
        return value.orEmpty()
    }

    fun getVariable(key: String): String = variables[key].orEmpty()
}

class ChapterBridge(
    @JvmField var title: String = "",
    @JvmField var url: String = "",
    @JvmField var index: Int = 0,
) {
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
) {

    // ---- 网络 ----

    /**
     * 与 Legado 一致：地址可自带 `,{'method':'POST','body':…,'headers':…}` 选项，
     * 且**失败时返回错误文本而不是 null** —— 书源脚本普遍直接写 `java.ajax(url).match(…)`，
     * 返回 null 会在 JS 里抛 TypeError，把整条脚本连同书源一起搞死（思路客、大文学等一批源就死在这）。
     */
    fun ajax(url: String): String = fetchWithOptions(url, "GET", null, emptyMap())

    fun connect(url: String): String = fetchWithOptions(url, "GET", null, emptyMap())

    fun connect(url: String, header: String?): String =
        fetchWithOptions(url, "GET", null, parseHeaders(header))

    private fun fetchWithOptions(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): String {
        val split = splitUrlOptions(url)
        val bare = split?.first ?: url
        val opt = split?.second
        return http?.fetch(
            bare,
            method = opt?.method ?: method,
            body = opt?.body ?: body,
            headers = headers + opt?.headers.orEmpty(),
        ) ?: ""
    }

    // ---- 交互（我们没有 UI 回调，作空实现）----
    // 缺一个方法，Rhino 就是 ReferenceError，整个书源直接判死。Legado 有的这些入口
    // 宁可给空壳：脚本照常往下走，最坏是拿不到人机验证后的页面，而不是整源不可用。

    fun startBrowser(url: String, title: String?): String = ""

    fun startBrowserAwait(url: String, title: String?): String = ""

    fun getVerificationCode(url: String): String = ""

    fun get(url: String, headers: Map<*, *>?): String =
        fetchWithOptions(url, "GET", null, toStringMap(headers))

    fun post(url: String, body: String?, headers: Map<*, *>?): String =
        fetchWithOptions(url, "POST", body, toStringMap(headers))

    fun head(url: String, headers: Map<*, *>?): String? =
        http?.fetch(url, method = "HEAD", headers = toStringMap(headers))

    // ---- 变量（同一条抓取链路内传值）----

    fun put(key: String, value: String): String {
        vars[key] = value
        return value
    }

    fun get(key: String): String = vars[key].orEmpty()

    fun getCookie(url: String): String = http?.cookieOf(url).orEmpty()

    // ---- 编解码 ----

    fun base64Encode(str: String): String =
        Base64.getEncoder().encodeToString(str.toByteArray(Charsets.UTF_8))

    fun base64Decode(str: String): String = String(decodeBase64(str), Charsets.UTF_8)

    fun base64DecodeToByteArray(str: String): ByteArray = decodeBase64(str)

    fun md5Encode(str: String): String = digest("MD5", str.toByteArray(Charsets.UTF_8))

    /** Legado 的 16 位 MD5：取 32 位的中间 16 位 */
    fun md5Encode16(str: String): String = md5Encode(str).substring(8, 24)

    fun digestHex(algorithm: String, str: String): String =
        digest(algorithm, str.toByteArray(Charsets.UTF_8))

    fun hexEncodeToString(str: String): String =
        str.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    fun hexDecodeToString(hex: String): String = String(hexToBytes(hex), Charsets.UTF_8)

    fun hexDecodeToByteArray(hex: String): ByteArray = hexToBytes(hex)

    fun strToBytes(str: String): ByteArray = str.toByteArray(Charsets.UTF_8)

    fun strToBytes(str: String, charset: String): ByteArray = str.toByteArray(charsetOf(charset))

    fun bytesToStr(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    fun bytesToStr(bytes: ByteArray, charset: String): String = String(bytes, charsetOf(charset))

    fun encodeURI(str: String): String = URLEncoder.encode(str, "UTF-8")

    fun encodeURI(str: String, enc: String): String = URLEncoder.encode(str, enc)

    // ---- 对称加密（书源常用 AES 解正文）----

    fun aesDecodeToString(data: String, key: String, transformation: String, iv: String): String =
        String(aes(Cipher.DECRYPT_MODE, data.toByteArray(Charsets.UTF_8), key, transformation, iv), Charsets.UTF_8)

    fun aesBase64DecodeToString(data: String, key: String, transformation: String, iv: String): String =
        String(aes(Cipher.DECRYPT_MODE, decodeBase64(data), key, transformation, iv), Charsets.UTF_8)

    fun aesEncodeToBase64String(data: String, key: String, transformation: String, iv: String): String =
        Base64.getEncoder().encodeToString(
            aes(Cipher.ENCRYPT_MODE, data.toByteArray(Charsets.UTF_8), key, transformation, iv)
        )

    // ---- 时间/文本/杂项 ----

    fun timeFormat(time: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(time))

    fun htmlFormat(str: String): String = org.jsoup.Jsoup.parse(str).text()

    fun randomUUID(): String = UUID.randomUUID().toString()

    /** 书源用它打调试日志；返回原值方便串在表达式里 */
    fun log(msg: Any?): Any? = msg

    fun toast(msg: Any?): Any? = msg

    fun longToast(msg: Any?): Any? = msg

    fun cacheGet(key: String): String? = cache.get(key)

    @JvmOverloads
    fun cachePut(key: String, value: String, saveTime: Int = 0): String = cache.put(key, value, saveTime)

    // ---- 内部 ----

    private fun aes(mode: Int, data: ByteArray, key: String, transformation: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        if (iv.isEmpty() || !transformation.contains("CBC", ignoreCase = true)) {
            cipher.init(mode, keySpec)
        } else {
            cipher.init(mode, keySpec, IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
        }
        return cipher.doFinal(data)
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    /** 书源里的 base64 常缺 padding 或用 URL 变体，宽松处理 */
    private fun decodeBase64(str: String): ByteArray {
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
