package com.radium.inkwell.data.source

import android.content.Context
import android.util.AtomicFile
import android.webkit.CookieManager
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.radium.inkwell.core.source.SourceSessionKey
import com.radium.inkwell.core.source.SourceSessionStore
import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import com.radium.inkwell.core.source.sourceOrigin
import com.radium.inkwell.core.source.webLoginOrigins
import com.radium.inkwell.data.prefs.KeystoreSecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 数据按来源隔离，密文只留在设备本机。网页会话仍由 WebView 管理过期与路径。 */
internal class SourceLoginSessions(context: Context) : SourceSessionStore {
    private val directory = File(context.noBackupFilesDir, "source-login")
    private val cipher = KeystoreSecretCipher("inkwell_source_login_v1")
    private val values = mutableMapOf<String, Map<String, String>>()
    private val managers = ConcurrentHashMap<String, CookieManager>()

    @Synchronized
    override fun read(sourceId: String, key: SourceSessionKey): String? = load(sourceId)[key.name]

    override fun write(sourceId: String, key: SourceSessionKey, value: String?) =
        writeAll(sourceId, mapOf(key to value))

    @Synchronized
    override fun writeAll(sourceId: String, changes: Map<SourceSessionKey, String?>) {
        if (changes.isEmpty()) return
        val previous = load(sourceId)
        val updated = previous.toMutableMap()
        for ((key, value) in changes) {
            if (value == null) updated.remove(key.name) else updated[key.name] = value
        }
        if (updated == previous) return
        val file = file(sourceId)
        if (updated.isEmpty()) {
            file.delete()
        } else {
            val bytes = cipher.encrypt(Json.encodeToString(updated)).toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
        values[sourceId] = updated
    }

    private fun load(sourceId: String): Map<String, String> = values.getOrPut(sourceId) {
        runCatching {
            val saved = String(file(sourceId).readFully(), Charsets.UTF_8)
            cipher.decrypt(saved)?.let { Json.decodeFromString<Map<String, String>>(it) }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    /** start 与 current 已由登录页核对获准访问。两个站点分别授权，仍由 CookieManager 校验 Cookie 作用域。 */
    fun rememberWebLogin(sessionId: String, start: HttpUrl, current: HttpUrl): Boolean {
        val manager = profileCookies(sessionId) ?: return false
        val origins = setOf(start.sourceOrigin(), current.sourceOrigin())
        val hasCookie = (origins + current).any { !manager.getCookie(it.toString()).isNullOrBlank() }
        if (!hasCookie) return false
        manager.flush()
        synchronized(this) {
            val approved = webLoginOrigins(sessionId) + origins.map(HttpUrl::toString)
            write(sessionId, SourceSessionKey.WEB_ORIGINS, Json.encodeToString(approved))
        }
        return true
    }

    fun webCookies(sessionId: String, url: HttpUrl): String? =
        if (hasWebLogin(sessionId, url)) profileCookies(sessionId)?.getCookie(url.toString()) else null

    fun hasWebLogin(sessionId: String, url: HttpUrl): Boolean =
        url.sourceOrigin().toString() in webLoginOrigins(sessionId)

    fun updateWebCookies(sessionId: String, url: HttpUrl, cookies: List<Cookie>) {
        if (!hasWebLogin(sessionId, url)) return
        val manager = profileCookies(sessionId) ?: return
        val updated = runBlocking(Dispatchers.Main.immediate) {
            withTimeoutOrNull(5_000) {
                cookies.all { cookie ->
                    suspendCancellableCoroutine { result ->
                        manager.setCookie(url.toString(), cookie.toString()) { result.resume(it) }
                    }
                }
            } ?: false
        }
        if (!updated) synchronized(this) {
            // WebView 拒收更新时先停用这个 origin 的桥接，原生 jar 已记录本次更新或删除。
            val remaining = webLoginOrigins(sessionId) - url.sourceOrigin().toString()
            write(sessionId, SourceSessionKey.WEB_ORIGINS, remaining.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) })
        }
    }

    fun clearWebCookies(sessionId: String, url: HttpUrl) {
        if (!hasWebLogin(sessionId, url)) return
        // CookieManager 无法逐站清除所有路径上的 Cookie。这个 profile 只属于一份规则。
        val manager = profileCookies(sessionId)
        synchronized(this) { write(sessionId, SourceSessionKey.WEB_ORIGINS, null) }
        if (manager == null) return
        runBlocking(Dispatchers.Main.immediate) {
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine { result -> manager.removeAllCookies { result.resume(Unit) } }
            }
        }
        manager.flush()
    }

    private fun profileCookies(sourceId: String): CookieManager? {
        managers[sourceId]?.let { return it }
        return runBlocking(Dispatchers.Main.immediate) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return@runBlocking null
            ProfileStore.getInstance().getProfile(loginProfileName(sourceId))?.cookieManager?.also {
                managers.putIfAbsent(sourceId, it)
            }
        }
    }

    /**
     * 清掉没有规则再用的会话。会话标识跟着规则内容走（见 BookSourceRule.loginSessionId）：规则一更新、来源一删，
     * 旧的加密文件和 WebView profile（Cookie、本地存储、缓存）就没人再读，一直堆着。
     * 目录里没有过期的就到此为止 —— 不查库，也不拉起 WebView（ProfileStore 要在主线程上加载它）。
     * 先列目录、再取 [liveSessionIds]：列完之后才登录的新会话，文件不在列出来的名单里，删不到它
     */
    suspend fun prune(liveSessionIds: suspend () -> Set<String>) {
        val names = withContext(Dispatchers.IO) { directory.list()?.toList().orEmpty() }
        if (names.isEmpty()) return
        val live = liveSessionIds()
        val stale = staleSessionFiles(names, live)
        if (stale.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(this@SourceLoginSessions) {
                stale.forEach { File(directory, it).delete() }
                values.keys.retainAll(live)
            }
        }
        managers.keys.retainAll(live)
        withContext(Dispatchers.Main) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return@withContext
            val store = ProfileStore.getInstance()
            val liveNames = live.mapTo(HashSet(), ::loginProfileName)
            for (name in store.allProfileNames) {
                if (!name.startsWith(PROFILE_PREFIX) || name in liveNames) continue
                // 正开着的（登录网页还没关）删不掉，下次启动再来
                runCatching { store.deleteProfile(name) }
            }
        }
    }

    private fun file(sourceId: String): AtomicFile = AtomicFile(File(directory, loginProfileName(sourceId)))
}

private const val PROFILE_PREFIX = "source-"

internal fun loginProfileName(sourceId: String): String = PROFILE_PREFIX + MessageDigest.getInstance("SHA-256")
    .digest(sourceId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** 会话目录里哪些文件没有规则再用（AtomicFile 写到一半留下的 `.new` / `.bak` 跟着它的正身走） */
internal fun staleSessionFiles(names: Collection<String>, liveSessionIds: Set<String>): List<String> {
    val live = liveSessionIds.mapTo(HashSet(), ::loginProfileName)
    return names.filter { it.substringBefore('.') !in live }
}

internal fun String.sourceLoginHttpUrl(): HttpUrl? = toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty() && !isPrivateNetworkLiteral(it.host)
}
