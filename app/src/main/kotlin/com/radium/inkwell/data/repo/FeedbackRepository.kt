package com.radium.inkwell.data.repo

import com.radium.inkwell.data.net.InkwellServer
import com.radium.inkwell.data.prefs.FeedbackReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MultipartBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 提交结果。**每个分支对应服务端一个明确的状态码**，而不是笼统的成功/失败 ——
 * 用户该做什么完全取决于是哪一种：改内容、等一会儿、还是干脆别再点了。
 */
sealed interface FeedbackResult {
    /** 201。[url] 是公开 issue 地址，可以给用户一个「查看进展」的出口 */
    data class Success(val issue: Int, val url: String, val replyToken: String? = null) : FeedbackResult

    /** 400：content 为空或请求体非法 */
    data object InvalidContent : FeedbackResult
    data object InvalidAttachment : FeedbackResult

    /** 413：超过 [MAX_CONTENT_LENGTH] 字。UI 层本该拦住，走到这里说明拦漏了 */
    data object TooLong : FeedbackResult
    data object AttachmentTooLarge : FeedbackResult
    data object Pending : FeedbackResult

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

enum class FeedbackIssueStatus(val label: String) {
    OPEN("未关闭"),
    COMPLETED("已完成"),
    NOT_PLANNED("不计划处理"),
    DUPLICATE("重复反馈"),
    CLOSED("已关闭"),
}

@Serializable
data class FeedbackComment(
    val id: Long,
    val body: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val user: FeedbackCommentUser? = null,
) {
    val message: String get() = body.removePrefix(APP_REPLY_PREFIX)
    val authorLabel: String
        get() = if (body.startsWith(APP_REPLY_PREFIX)) "反馈提交者"
        else user?.login?.takeIf(String::isNotBlank) ?: "未知作者"
}

@Serializable
data class FeedbackCommentUser(val login: String = "")

private const val APP_REPLY_PREFIX = "**反馈提交者（来自 Inkwell）**\n\n"

data class FeedbackDiscussion(
    val status: FeedbackIssueStatus?,
    val comments: List<FeedbackComment>,
    val nextCursor: String?,
)

enum class FeedbackLogUploadResult {
    SUCCESS,
    FORBIDDEN,
    TOO_LARGE,
    RATE_LIMITED,
    UNCONFIRMED,
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

