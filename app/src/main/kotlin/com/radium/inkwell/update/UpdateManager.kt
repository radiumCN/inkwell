package com.radium.inkwell.update

import android.content.Context
import com.radium.inkwell.util.UsageLog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 更新门面：按用户选的 [UpdateSource] 路由检查，并封装中转服务器源的下载/安装。
 * UI 只跟它打交道，不必知道背后是 GitHub 还是中转服务器。
 */
class UpdateManager(
    private val github: UpdateChecker,
    private val server: ServerUpdateChecker,
    private val installer: UpdateInstaller,
    private val readGithubRetryAt: suspend () -> Long,
    private val saveGithubRetryAt: suspend (Long) -> Unit,
) {

    private val githubCheck = Mutex()
    private var githubRetryAt = 0L

    suspend fun check(
        source: UpdateSource,
        channel: UpdateChannel,
        versionName: String,
        trigger: String,
    ): CheckResult {
        val result = when (source) {
            UpdateSource.SERVER -> server.check(channel, versionName)
            UpdateSource.GITHUB -> checkGithub(channel, versionName)
        }
        val failure = result as? CheckResult.Failed
        UsageLog.event(
            "update.check.result",
            "trigger" to trigger, "source" to source.name, "channel" to channel.name,
            "result" to when (result) {
                is CheckResult.Available -> "available"
                CheckResult.UpToDate -> "up_to_date"
                is CheckResult.Failed -> "failed"
            },
            "http_status" to failure?.httpStatus, "limit_signal" to failure?.limitSignal,
            "retry_at_ms" to failure?.retryAt, "response_marker" to failure?.responseMarker,
        )
        return result
    }

    private suspend fun checkGithub(channel: UpdateChannel, version: String): CheckResult = githubCheck.withLock {
        if (githubRetryAt == 0L) {
            githubRetryAt = try { readGithubRetryAt() } catch (_: IOException) { 0L }
        }
        if (githubRetryAt > System.currentTimeMillis()) {
            return@withLock CheckResult.Failed("GitHub 更新请求仍在等待重试", retryAt = githubRetryAt, limitSignal = "cooldown")
        }
        val result = github.check(channel, version)
        if (result is CheckResult.Failed && result.retryAt != null) {
            githubRetryAt = result.retryAt
            try {
                saveGithubRetryAt(githubRetryAt)
            } catch (_: IOException) {
                // 偏好写入失败时，本进程仍遵守退避，限流原因照常交给界面。
            }
        }
        result
    }

    /** 中转服务器源专用：下载 + 校验 sha256，返回 APK 文件 */
    suspend fun downloadAndVerify(
        install: DirectInstall,
        cacheDir: File,
        onProgress: (Float) -> Unit,
    ): File = installer.downloadAndVerify(install, cacheDir, onProgress)

    /** @return true 已拉起安装；false 需先授予「安装未知应用」权限 */
    fun install(context: Context, apk: File): Boolean = installer.install(context, apk)
}
