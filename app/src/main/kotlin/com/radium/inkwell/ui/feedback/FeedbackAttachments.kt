package com.radium.inkwell.ui.feedback

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

data class FeedbackAttachment(val uri: Uri, val name: String, val sizeBytes: Long?)

internal const val MAX_FEEDBACK_FILES = 4
internal const val MAX_FEEDBACK_FILE_MIB = 25
internal const val MAX_FEEDBACK_FILE_BYTES = MAX_FEEDBACK_FILE_MIB * 1024L * 1024L

internal class FeedbackFilesTooLarge : IOException()

internal fun Context.readFeedbackAttachment(uri: Uri): FeedbackAttachment {
    val fallback = FeedbackAttachment(uri, "未命名附件", null)
    return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use fallback
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            FeedbackAttachment(uri, name?.takeIf(String::isNotBlank) ?: fallback.name, size?.takeIf { it >= 0 })
        } ?: fallback
}

internal suspend fun Context.packFeedbackAttachments(files: List<FeedbackAttachment>): File {
    val dir = File(cacheDir, "feedback").apply { mkdirs() }
    val archive = File.createTempFile("attachments-", ".zip", dir)
    try {
        var remaining = MAX_FEEDBACK_FILE_BYTES
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            files.forEachIndexed { index, file ->
                val base = file.name.substringBeforeLast('.').take(40).trimEnd { it.isHighSurrogate() }
                val extension = file.name.substringAfterLast('.', "").take(12).trimEnd { it.isHighSurrogate() }
                val name = if (extension.isEmpty()) base else "$base.$extension"
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                // Stable filename and timestamp keep retries byte-identical for unchanged files.
                zip.putNextEntry(ZipEntry("${index + 1}-$safeName").apply { time = 0 })
                val input = contentResolver.openInputStream(file.uri) ?: throw IOException("无法读取附件")
                input.use {
                    while (true) {
                        coroutineContext.ensureActive()
                        val length = it.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                        if (length == -1) break
                        if (length > remaining) throw FeedbackFilesTooLarge()
                        zip.write(buffer, 0, length)
                        remaining -= length
                    }
                }
                zip.closeEntry()
            }
        }
        return archive
    } catch (e: Throwable) {
        archive.delete()
        throw e
    }
}
