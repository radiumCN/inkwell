package com.radium.inkwell.data.repo

import com.radium.inkwell.data.net.InkwellServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 提交结果。**每个分支对应服务端一个明确的状态码**，而不是笼统的成功/失败 ——
 * 用户该做什么完全取决于是哪一种：改内容、等一会儿、还是干脆别再点了。
 */
sealed interface FeedbackResult {
    /** 201。[url] 是公开 issue 地址，可以给用户一个「查看进展」的出口 */
    data class Success(val issue: Int, val url: String) : FeedbackResult

    /** 400：content 为空或请求体非法 */
    data object InvalidContent : FeedbackResult

    /** 413：超过 [MAX_CONTENT_LENGTH] 字。UI 层本该拦住，走到这里说明拦漏了 */
    data object TooLong : FeedbackResult

    /**
     * 429：限流。按**来源 IP** 计（默认每小时 5 条），
     * 同一 WiFi 下多个用户共享额度 —— 所以这不是异常，是正常会遇到的情况，措辞别吓人。
     *
     * [retryAfterSec] 来自 Retry-After 响应头；服务端没给就是 null。
     */
    data class RateLimited(val retryAfterSec: Long?) : FeedbackResult

    /**
     * 502/503：服务端或 GitHub 侧的问题。
     *
     * **绝不能自动重试** —— 服务端可能已经把 issue 建出来了，只是回程失败，
     * 重试会在公开仓库里刷出一串重复 issue。只能让用户自己决定要不要再点一次。
     */
    data object ServerDown : FeedbackResult

    /** 请求根本没发出去 / 没收到回应 */
    data class NetworkError(val message: String?) : FeedbackResult
}

/**
 * 意见反馈：POST 到中转服务器，服务端据此在公开仓库建一个 GitHub issue。
 *
 * 标题由服务端从 content 首行截取（60 字内），客户端不需要也无法自定义。
 */
class FeedbackRepository(
    private val http: OkHttpClient,
    private val baseUrl: String = InkwellServer.BASE,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /**
     * @param contact 可选。⚠️ issue 建在**公开仓库**，填了就是公开可见的 ——
     *   调用方必须先把这件事明确告诉用户，再让他填。
     */
    suspend fun submit(
        content: String,
        contact: String? = null,
        appVersion: String? = null,
        device: String? = null,
        osVersion: String? = null,
        channel: String? = null,
    ): FeedbackResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            Payload.serializer(),
            Payload(
                content = content,
                contact = contact?.takeIf { it.isNotBlank() },
                appVersion = appVersion,
                device = device,
                osVersion = osVersion,
                channel = channel,
            ),
        )
        val req = Request.Builder()
            .url("$baseUrl/api/v1/feedback")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                when (resp.code) {
                    201 -> {
                        val dto = json.decodeFromString<Response>(resp.body?.string().orEmpty())
                        FeedbackResult.Success(dto.issue, dto.url)
                    }
                    400 -> FeedbackResult.InvalidContent
                    413 -> FeedbackResult.TooLong
                    429 -> FeedbackResult.RateLimited(
                        resp.header("Retry-After")?.trim()?.toLongOrNull(),
                    )
                    502, 503 -> FeedbackResult.ServerDown
                    else -> FeedbackResult.NetworkError("HTTP ${resp.code}")
                }
            }
        } catch (e: CancellationException) {
            // 用户退出页面把请求取消了，不是"提交失败"。吞掉会让界面停在转圈上
            throw e
        } catch (e: Exception) {
            FeedbackResult.NetworkError(e.message)
        }
    }

    @Serializable
    private data class Payload(
        val content: String,
        val contact: String? = null,
        @SerialName("app_version") val appVersion: String? = null,
        val device: String? = null,
        @SerialName("os_version") val osVersion: String? = null,
        val channel: String? = null,
    )

    @Serializable
    private data class Response(val status: String = "", val issue: Int = 0, val url: String = "")

    companion object {
        /** 服务端上限；UI 层照这个数拦，别等 413 回来才告诉用户白写了 */
        const val MAX_CONTENT_LENGTH = 4000

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
