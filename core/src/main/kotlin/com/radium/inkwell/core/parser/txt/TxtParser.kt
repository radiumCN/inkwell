package com.radium.inkwell.core.parser.txt

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookMetadata
import com.radium.inkwell.core.model.BookParseException
import com.radium.inkwell.core.model.BookParser
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ChapterRef
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.ImageBlob
import com.radium.inkwell.core.model.TocEntry
import com.radium.inkwell.core.util.toHex
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class TxtParser(
    private val splitConfig: TxtChapterSplitter.Config = TxtChapterSplitter.Config(),
    /**
     * 解码后的正文与拆章结果缓存在这里（见 [TxtCache]）；null = 不缓存，每次打开都整本解码、整本拆章。
     * 放在可被系统清理的缓存目录即可：没了只是下次打开慢一次。
     */
    private val cacheDir: File? = null,
) : BookParser {

    override fun sniff(file: File): Boolean {
        if (!file.extension.equals("txt", ignoreCase = true)) return false
        return file.isFile && file.length() > 0
    }

    /**
     * 有缓存就直接用：只读章节表，正文按章从缓存文件里取，整本不进内存、也不再拆一遍。
     * 没有就照旧整本解码、拆章，然后写好缓存、改用文件里的那份 —— 几十 MB 的正文不必在阅读期间一直攥在堆上。
     * 写缓存失败（空间不够之类）就退回内存版，照常能读。
     */
    override fun open(file: File): BookHandle {
        val title = file.nameWithoutExtension
        val cache = cacheDir?.let { TxtCache(it, file, splitConfig) }
        cache?.load()?.let { (textFile, splits) -> return CachedTxtBookHandle(title, textFile, splits) }

        val text = try {
            readText(file)
        } catch (e: Exception) {
            throw BookParseException.Corrupted("读取文件失败: ${file.name}", e)
        }
        val splits = TxtChapterSplitter.split(text, splitConfig)
        if (splits.isEmpty()) throw BookParseException.Corrupted("空文件: ${file.name}")
        cache?.save(text, splits)?.let { textFile -> return CachedTxtBookHandle(title, textFile, splits) }
        return TxtBookHandle(title, text, splits)
    }

    override fun evictCache(file: File) {
        cacheDir?.let { TxtCache(it, file, splitConfig).evict() }
    }

    /**
     * 流式解码整本 txt。
     *
     * 从前 `readBytes()` + `String(bytes)`：解码那一刻原始字节、解码器的字符缓冲、结果字符串三份同时在堆上，
     * 原始字节还一直被 open() 的局部变量攥着撑到拆章结束 —— 几十 MB 的 txt 峰值是文件的三四倍，低端机直接 OOM。
     * 编码只看头部 64KB 就定得下来；正文从 Reader 一路读进按头部字符/字节比例预估好容量的 StringBuilder，
     * 堆上不再有整份原始字节，也省掉 StringBuilder 反复翻倍扩容的拷贝。解码语义不变（非法字节同样替换成 U+FFFD）。
     */
    private fun readText(file: File): String {
        val head = file.inputStream().use { input ->
            val buf = ByteArray(minOf(file.length(), HEAD_BYTES.toLong()).toInt())
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n == buf.size) buf else buf.copyOf(n)
        }
        val charset = EncodingDetector.detect(head)
        val bom = EncodingDetector.bomLength(head)
        val sampleBytes = head.size - bom
        val ratio = if (sampleBytes > 0) String(head, bom, sampleBytes, charset).length.toDouble() / sampleBytes else 1.0
        // 字符数不会多于字节数（任何编码都至少一字节一字符），以文件长度封顶；估不准也只是容量提示
        val capacity = ((file.length() - bom) * minOf(ratio * 1.02, 1.0)).toLong().coerceIn(16, MAX_CAPACITY)
        val sb = StringBuilder(capacity.toInt())
        file.inputStream().buffered().use { input ->
            var skipped = 0L
            while (skipped < bom) {
                val s = input.skip(bom - skipped)
                if (s <= 0) break
                skipped += s
            }
            InputStreamReader(input, charset).use { reader ->
                val buf = CharArray(64 * 1024)
                while (true) {
                    val r = reader.read(buf)
                    if (r < 0) break
                    sb.append(buf, 0, r)
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        /** 编码探测的样本长度 */
        const val HEAD_BYTES = 64 * 1024
        const val MAX_CAPACITY = Int.MAX_VALUE - 16L
    }
}

/** 一章正文 → 段落：按行切、去空行，开头那行若就是标题则去掉（标题行由排版层渲染） */
private fun chapterContent(body: String, title: String): ChapterContent {
    val paragraphs = body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterIndexed { i, line -> !(i == 0 && line == title) }
        .map { ContentElement.Paragraph(it) as ContentElement }
        .toList()
    return ChapterContent(paragraphs)
}

/** 整本正文在内存里的版本：不缓存（[TxtParser] 没给缓存目录）或缓存写不下时用 */
private class TxtBookHandle(
    title: String,
    private val text: String,
    private val splits: List<TxtChapterSplitter.Split>,
) : BookHandle {

    override val metadata = BookMetadata(title = title)

    override val chapters: List<ChapterRef> =
        splits.mapIndexed { i, s -> ChapterRef(i, s.title) }

    override val toc: List<TocEntry> =
        splits.mapIndexed { i, s -> TocEntry(s.title, i, level = s.level) }

    override fun loadChapter(index: Int): ChapterContent {
        val split = splits[index]
        return chapterContent(text.substring(split.start, split.end), split.title)
    }

    override fun loadResource(resourceId: String): ImageBlob? = null

    override fun close() {}
}

/**
 * 正文在缓存文件里的版本：读哪章取哪章。
 *
 * 缓存文件是 UTF-16LE 的原样码元，字符偏移 × 2 就是字节偏移 —— 拆章给的是字符区间，这样换算是精确的，
 * 不像按原编码（GBK 等变长编码、还可能有坏字节）去倒推字节位置那样会错位。
 * 用 FileChannel 的定位读：线程安全，当前章与邻章预排可以同时取。
 */
private class CachedTxtBookHandle(
    title: String,
    textFile: File,
    private val splits: List<TxtChapterSplitter.Split>,
) : BookHandle {

    private val channel: FileChannel = FileChannel.open(textFile.toPath(), StandardOpenOption.READ)

    override val metadata = BookMetadata(title = title)

    override val chapters: List<ChapterRef> =
        splits.mapIndexed { i, s -> ChapterRef(i, s.title) }

    override val toc: List<TocEntry> =
        splits.mapIndexed { i, s -> TocEntry(s.title, i, level = s.level) }

    override fun loadChapter(index: Int): ChapterContent {
        val split = splits[index]
        val buf = ByteBuffer.allocate((split.end - split.start) * 2)
        var pos = split.start * 2L
        while (buf.hasRemaining()) {
            val r = channel.read(buf, pos)
            if (r < 0) break
            pos += r
        }
        buf.flip()
        val body = buf.order(ByteOrder.LITTLE_ENDIAN).asCharBuffer().toString()
        return chapterContent(body, split.title)
    }

    override fun loadResource(resourceId: String): ImageBlob? = null

    override fun close() = channel.close()
}

/**
 * 一本 txt 的「解码后正文 + 拆章结果」缓存。
 *
 * 从前每次打开都要探测编码、整本解码成一个大字符串、整本跑拆章正则，然后这个字符串在阅读期间一直常驻：
 * 50MB 的 GBK 网文是两千多万字，光这一份就是 50MB 的堆，低端机上打开要好几秒。
 *
 * 两个文件：`<键>.u16` 是正文（UTF-16LE 码元原样写出），`<键>.idx` 是头 + 章节表（文本，一章一行）。
 * 键是原文件绝对路径的 MD5；头里记着原文件的长度、修改时间、拆章配置与正文字数 —— 任何一样对不上就当没有缓存。
 * 先写正文、后写章节表，各自「临时文件 + 改名」：中途被杀，留下的要么是旧的、要么对不上头，不会被当成有效缓存。
 *
 * **改了解码或拆章逻辑（会改变拆出来的结果）必须把 [FORMAT] +1**，否则老用户的书还按旧结果读。
 */
internal class TxtCache(
    private val dir: File,
    private val source: File,
    config: TxtChapterSplitter.Config,
) {
    private val key = md5(source.absolutePath)
    private val textFile = File(dir, "$key.u16")
    private val indexFile = File(dir, "$key.idx")

    /** 拆章配置的指纹：自定义规则或参数变了，旧的章节表就作废 */
    private val fingerprint = listOf(
        config.customPattern?.pattern.orEmpty(),
        config.maxTitleLength,
        config.fallbackSliceChars,
        config.minMatches,
    ).joinToString(",")

    private fun header(charCount: Long): String =
        listOf(FORMAT, source.length(), source.lastModified(), escape(fingerprint), charCount).joinToString("\t")

    fun load(): Pair<File, List<TxtChapterSplitter.Split>>? = try {
        loadOrNull()
    } catch (_: Exception) {
        null
    }

    private fun loadOrNull(): Pair<File, List<TxtChapterSplitter.Split>>? {
        if (!indexFile.isFile || !textFile.isFile) return null
        val lines = indexFile.readLines(Charsets.UTF_8)
        val head = lines.firstOrNull()?.split('\t') ?: return null
        val chars = head.getOrNull(4)?.toLongOrNull() ?: return null
        if (lines.first() != header(chars)) return null
        if (textFile.length() != chars * 2) return null
        val splits = lines.drop(1).map { line ->
            val parts = line.split('\t', limit = 4)
            TxtChapterSplitter.Split(
                title = unescape(parts[3]),
                start = parts[1].toInt(),
                end = parts[2].toInt(),
                level = parts[0].toInt(),
            )
        }
        if (splits.isEmpty() || splits.any { it.start < 0 || it.end > chars || it.start > it.end }) return null
        return textFile to splits
    }

    /** 写好返回正文文件；写不成（空间不够之类）清掉半成品、返回 null，调用方退回内存版 */
    fun save(text: String, splits: List<TxtChapterSplitter.Split>): File? = try {
        dir.mkdirs()
        writeAtomically(textFile) { out ->
            // 逐块把码元原样写出：不走编码器，代理对、孤立代理都一字不差，偏移才对得上
            val buf = ByteBuffer.allocate(CHUNK_CHARS * 2).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < text.length) {
                val j = minOf(text.length, i + CHUNK_CHARS)
                buf.clear()
                buf.asCharBuffer().put(text, i, j)
                out.write(buf.array(), 0, (j - i) * 2)
                i = j
            }
        }
        val index = buildString {
            append(header(text.length.toLong()))
            for (s in splits) {
                append('\n').append(s.level).append('\t').append(s.start).append('\t').append(s.end)
                append('\t').append(escape(s.title))
            }
        }
        writeAtomically(indexFile) { out -> out.write(index.toByteArray(Charsets.UTF_8)) }
        textFile
    } catch (_: Exception) {
        evict()
        null
    }

    fun evict() {
        indexFile.delete()
        textFile.delete()
    }

    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File.createTempFile("txt", ".part", dir)
        try {
            tmp.outputStream().buffered().use(write)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "改名失败: ${target.name}" }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private companion object {
        /** 缓存格式 / 解码与拆章逻辑的版本，见类注释 */
        const val FORMAT = "TXT1"
        const val CHUNK_CHARS = 64 * 1024

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).toHex()

        /** 章节表一行一章、字段用 tab 隔开：标题里的反斜杠、tab、换行先转义 */
        fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

        fun unescape(s: String): String = buildString(s.length) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