    private val replyHttp = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).build()
    private val attachmentHttp = replyHttp.newBuilder()
        .callTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun discussion(receipt: FeedbackReceipt, cursor: String? = null): FeedbackDiscussion? =
        withContext(Dispatchers.IO) {
            val repo = feedbackRepo(receipt) ?: return@withContext null
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegments("api/v1/feedback")?.addPathSegment(receipt.issue.toString())
                ?.addQueryParameter("repo", repo)
                ?.apply { if (!cursor.isNullOrEmpty()) addQueryParameter("after", cursor) }
                ?.build() ?: return@withContext null
            try {
                val call = http.newCall(Request.Builder().url(url).build())
                call.timeout().timeout(15, TimeUnit.SECONDS)
                call.execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val info = json.decodeFromString<DiscussionResponse>(response.body.string())
                    val status = when (info.state) {
                        "open" -> FeedbackIssueStatus.OPEN
                        "closed" -> when (info.reason) {
                            "completed" -> FeedbackIssueStatus.COMPLETED
                            "not_planned" -> FeedbackIssueStatus.NOT_PLANNED
                            "duplicate" -> FeedbackIssueStatus.DUPLICATE
                            else -> FeedbackIssueStatus.CLOSED
                        }
                        else -> null
                    }
                    FeedbackDiscussion(status, info.comments, info.nextCursor?.takeIf { it.isNotBlank() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    suspend fun reply(receipt: FeedbackReceipt, body: String): FeedbackComment? = withContext(Dispatchers.IO) {
        val token = receipt.replyToken ?: return@withContext null
        if (receipt.issue <= 0 || body.isBlank() || body.length > MAX_CONTENT_LENGTH) return@withContext null
        val request = Request.Builder().url("$baseUrl/api/v1/feedback/${receipt.issue}/comments")
            .header("Authorization", "Bearer $token")
            .post(json.encodeToString(ReplyBody(body)).toRequestBody(JSON_MEDIA)).build()
        try {
            val call = replyHttp.newCall(request)
            call.timeout().timeout(25, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (response.code != 201) return@withContext null
                json.decodeFromString<FeedbackComment>(response.body.string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun appendLogs(receipt: FeedbackReceipt, uploadId: String, logs: File): FeedbackLogUploadResult =
        withContext(Dispatchers.IO) {
            val token = receipt.replyToken?.takeIf(String::isNotBlank)
                ?: return@withContext FeedbackLogUploadResult.FORBIDDEN
            if (feedbackRepo(receipt) == null) return@withContext FeedbackLogUploadResult.FORBIDDEN
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegments("api/v1/feedback")?.addPathSegment(receipt.issue.toString())
                ?.addPathSegment("logs")?.addPathSegment(uploadId)?.build()
                ?: return@withContext FeedbackLogUploadResult.UNCONFIRMED
            try {
                val request = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token")
                    .put(logs.asRequestBody(ZIP_MEDIA))
                    .build()
                attachmentHttp.newCall(request).execute().use { response ->
                    when (response.code) {
                        201, 204 -> FeedbackLogUploadResult.SUCCESS
                        403 -> FeedbackLogUploadResult.FORBIDDEN
                        413 -> FeedbackLogUploadResult.TOO_LARGE
                        429 -> FeedbackLogUploadResult.RATE_LIMITED
                        else -> FeedbackLogUploadResult.UNCONFIRMED
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FeedbackLogUploadResult.UNCONFIRMED
            }
        }

    private fun feedbackRepo(receipt: FeedbackReceipt): String? {
        val issue = receipt.url.toHttpUrlOrNull() ?: return null
        val parts = issue.pathSegments
        if (!issue.isHttps || issue.host != "github.com" || issue.port != 443 ||
            parts.size != 4 || parts[2] != "issues" || parts[3].toIntOrNull() != receipt.issue || receipt.issue <= 0
        ) return null
        return "${parts[0]}/${parts[1]}"
    }

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
        requestId: String? = null,
        logs: File? = null,
        replyNonce: String? = null,
        attachments: File? = null,
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
                requestId = requestId,
                replyNonce = replyNonce,
            ),
        )
        val requestBody = if (logs == null && attachments == null) body.toRequestBody(JSON_MEDIA) else {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("payload", body)
            logs?.let { multipart.addFormDataPart("logs", "logs.zip", it.asRequestBody(ZIP_MEDIA)) }
            attachments?.let {
                multipart.addFormDataPart("attachments", "attachments.zip", it.asRequestBody(ZIP_MEDIA))
            }
            multipart.build()
        }
        val req = Request.Builder()
            .url("$baseUrl/api/v1/feedback")
            .post(requestBody)
            .build()
        try {
            val client = if (attachments == null) http else attachmentHttp
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    201 -> {
                        val dto = json.decodeFromString<Response>(resp.body.string())
                        FeedbackResult.Success(dto.issue, dto.url, dto.replyToken)
                    }
                    400 -> if (attachments == null) FeedbackResult.InvalidContent else FeedbackResult.InvalidAttachment
                    413 -> if (logs != null || attachments != null) FeedbackResult.AttachmentTooLarge else FeedbackResult.TooLong
                    409 -> FeedbackResult.Pending
                    429 -> FeedbackResult.RateLimited(
                        resp.header("Retry-After")?.trim()?.toLongOrNull(),
                    )
                    502, 503 -> {
                        val error = runCatching { json.decodeFromString<ErrorResponse>(resp.body.string()) }.getOrNull()
                        if (error?.code == "result_pending") FeedbackResult.Pending else FeedbackResult.ServerDown
                    }
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
        @SerialName("request_id") val requestId: String? = null,
        @SerialName("reply_nonce") val replyNonce: String? = null,
    )

    @Serializable
    private data class ReplyBody(val body: String)

    @Serializable
    private data class ErrorResponse(val code: String = "")

    @Serializable
    private data class DiscussionResponse(
        val state: String,
        @SerialName("state_reason") val reason: String? = null,
        val comments: List<FeedbackComment>,
        @SerialName("next_cursor") val nextCursor: String? = null,
    )

    @Serializable
    private data class Response(
        val status: String = "",
        val issue: Int = 0,
        val url: String = "",
        @SerialName("reply_token") val replyToken: String? = null,
    )

    companion object {
        /** 服务端上限；UI 层照这个数拦，别等 413 回来才告诉用户白写了 */
        const val MAX_CONTENT_LENGTH = 4000

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val ZIP_MEDIA = "application/zip".toMediaType()
    }
}
