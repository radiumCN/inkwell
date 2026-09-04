package com.radium.inkwell.data.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OfficialWebDavException(
    val httpStatus: Int,
    val errorCode: String,
    override val message: String,
) : IOException(message)

@Serializable
data class OfficialTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class OfficialUser(
    val username: String,
    val email: String = "",
)

@Serializable
data class OfficialPublicConfig(
    @SerialName("registration_enabled") val registrationEnabled: Boolean = false,
    @SerialName("webdav_url") val webdavUrl: String = "",
)

@Serializable
data class OfficialAuthData(
    val user: OfficialUser,
    val tokens: OfficialTokens,
)

@Serializable
data class OfficialMe(
    val user: OfficialUser,
    @SerialName("webdav_url") val webdavUrl: String = "",
    @SerialName("webdav_user") val webdavUser: String = "",
)

@Serializable
data class OfficialAppPassword(
    val id: Long = 0,
    val name: String = "",
    val hint: String = "",
    val secret: String = "",
)

@Serializable
data class OfficialAppPasswordList(
    val items: List<OfficialAppPassword> = emptyList(),
)

/**
 * 官方 WebDAV 账号 API。日常同步仍走 [com.radium.inkwell.core.webdav.WebDavClient]，
 * 这里只覆盖注册 / 登录 / 找回 / 发应用码。
 *
 * 响应是 `{ ok, data, error }` 信封；失败用 [OfficialWebDavException] 把服务端 message 原样抛出，
 * UI 直接展示，不必再猜 HTTP 码。
 */
class OfficialWebDavClient(
    private val http: OkHttpClient,
    private val apiBase: String = OfficialWebDav.API,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun publicConfig(): OfficialPublicConfig =
        request(GET, "/api/v1/public/config")

    suspend fun sendRegisterCode(email: String) {
        request<Sent>(POST, "/api/v1/auth/send-code", """{"email":${email.json()}}""")
    }

    suspend fun register(username: String, email: String, password: String, code: String): OfficialAuthData =
        request(
            POST,
            "/api/v1/auth/register",
            """{"username":${username.json()},"email":${email.json()},"password":${password.json()},"code":${code.json()}}""",
        )

    suspend fun login(login: String, password: String): OfficialAuthData =
        request(
            POST,
            "/api/v1/auth/login",
            """{"login":${login.json()},"password":${password.json()}}""",
        )

    suspend fun sendLoginCode(email: String) {
        request<Sent>(POST, "/api/v1/auth/send-login-code", """{"email":${email.json()}}""")
    }

    suspend fun loginWithCode(email: String, code: String): OfficialAuthData =
        request(
            POST,
            "/api/v1/auth/login-code",
            """{"email":${email.json()},"code":${code.json()}}""",
        )

    suspend fun sendResetCode(email: String) {
        request<Sent>(POST, "/api/v1/auth/send-reset-code", """{"email":${email.json()}}""")
    }

    suspend fun resetPassword(email: String, code: String, password: String): OfficialAuthData =
        request(
            POST,
            "/api/v1/auth/reset-password",
            """{"email":${email.json()},"code":${code.json()},"password":${password.json()}}""",
        )

    suspend fun me(accessToken: String): OfficialMe =
        request(GET, "/api/v1/me", access = accessToken)

    suspend fun refresh(refreshToken: String): OfficialTokens =
        request(POST, "/api/v1/auth/refresh", """{"refresh_token":${refreshToken.json()}}""")

    suspend fun listAppPasswords(accessToken: String): OfficialAppPasswordList =
        request(GET, "/api/v1/app-passwords", access = accessToken)

    suspend fun createAppPassword(accessToken: String, name: String, replace: Boolean = true): OfficialAppPassword =
        request(
            POST,
            "/api/v1/app-passwords",
            """{"name":${name.json()},"replace":$replace}""",
            access = accessToken,
        )

    suspend fun deleteAppPassword(accessToken: String, id: Long) {
        request<Sent>(DELETE, "/api/v1/app-passwords/$id", access = accessToken)
    }

    suspend fun logout(accessToken: String, refreshToken: String) {
        runCatching {
            request<Sent>(
                POST,
                "/api/v1/auth/logout",
                """{"refresh_token":${refreshToken.json()}}""",
                access = accessToken,
            )
        }
    }

    private suspend inline fun <reified T> request(
        method: String,
        path: String,
        body: String? = null,
        access: String? = null,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$apiBase$path")
        if (!access.isNullOrBlank()) builder.header("Authorization", "Bearer $access")
        when (method) {
            GET -> builder.get()
            else -> builder.method(method, (body ?: "{}").toRequestBody(JSON))
        }
        try {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body.string()
                parse(text, resp.code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OfficialWebDavException) {
            throw e
        } catch (e: Exception) {
            throw OfficialWebDavException(0, "network", e.message ?: "网络错误")
        }
    }

    private inline fun <reified T> parse(body: String, httpStatus: Int): T {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw OfficialWebDavException(httpStatus, "bad_response", "服务器响应无法解析")
        }
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) {
            val err = root["error"]?.jsonObject
            throw OfficialWebDavException(
                httpStatus,
                err?.get("code")?.jsonPrimitive?.content ?: "",
                err?.get("message")?.jsonPrimitive?.content ?: "请求失败",
            )
        }
        val data = root["data"] ?: throw OfficialWebDavException(httpStatus, "bad_response", "响应缺少 data")
        return json.decodeFromJsonElement<T>(data)
    }

    @Serializable
    private data class Sent(val sent: Boolean = true)

    private companion object {
        const val GET = "GET"
        const val POST = "POST"
        const val DELETE = "DELETE"
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun String.json(): String = Json.encodeToString(this)
    }
}
