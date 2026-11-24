package com.radium.inkwell.core.webdav

import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * 一次可重试的读、合并、条件写入。放在纯 JVM 层，用两个客户端交错请求验证，
 * 不把多设备竞争留给真机上的偶然时序。服务器仍需正确实现 HTTP 条件请求。
 */
class BackupSync(private val client: WebDavClient) {
    data class Result(val remoteVersion: String?)

    suspend fun run(
        path: String,
        local: suspend () -> BackupPayload,
        apply: suspend (BackupMerger.MergeResult) -> Unit,
    ): Result {
        repeat(3) {
            val snapshot = client.getSnapshot(path)
            val remote = snapshot?.let {
                try {
                    BackupCodec.decode(it.bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    throw IOException("远端备份太大，本机内存不足以解析，本次同步已跳过", e)
                } catch (e: Exception) {
                    // 不拿本地库覆盖唯一远端副本。损坏与未来版本都保留，方便恢复或升级后重试。
                    throw IOException("远端备份无法读取，已保留原文件，请检查备份或升级应用后重试", e)
                }
            }
            val current = local()
            val merged = remote?.let { BackupMerger.merge(current, it) }
            val payload = if (merged == null) current else current.copy(
                books = merged.books, sources = merged.sources,
                replaceRules = merged.replaceRules, rssSources = merged.rssSources,
                readerSettings = merged.mergedReaderSettings, appSettings = merged.mergedAppSettings,
                readingStats = merged.readingStats,
            )
            if (merged != null) apply(merged)
            if (remote != null && sameContent(payload, remote)) {
                return Result(snapshot.etag?.let { "etag:$it" })
            }
            // 无强版本标识时仍可拉取，但拒绝有丢数据风险的覆盖上传，不能默默降级成盲写。
            if (snapshot != null && snapshot.etag == null) {
                throw IOException("服务器未提供可靠的备份版本标识，已读取远端更新，但未覆盖上传；请检查 WebDAV 服务的 ETag 支持")
            }
            val encoded = BackupCodec.encode(payload)
            try {
                val written = client.putConditional(path, encoded, snapshot?.etag)
                return Result(written?.let { "etag:$it" })
            } catch (e: WebDavClient.WebDavException) {
                if (e.code != 412) throw e
                // 对面抢先写入：重新下载，再拍本地快照。上一轮已采纳的更新也会参与合并。
            } catch (e: IOException) {
                // 响应可以在服务器提交后丢失。只读回确认，下一次同步再决定是否需要写入。
                val confirmed = try {
                    client.getSnapshot(path)?.takeIf { it.bytes.contentEquals(encoded) }
                } catch (_: IOException) {
                    null
                }
                if (confirmed != null) return Result(confirmed.etag?.let { "etag:$it" })
                val localStatus = if (merged == null) "本机数据已保留" else "已下载的更新已合并到本机"
                throw IOException("服务器上的备份写入尚未确认，$localStatus。下次同步会重新读取服务器备份", e)
            }
        }
        throw IOException("其他设备正在更新备份，本次同步未完成，请稍后重试")
    }

    private fun sameContent(a: BackupPayload, b: BackupPayload): Boolean =
        a.version == b.version &&
            a.books.associateBy { it.id } == b.books.associateBy { it.id } &&
            a.sources.associateBy { it.id } == b.sources.associateBy { it.id } &&
            a.replaceRules.associateBy { it.id } == b.replaceRules.associateBy { it.id } &&
            a.rssSources.associateBy { it.id } == b.rssSources.associateBy { it.id } &&
            a.readerSettings == b.readerSettings && a.appSettings == b.appSettings &&
            a.readingStats.associateBy { it.key } == b.readingStats.associateBy { it.key }
}
