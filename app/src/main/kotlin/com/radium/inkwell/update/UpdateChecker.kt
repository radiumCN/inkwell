package com.radium.inkwell.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 更新渠道：稳定版只看正式 Release，测试版包含 prerelease */
enum class UpdateChannel(val label: String) {
    STABLE("稳定版"),
    BETA("测试版"),
}

/** 从 GitHub Releases 检查更新（仓库 = 本项目开源地址） */
class UpdateChecker(
    private val http: OkHttpClient = OkHttpClient(),
    private val repo: String = REPO,
) : UpdateProvider {

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(
        channel: UpdateChannel,
        versionName: String,
    ): CheckResult = withContext(Dispatchers.IO) {
        try {
            val path = when (channel) {
                UpdateChannel.STABLE -> "releases/latest"
                UpdateChannel.BETA -> "releases?per_page=15"
            }
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/$path")
                .header("Accept", "application/vnd.github+json")
                .build()
            val candidate = http.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext CheckResult.UpToDate
                if (!response.isSuccessful) return@withContext githubFailure(response)
                val body = response.body.string()
                when (channel) {
                    UpdateChannel.STABLE -> listOf(json.decodeFromString<GithubRelease>(body))
                    UpdateChannel.BETA -> json.decodeFromString<List<GithubRelease>>(body)
                }
            }
            val latest = candidate
                .filter { !it.draft }
                .maxWithOrNull { a, b ->
                    if (isNewer(a.tagName, b.tagName)) 1
                    else if (isNewer(b.tagName, a.tagName)) -1
                    else 0
                } ?: return@withContext CheckResult.UpToDate

            val latestVersion = latest.tagName.removePrefix("v")
            if (isNewer(latestVersion, versionName)) {
                // GitHub 不给 sha256，无法应用内校验安装 —— 走浏览器：有 APK 附件直链就下 APK，否则跳 Release 页
                val apkUrl = latest.assets.firstOrNull { it.name.endsWith(".apk") }?.downloadUrl
                CheckResult.Available(
                    UpdateInfo(
                        latestVersion = latestVersion,
                        notes = latest.body?.trim().orEmpty(),
                        isPrerelease = latest.prerelease,
                        directInstall = null,
                        browserUrl = apkUrl ?: latest.htmlUrl,
                        browserIsApk = apkUrl != null,
                    )
                )
            } else {
                CheckResult.UpToDate
            }
        } catch (e: CancellationException) {
            // 离开设置页 / 新一轮检查掐掉了这一次，不是「检查失败」
            throw e
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "网络错误")
        }
    }

    private fun githubFailure(response: Response): CheckResult.Failed {
        val code = response.code
        if (code != 403 && code != 429) return CheckResult.Failed("HTTP $code", code)
        val marker = when {
            response.header("CF-Mitigated").equals("challenge", ignoreCase = true) -> "edge_challenge"
            response.header("X-GitHub-Request-Id") != null -> "github_request_id"
            else -> "unknown"
        }
        val now = Instant.now()
        val retryAfter = response.header("Retry-After")?.trim()?.let { value ->
            runCatching {
                val seconds = value.toLongOrNull()
                if (seconds != null && seconds >= 0) now.plusSeconds(seconds).toEpochMilli()
                else ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        }
        val exhausted = response.header("X-RateLimit-Remaining") == "0"
        val signal = when {
            exhausted -> "remaining_zero"
            retryAfter != null -> "retry_after"
            code == 429 -> "http_429"
            else -> {
                // GitHub 的次级限流有时只有 JSON message。只读少量，只记录分类。
                val message = runCatching {
                    val body = json.parseToJsonElement(response.peekBody(4096).string()) as? JsonObject
                    (body?.get("message") as? JsonPrimitive)?.content
                }.getOrNull()
                if (message?.contains("rate limit", ignoreCase = true) == true) "api_message"
                else return CheckResult.Failed("GitHub 更新源拒绝了本次请求（HTTP $code）", code, responseMarker = marker)
            }
        }
        val reset = if (exhausted) response.header("X-RateLimit-Reset")?.toLongOrNull()?.let {
            runCatching { Instant.ofEpochSecond(it).toEpochMilli() }.getOrNull()
        } else null
        val retryAt = listOfNotNull(retryAfter, reset).maxOrNull()?.coerceAtLeast(now.toEpochMilli())
            ?: now.plusSeconds(60).toEpochMilli()
        return CheckResult.Failed("GitHub 更新请求暂时受限", code, retryAt, signal, marker)
    }

    companion object {
        const val REPO = "radiumCN/inkwell"
        const val REPO_URL = "https://github.com/radiumCN/inkwell"

        /**
         * 语义化版本比较，含预发布规则：主版本相同时，正式版 > 预发布版，
         * 预发布之间按标识符逐段比较（数字段按数值，字母段按字典序）。
         * 如 0.2.0-beta.1 < 0.2.0-beta.2 < 0.2.0-rc.1 < 0.2.0
         */
        fun isNewer(latest: String, current: String): Boolean =
            compare(latest, current) > 0

        private fun compare(a: String, b: String): Int {
            val (aMain, aPre) = split(a)
            val (bMain, bPre) = split(b)
            for (i in 0 until maxOf(aMain.size, bMain.size)) {
                val x = aMain.getOrElse(i) { 0 }
                val y = bMain.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            // 主版本相同：无预发布标识 = 正式版，更大
            if (aPre == null && bPre == null) return 0
            if (aPre == null) return 1
            if (bPre == null) return -1
            for (i in 0 until maxOf(aPre.size, bPre.size)) {
                val x = aPre.getOrNull(i) ?: return -1 // 段少的更小（beta < beta.1）
                val y = bPre.getOrNull(i) ?: return 1
                val xNum = x.toIntOrNull()
                val yNum = y.toIntOrNull()
                val cmp = when {
                    xNum != null && yNum != null -> xNum.compareTo(yNum)
                    xNum != null -> -1 // 数字段 < 字母段（semver 规则）
                    yNum != null -> 1
                    else -> x.compareTo(y)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }

        private fun split(version: String): Pair<List<Int>, List<String>?> {
            val v = version.trim().removePrefix("v")
            val dash = v.indexOf('-')
            val main = (if (dash < 0) v else v.substring(0, dash))
                .split(".")
                .map { it.toIntOrNull() ?: 0 }
            val pre = if (dash < 0) null else v.substring(dash + 1).split(".")
            return main to pre
        }
    }
}
