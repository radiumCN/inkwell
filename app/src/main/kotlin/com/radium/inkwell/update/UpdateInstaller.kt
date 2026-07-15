package com.radium.inkwell.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 中转服务器更新的下载 + 校验 + 安装。
 *
 * 下载边下边算 sha256，与服务端给的对不上就删文件报错（绝不安装未校验的包）。
 * 安装走系统安装器（ACTION_VIEW + FileProvider），需要「安装未知应用」权限。
 */
class UpdateInstaller(private val http: OkHttpClient) {

    /**
     * 下载并校验 sha256，返回落地的 APK 文件。校验不过会删文件并抛异常。
     * [onProgress] 回调 0f..1f（Compose State 可跨线程写，这里在 IO 线程回调）。
     */
    suspend fun downloadAndVerify(
        install: DirectInstall,
        cacheDir: File,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        // 清掉上次的残留：避免占空间，也避免装到半截的旧包
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, install.filename)

        val req = Request.Builder().url(install.url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("下载失败: 空响应")
            val total = install.size.takeIf { it > 0 } ?: body.contentLength()
            val md = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(install.sha256, ignoreCase = true)) {
                out.delete()
                throw IOException("文件校验失败（sha256 不一致）")
            }
        }
        out
    }

    /**
     * 拉起系统安装器。
     * @return true 已拉起安装；false 没有「安装未知应用」权限（已跳去授权页，需用户授权后重试）。
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        // 复用书源导出用的同一个 FileProvider（authority = ${applicationId}.fileprovider）
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return true
    }
}
