package com.radium.inkwell.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 从 GitHub Releases 检查更新（仓库 = 本项目开源地址） */
class UpdateChecker(
    private val http: OkHttpClient = OkHttpClient(),
    private val repo: String = REPO,
) {

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    data class UpdateInfo(
        val latestVersion: String,
        val notes: String,
        val htmlUrl: String,
        /** Release 附件里的 APK 直链；无附件时为 null（跳 Release 页面） */
        val apkUrl: String?,
    )

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Available(val info: UpdateInfo) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(request).execute().use { resp ->
                if (resp.code == 404) return@withContext CheckResult.UpToDate // 尚无任何 Release
                if (!resp.isSuccessful) return@withContext CheckResult.Failed("HTTP ${resp.code}")
                val release = json.decodeFromString<GithubRelease>(resp.body?.string().orEmpty())
                val latest = release.tagName.removePrefix("v")
                if (isNewer(latest, currentVersion)) {
                    CheckResult.Available(
                        UpdateInfo(
                            latestVersion = latest,
                            notes = release.body?.trim().orEmpty(),
                            htmlUrl = release.htmlUrl,
                            apkUrl = release.assets
                                .firstOrNull { it.name.endsWith(".apk") }
                                ?.downloadUrl,
                        )
                    )
                } else {
                    CheckResult.UpToDate
                }
            }
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "网络错误")
        }
    }

    companion object {
        const val REPO = "radiumCN/inkwell"
        const val REPO_URL = "https://github.com/radiumCN/inkwell"

        /** 语义化版本比较；非数字后缀忽略（"1.2.0-beta" 按 1.2.0），缺位补 0 */
        fun isNewer(latest: String, current: String): Boolean {
            val l = parse(latest)
            val c = parse(current)
            for (i in 0 until maxOf(l.size, c.size)) {
                val a = l.getOrElse(i) { 0 }
                val b = c.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        private fun parse(version: String): List<Int> =
            version.trim().removePrefix("v")
                .split(".")
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }
}
