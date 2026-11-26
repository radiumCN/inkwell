package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.StrResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 标准化的 HTTP origin，保留协议和端口，用于核对登录回跳和凭据的发送范围。 */
fun HttpUrl.sourceOrigin(): HttpUrl = newBuilder().encodedPath("/").query(null).fragment(null)
    .username("").password("").build()

/**
 * 来源（this）的登录凭据能不能发给 [target]：同一个站点就行 —— 注册域名相同，`www.a.com` 是主页、接口在 `api.a.com`
 * 是常见分法，只认主页那一个源的话，登录了接口照样拿不到 token。第三方（图床、跳去的别家站）不给。
 * IP、localhost 这类没有注册域名的只认同一个源；https 的不降级发给 http。
 * 公共后缀表查不出来（Android 上表在 assets 里、靠 androidx-startup 初始化）也退回只认同一个源
 */
internal fun HttpUrl.sharesSiteWith(target: HttpUrl): Boolean {
    if (isHttps && !target.isHttps) return false
    if (sourceOrigin() == target.sourceOrigin()) return true
    val site = runCatching { topPrivateDomain() }.getOrNull() ?: return false
    return site == runCatching { target.topPrivateDomain() }.getOrNull()
}

fun BookSourceRule.allowsLoginReturn(start: HttpUrl, current: HttpUrl): Boolean =
    current.isHttps && (current.sourceOrigin() == start.sourceOrigin() ||
        current.sourceOrigin() == baseUrl.toHttpUrlOrNull()?.sourceOrigin())

/** 网页型来源直接打开登录入口；脚本型来源把 loginUrl 当代码。 */
val BookSourceRule.webLoginUrl: String?
    get() = loginUrl?.trim()?.toHttpUrlOrNull()?.toString()

internal val BookSourceRule.loginScript: String?
    get() = loginUrl?.trim()?.takeIf { it.isNotEmpty() && webLoginUrl == null }
        ?.let { headerScriptOf(it) ?: it }

/** 登录数据按规则版本隔离，平台保管会话，来源 JSON 不包含这些数据。 */
interface SourceSessionStore {
    fun read(sourceId: String, key: SourceSessionKey): String?
    fun write(sourceId: String, key: SourceSessionKey, value: String?)
    fun writeAll(sourceId: String, changes: Map<SourceSessionKey, String?>) {
        changes.forEach { (key, value) -> write(sourceId, key, value) }
    }
}

enum class SourceSessionKey { INFO, HEADERS, VARIABLE, COOKIES, WEB_ORIGINS, WEB_USER_AGENTS, SEARCH_ORIGIN }

enum class SourceLoginDiagnostic(val message: String) {
    SCRIPT_FAILED("登录脚本执行失败，请检查规则或脚本接口兼容性"),
    VERIFICATION_FAILED("登录校验脚本执行失败，请检查来源规则"),
    AUTH_REQUIRED("站点要求登录，已有会话可能已失效"),
}

class SourceLoginException(val diagnostic: SourceLoginDiagnostic) : SourceException(diagnostic.message)

fun SourceSessionStore?.webLoginOrigins(sessionId: String): Set<String> =
    this?.read(sessionId, SourceSessionKey.WEB_ORIGINS)?.let {
        runCatching { Json.decodeFromString<Set<String>>(it) }.getOrNull()
    }.orEmpty()

fun SourceSessionStore?.webLoginUserAgents(sessionId: String): Map<String, String> =
    this?.read(sessionId, SourceSessionKey.WEB_USER_AGENTS)?.let {
        runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull()
    }.orEmpty()

fun SourceSessionStore?.webLoginUserAgent(sessionId: String, url: HttpUrl): String? {
    val origin = url.sourceOrigin().toString()
    return if (origin in webLoginOrigins(sessionId)) webLoginUserAgents(sessionId)[origin] else null
}

data class SourceLoginForm(
    val fields: List<SourceLoginField>,
    val values: Map<String, String>,
    val canSubmit: Boolean,
)

@Serializable
data class SourceLoginField(
    val name: String = "",
    val type: String = "text",
    private val default: JsonPrimitive? = null,
    val placeholder: String? = null,
    val action: String? = null,
    private val title: String? = null,
    private val url: String? = null,
    private val chars: JsonElement? = null,
) {
    val isButton get() = type.equals("button", ignoreCase = true)
    val isLabel get() = type.equals("label", ignoreCase = true)
    val defaultValue get() = default?.contentOrNull.orEmpty()
    val command get() = action?.takeIf(String::isNotBlank) ?: url?.takeIf(String::isNotBlank)
    val choices get() = when (val options = chars) {
        is JsonArray -> options.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> options.contentOrNull.orEmpty().split(',', '\n').map(String::trim).filter(String::isNotEmpty)
        else -> emptyList()
    }

    internal fun withName(): SourceLoginField = when {
        name.isNotBlank() -> this
        command != null -> copy(name = title.orEmpty(), type = "button")
        else -> copy(name = title.orEmpty(), type = "label")
    }
}

/** 只有用户主动打开的登录面板可以让脚本打开网页或显示提示。 */
interface SourceLoginInteraction {
    fun message(text: String)
    fun updateFields(values: Map<String, String>)
    suspend fun openBrowser(url: String, title: String): StrResponse?
    fun onHttpStatus(status: Int) {}
}

internal fun parseSourceLoginFields(text: String): List<SourceLoginField> {
    val controls = BookSourceJson.parseToJsonElement(text) as? JsonArray
        ?: throw SourceException("无法解析来源登录表单")
    return controls.mapNotNull { item ->
        val field = item as? JsonObject ?: return@mapNotNull null
        BookSourceJson.decodeFromJsonElement(SourceLoginField.serializer(), field).withName()
            .takeIf { it.name.isNotBlank() }
    }
}

internal fun loginValues(text: String?): Map<String, String> = text?.let {
    runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull()
}.orEmpty()
