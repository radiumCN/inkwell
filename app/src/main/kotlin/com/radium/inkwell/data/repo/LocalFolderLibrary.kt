package com.radium.inkwell.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 目录授权与已处理文件只保存在本机，不进入 WebDAV。已处理记录保留，避免删书后又被自动加入。 */
@Serializable
data class WatchedFolder(
    val uri: String,
    val name: String,
    val groupByFolder: Boolean,
    val enabled: Boolean = true,
    val seen: Set<String> = emptySet(),
    val status: String = "等待扫描",
)

/** 便于验证升级、重启后仍保留扫描范围和排除记录。 */
internal object FolderLibraryState {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(raw: String): List<WatchedFolder> = json.decodeFromString(raw)
    fun encode(folders: List<WatchedFolder>): String = json.encodeToString(folders)
}

data class FolderImportSummary(val added: Int, val skipped: Int, val failed: Int) {
    val message: String get() = "新增 $added 本，跳过 $skipped 本，失败 $failed 本"
}

class LocalFolderLibrary(
    private val context: Context,
    private val scanner: LocalFolderScanner,
    private val books: BookRepository,
) {
    private val prefs = context.getSharedPreferences("local_folder_library", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _folders = MutableStateFlow(readFolders())
    val folders = _folders.asStateFlow()

    private fun readFolders(): List<WatchedFolder> = runCatching {
        FolderLibraryState.decode(prefs.getString("folders", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun save(folders: List<WatchedFolder>) {
        check(prefs.edit().putString("folders", FolderLibraryState.encode(folders)).commit()) { "无法保存目录配置" }
        _folders.value = folders
    }

    suspend fun setEnabled(uri: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { save(_folders.value.map { if (it.uri == uri) it.copy(enabled = enabled) else it }) }
    }

    suspend fun remove(uri: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            save(_folders.value.filterNot { it.uri == uri })
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    suspend fun importSelection(
        tree: Uri, name: String, selected: List<FolderBook>, all: List<FolderBook>,
        groupByFolder: Boolean, watch: Boolean, truncated: Boolean,
    ): FolderImportSummary = withContext(Dispatchers.IO) {
        require(!watch || !truncated) { "扫描未完成，请选择更小的子目录开启自动扫描" }
        mutex.withLock {
            val old = _folders.value.firstOrNull { it.uri == tree.toString() }
            if (watch) {
                // 授权失败时保留手动导入入口，由界面明确报告，不能假装已开启自动扫描。
                context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val selectedIds = selected.mapTo(HashSet()) { it.uri.toString() }
                val ignored = all.map { it.uri.toString() }.filterNot { it in selectedIds }
                val config = WatchedFolder(tree.toString(), name, groupByFolder,
                    seen = old?.seen.orEmpty() + ignored)
                save(_folders.value.filterNot { it.uri == config.uri } + config)
            } else if (old != null && !truncated) {
                save(_folders.value.map { if (it.uri == old.uri) it.copy(enabled = false) else it })
            }
            val (summary, processed) = importBatch(name, selected, groupByFolder)
            if (watch || old != null) save(_folders.value.map {
                if (it.uri == tree.toString()) it.copy(seen = it.seen + processed, status = summary.message) else it
            })
            summary
        }
    }

    private suspend fun importBatch(name: String, entries: List<FolderBook>, grouped: Boolean): Pair<FolderImportSummary, Set<String>> {
        var added = 0
        var skipped = 0
        var failed = 0
        val processed = HashSet<String>()
        for (entry in entries) {
            try {
                val initialGroup = if (grouped) FolderBooks.groupName(name, entry.folder) else null
                val result = books.importLocalBook(entry.uri, initialGroup).getOrThrow()
                when (result) {
                    is LocalImportResult.Added -> added++
                    is LocalImportResult.AlreadyOnShelf -> skipped++
                }
                processed += entry.uri.toString()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { failed++ }
        }
        return FolderImportSummary(added, skipped, failed) to processed
    }

    /** 每次回到前台扫描；同一时刻只允许一轮，失败文件下次重试。 */
    suspend fun scan(): List<String> = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            val messages = ArrayList<String>()
            for (folder in _folders.value.filter { it.enabled }) {
                try {
                    val scan = scanner.scan(Uri.parse(folder.uri), maxBooks = 10000, maxDepth = 32)
                    val pending = scan.books.filterNot { it.uri.toString() in folder.seen }
                    val (summary, processed) = importBatch(folder.name, pending, folder.groupByFolder)
                    val status = summary.message + if (scan.truncated) "；目录过大，扫描未完成，请选择更小的子目录" else ""
                    save(_folders.value.map { if (it.uri == folder.uri) it.copy(seen = it.seen + processed, status = status) else it })
                    if (summary.added > 0 || summary.failed > 0 || scan.truncated) messages += "${folder.name}：$status"
                } catch (e: CancellationException) { throw e
                } catch (_: SecurityException) {
                    save(_folders.value.map { if (it.uri == folder.uri) it.copy(enabled = false, status = "目录授权失效，请重新添加") else it })
                    messages += "${folder.name}：目录授权失效，请重新添加"
                } catch (e: Exception) {
                    val status = "扫描失败：${e.message?.take(80) ?: "无法读取目录"}"
                    save(_folders.value.map { if (it.uri == folder.uri) it.copy(status = status) else it })
                    messages += "${folder.name}：$status"
                }
            }
            messages
        } finally { mutex.unlock() }
    }
}
