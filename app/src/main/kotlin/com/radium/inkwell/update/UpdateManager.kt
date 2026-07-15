package com.radium.inkwell.update

import android.content.Context
import java.io.File

/**
 * 更新门面：按用户选的 [UpdateSource] 路由检查，并封装中转服务器源的下载/安装。
 * UI 只跟它打交道，不必知道背后是 GitHub 还是中转服务器。
 */
class UpdateManager(
    private val github: UpdateChecker,
    private val server: ServerUpdateChecker,
    private val installer: UpdateInstaller,
) {

    suspend fun check(
        source: UpdateSource,
        channel: UpdateChannel,
        versionName: String,
    ): CheckResult = provider(source).check(channel, versionName)

    private fun provider(source: UpdateSource): UpdateProvider = when (source) {
        UpdateSource.GITHUB -> github
        UpdateSource.SERVER -> server
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
