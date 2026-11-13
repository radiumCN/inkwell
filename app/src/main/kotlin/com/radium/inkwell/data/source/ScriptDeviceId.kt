package com.radium.inkwell.data.source

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * 来源脚本 `java.androidId()` 拿到的设备号（见 ScriptRuntime.androidId）。接口源拿它当设备号放进请求头
 * （Lofter 的 deviceid），没有就是空串，站点那边当成坏请求。
 *
 * 阅读给的是系统的 ANDROID_ID。我们不给它：那一串跟着签名和这台设备走，卸了重装都不变，而来源脚本会把它原样发给
 * 第三方站点。给的是同样格式（16 位十六进制）、本机第一次要时随机生成的一串，存在 noBackupFilesDir ——
 * 不进系统备份：恢复到另一台手机上的应该是另一个设备号；清数据、重装就换一个。
 * 只在第一次有脚本调它时碰一次磁盘（调它的脚本本来就在 IO 线程上）。
 */
class ScriptDeviceId(private val context: Context) {

    private val id: String by lazy { load() }

    fun get(): String = id

    private fun load(): String {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { ID_FORMAT.matches(it) }?.let { return it }
        val fresh = randomId()
        // 原子写：写到一半被杀，下次读出半截就换了号，接口源存下的登录态跟着失效
        runCatching {
            val tmp = File.createTempFile("device", ".tmp", file.parentFile)
            tmp.writeText(fresh)
            if (!tmp.renameTo(file)) tmp.delete()
        }
        return fresh
    }

    private fun randomId(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FILE_NAME = "script_device_id"
        val ID_FORMAT = Regex("[0-9a-f]{16}")
    }
}
