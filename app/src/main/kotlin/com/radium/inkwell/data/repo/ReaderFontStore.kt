package com.radium.inkwell.data.repo

import android.content.Context
import android.net.Uri
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.measure.FileFontRegistry
import com.radium.inkwell.reader.measure.FontRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 一个用户导入的字体。[id] 即 ReaderSettings.fontId（`file:<文件名>`） */
data class UserFont(val id: String, val label: String)

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
    val registry: FontRegistry = FileFontRegistry(dir)

    private val _fonts = MutableStateFlow(scan())
    val fonts: StateFlow<List<UserFont>> = _fonts.asStateFlow()

    /** 这个字体在本机能不能用。预设字体恒为 true */
    fun isAvailable(fontId: String): Boolean =
        !ReaderSettings.isFileFont(fontId) || _fonts.value.any { it.id == fontId }

    /**
     * 从 SAF 导入一个字体文件。先写临时文件、校验能被系统解析，再 rename 成正式文件 ——
     * 不校验的话，选错一个 zip 进来，要等到分页时 Compose 加载字体才炸，那时已经在阅读页里了。
     */
    suspend fun import(uri: Uri): Result<UserFont> = withContext(Dispatchers.IO) {
        var temp: File? = null
        try {
            dir.mkdirs()
            val displayName = queryDisplayName(uri) ?: "font.ttf"
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext !in FONT_EXTENSIONS) error("只支持 TTF / OTF 字体文件")
            val tmp = File.createTempFile("font", ".tmp", dir)
            temp = tmp
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    // 字体再大也就二三十 MB；拦住误选的超大文件，免得把存储写满
                    val copied = input.copyTo(out)
                    if (copied > MAX_FONT_BYTES) error("字体文件太大（超过 ${MAX_FONT_BYTES / 1024 / 1024} MB）")
                }
            } ?: error("无法读取所选文件")
            if (!isParsableFont(tmp)) error("这不是能用的字体文件")

            val dest = uniqueFile(sanitize(displayName.substringBeforeLast('.')), ext)
            if (!tmp.renameTo(dest)) throw IOException("保存字体失败")
            temp = null
            val font = dest.toUserFont()
            _fonts.value = scan()
            Result.success(font)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            temp?.delete()
        }
    }

    suspend fun delete(font: UserFont) = withContext(Dispatchers.IO) {
        File(dir, font.id.removePrefix(ReaderSettings.FONT_FILE_PREFIX)).let { file ->
            // id 来自本目录扫描，但仍只删这个目录里的文件 —— 与 deleteOwnedFile 同一个顾虑
            deleteOwnedFile(file.path, dir)
        }
        _fonts.value = scan()
    }

    private fun scan(): List<UserFont> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in FONT_EXTENSIONS }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { it.toUserFont() }

    private fun File.toUserFont() = UserFont(
        id = ReaderSettings.FONT_FILE_PREFIX + name,
        label = nameWithoutExtension,
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

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    private companion object {
        const val FONTS_DIR = "fonts"
        val FONT_EXTENSIONS = setOf("ttf", "otf")
        const val MAX_FONT_BYTES = 64L * 1024 * 1024

        /** 文件名就是界面上的字体名，也会进 fontId 同步出去：去掉路径分隔与控制字符，别的原样保留 */
        fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "字体" }.take(60)
    }
}
