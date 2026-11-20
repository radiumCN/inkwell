package com.radium.inkwell.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.measure.FileFontRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** 一个用户导入的字体。[id] 即 ReaderSettings.fontId（`file:<文件名>`） */
data class UserFont(val id: String, val label: String, val sizeBytes: Long)

enum class FontImportStage { READING, VALIDATING }

/**
 * 用户导入的阅读字体：放在 `filesDir/fonts/`，一份文件一个字体。
 *
 * **字体文件不进 WebDAV**，与「书籍文件不上云」同一条线：几 MB 到二十几 MB 一个，塞进单文件备份会把
 * 每次同步拖慢一个量级。同步的只是 fontId —— 另一台设备没有这个文件时排版回落默认字体（见 [FileFontRegistry]），
 * 设置导入时也不会拿远端那个用不了的 id 覆盖本地（见 ReaderPrefs.importFromBackup）。
 */
class ReaderFontStore(context: Context) {

    private val resolver = context.contentResolver
    val dir: File = File(context.filesDir, FONTS_DIR)

    /** 排版用的字体解析：预设 + 本目录里的文件。整个应用共用一个，FontFamily 缓存才有意义 */
    val registry = FileFontRegistry(dir)

    private val scanMutex = Mutex()
    private val importMutex = Mutex()
    private val _fonts = MutableStateFlow<List<UserFont>>(emptyList())
    val fonts: StateFlow<List<UserFont>> = _fonts.asStateFlow()

    init {
        // 构造阅读 VM 时不扫描磁盘。扫描与导入/删除后的发布串行，初次扫描不会盖回旧列表。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { refresh() }
    }

    /** 备份导入在 IO 上查询实际文件，不把异步列表的初始空值当成“字体不存在”。 */
    fun isAvailable(fontId: String): Boolean = fontFileAvailable(dir, fontId)

    private suspend fun refresh() = scanMutex.withLock { _fonts.value = scan() }

    /**
     * 从 SAF 导入一个字体文件。先写临时文件、校验能被系统解析，再 rename 成正式文件 ——
     * 不校验的话，选错一个 zip 进来，要等到分页时 Compose 加载字体才炸，那时已经在阅读页里了。
     */
    suspend fun import(uri: Uri, onStage: (FontImportStage) -> Unit = {}): Result<UserFont> =
        importMutex.withLock { withContext(Dispatchers.IO) { readFont(uri, onStage) } }

    private suspend fun readFont(uri: Uri, onStage: (FontImportStage) -> Unit): Result<UserFont> {
        var temp: File? = null
        return try {
            dir.mkdirs()
            val (displayName, size) = queryFileInfo(uri)
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext !in FONT_EXTENSIONS) error("只支持 TTF / OTF 字体文件")
            if (size != null && size > MAX_FONT_BYTES) throw fontTooLarge(MAX_FONT_BYTES)
            onStage(FontImportStage.READING)
            val tmp = File.createTempFile("font", ".tmp", dir)
            temp = tmp
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    // 字体再大也就二三十 MB；拦住误选的超大文件，免得把存储写满
                    copyFontBytes(input, out, MAX_FONT_BYTES)
                }
            } ?: error("无法读取所选文件")
            onStage(FontImportStage.VALIDATING)
            if (!isParsableFont(tmp)) error("这不是能用的字体文件")
            currentCoroutineContext().ensureActive()

            val dest = uniqueFile(sanitize(displayName.substringBeforeLast('.')), ext)
            if (!tmp.renameTo(dest)) throw IOException("保存字体失败")
            temp = null
            val font = dest.toUserFont()
            refresh()
            Result.success(font)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            temp?.delete()
        }
    }

    suspend fun delete(font: UserFont) = importMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            registry.evictAfterDelete(font.id) { deleteFontFile(dir, font.id) }
            refresh()
        }
    }

    private fun scan(): List<UserFont> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in FONT_EXTENSIONS }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { it.toUserFont() }

    private fun File.toUserFont() = UserFont(
        id = ReaderSettings.FONT_FILE_PREFIX + name,
        label = nameWithoutExtension,
        sizeBytes = length(),
    )

    /** 同名已存在就加序号：同名不同字体（不同字重、不同版本）不该悄悄互相覆盖 */
    private fun uniqueFile(base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n).$ext").also { n++ }
        return f
    }

    private fun isParsableFont(file: File): Boolean = try {
        android.graphics.fonts.Font.Builder(file).build()
        true
    } catch (_: Exception) {
        false
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long?> =
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE)
            val displayName = if (name >= 0) cursor.getString(name) else null
            val byteCount = if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else null
            (displayName ?: "font.ttf") to byteCount
        } ?: ("font.ttf" to null)

    private companion object {
        const val FONTS_DIR = "fonts"
        const val MAX_FONT_BYTES = 64L * 1024 * 1024

        /** 文件名就是界面上的字体名，也会进 fontId 同步出去：去掉路径分隔与控制字符，别的原样保留 */
        fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "字体" }.take(60)
    }
}

/** 最多写入 limit 字节，再读一个字节判断是否越界；不等复制完一个大文件才发现超限。 */
internal suspend fun copyFontBytes(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
    require(limit >= 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val remaining = limit - copied
        val read = input.read(buffer, 0, if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt())
        if (read < 0) return
        if (read.toLong() > remaining) throw fontTooLarge(limit)
        output.write(buffer, 0, read)
        copied += read
    }
}

private fun fontTooLarge(limit: Long) =
    IOException("字体文件超过 ${limit / 1024 / 1024} MiB 导入上限，请选择较小的 TTF 或 OTF 文件。")

internal fun fontFileAvailable(dir: File, fontId: String): Boolean {
    if (!ReaderSettings.isFileFont(fontId)) return true
    return localFontFile(dir, fontId)?.isFile == true
}

internal fun deleteFontFile(dir: File, fontId: String) {
    val file = localFontFile(dir, fontId) ?: throw IOException("字体文件路径无效")
    if (!deleteOwnedFile(file.path, dir) && !Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("无法删除字体文件")
    }
}

private val FONT_EXTENSIONS = setOf("ttf", "otf")

private fun localFontFile(dir: File, fontId: String): File? {
    if (!ReaderSettings.isFileFont(fontId)) return null
    val name = fontId.removePrefix(ReaderSettings.FONT_FILE_PREFIX)
    val file = File(dir, name)
    if (file.name != name || file.extension.lowercase() !in FONT_EXTENSIONS) return null
    return file.takeIf {
        runCatching { it.canonicalFile == File(dir.canonicalFile, name) }.getOrDefault(false)
    }
}
