package com.radium.inkwell.update

import com.radium.inkwell.data.net.InkwellServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 从 inkwell 更新中转服务器检查更新。
 *
 * 服务器把上游 GitHub Release 镜像到本地，`has_update` 由服务端算好（要求带 `current`），
 * 附件带 sha256，可应用内下载→校验→安装 —— 适合 GitHub 访问受限的场景。
 */
class ServerUpdateChecker(
    private val http: OkHttpClient,
    private val baseUrl: String = InkwellServer.BASE,
) : UpdateProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(
        channel: UpdateChannel,
        versionName: String,
    ): CheckResult = withContext(Dispatchers.IO) {
        try {
            val path = when (channel) {
                UpdateChannel.STABLE -> "stable"
                UpdateChannel.BETA -> "beta"
            }
            // ⚠️ current 必须补 v 前缀：服务端拿 tag（v0.1.0-beta.63）与之字符串比对，
            // 不补的话 "0.1.0-beta.63" != "v0.1.0-beta.63" 恒真，会误报有更新。
            val url = "$baseUrl/api/v1/update/$path".toHttpUrl().newBuilder()
                .addQueryParameter("current", "v$versionName")
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val dto = json.decodeFromString<Dto>(resp.body.string())
                        // has_update 由服务端判定（传了 current 才有）；缺省时保守当作无更新。
                        // 但服务端是拿 tag **字符串**比的：不相等就算「有更新」。正式版渠道的用户装着
                        // 更新的测试版时，会被反复推一个更旧的正式版（下载完装不上：降级）；
                        // 渠道若回了更旧的预发布版（0.3.8 上推 0.3.8-beta.1），同样是下完装不上 ——
                        // versionCode 已把预发布序号算进去，系统会按降级拒掉。所以客户端再按语义化版本
                        // 比一次，只认真正更新的，别让用户白下一个包。
                        if (dto.hasUpdate == true && UpdateChecker.isNewer(dto.version, versionName)) {
                            CheckResult.Available(dto.toUpdateInfo())
                        } else {
                            CheckResult.UpToDate
                        }
                    }
                    // 该渠道当前无版本（如上游还没正式版时 stable 会 404）→ 当作无更新，不报错
                    404 -> CheckResult.UpToDate
                    else -> CheckResult.Failed("HTTP ${resp.code}", resp.code)
                }
            }
        } catch (e: CancellationException) {
            // 离开设置页 / 新一轮检查掐掉了这一次，不是「检查失败」
            throw e
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "网络错误")
        }
    }

    @Serializable
    private data class Dto(
        val version: String,
        val notes: String = "",
        @SerialName("has_update") val hasUpdate: Boolean? = null,
        val asset: Asset,
    ) {
        @Serializable
        data class Asset(
            val filename: String,
            val size: Long,
            val sha256: String,
            @SerialName("download_url") val downloadUrl: String,
            @SerialName("source_url") val sourceUrl: String = "",
        )
    }

    private fun Dto.toUpdateInfo() = UpdateInfo(
        latestVersion = version.removePrefix("v"),
        notes = notes.trim(),
        // 上游发版约定：tag 含 '-' 即预发布（v0.1.0-beta.63）
        isPrerelease = version.contains('-'),
        directInstall = DirectInstall(
            url = asset.downloadUrl,
            sha256 = asset.sha256,
            size = asset.size,
            filename = asset.filename,
        ),
        browserUrl = asset.sourceUrl.ifBlank { null },
    )
}
