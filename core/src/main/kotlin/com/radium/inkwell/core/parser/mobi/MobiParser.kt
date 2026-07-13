package com.radium.inkwell.core.parser.mobi

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookMetadata
import com.radium.inkwell.core.model.BookParseException
import com.radium.inkwell.core.model.BookParser
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ChapterRef
import com.radium.inkwell.core.model.ImageBlob
import com.radium.inkwell.core.model.TocEntry
import com.radium.inkwell.core.parser.html.HtmlToElements
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File

/**
 * 自研 MOBI/AZW3 解析：PDB 容器 + PalmDOC 解压 + MOBI7 filepos 切章 / KF8 skeleton 重组。
 * HUFF/CDIC 压缩与 DRM 明确报错；FONT/RESC/SRCS 等资源记录跳过。
 */
class MobiParser : BookParser {

    override fun sniff(file: File): Boolean {
        if (!file.isFile || file.length() < 78) return false
        val head = ByteArray(68)
        file.inputStream().use { it.read(head) }
        // PDB type "BOOK" + creator "MOBI"
        return head.hasMagic(60, "BOOKMOBI")
    }

    override fun open(file: File): BookHandle {
        val pdb = try {
            PdbFile.open(file)
        } catch (e: BookParseException) {
            throw e
        } catch (e: Exception) {
            throw BookParseException.Corrupted("无法打开 MOBI: ${file.name}", e)
        }
        try {
            return MobiBookHandle.create(pdb)
        } catch (e: BookParseException) {
            pdb.close()
            throw e
        } catch (e: Exception) {
            pdb.close()
            throw BookParseException.Corrupted("MOBI 解析失败: ${file.name}", e)
        }
    }
}

private class MobiBookHandle(
    private val pdb: PdbFile,
    override val metadata: BookMetadata,
    override val chapters: List<ChapterRef>,
    override val toc: List<TocEntry>,
    /** 每个物理章节的 html 串（已解码） */
    private val chapterHtml: List<String>,
    /** MOBI7 recindex=1 对应的 PDB 记录号 */
    private val mobi7ImageBase: Long,
    /** KF8 kindle:embed 资源 1 对应的 PDB 记录号 */
    private val kf8ResourceBase: Long,
) : BookHandle {

    override fun loadChapter(index: Int): ChapterContent {
        val html = chapterHtml.getOrNull(index) ?: return ChapterContent(emptyList())
        val doc = Jsoup.parse(html)
        val converter = HtmlToElements(resolveImage = ::resolveImage)
        return ChapterContent(converter.convert(doc.body()))
    }

    /** img 的 recindex（MOBI7）或 kindle:embed src（KF8）→ "rec:<PDB记录号>" */
    private fun resolveImage(img: Element): String? {
        val recindex = img.attr("recindex").trim()
        if (recindex.isNotEmpty()) {
            val n = recindex.toLongOrNull() ?: return null
            if (n <= 0) return null
            return "rec:${mobi7ImageBase + n - 1}"
        }
        val src = img.attr("src").ifBlank { img.attr("xlink:href") }.ifBlank { img.attr("href") }
        if (src.startsWith("kindle:embed:")) {
            val token = src.removePrefix("kindle:embed:").takeWhile { it.isLetterOrDigit() }
            val n = mobiBase32(token) ?: return null
            if (n <= 0) return null
            return "rec:${kf8ResourceBase + n - 1}"
        }
        return null
    }

    override fun loadResource(resourceId: String): ImageBlob? {
        val n = resourceId.removePrefix("rec:").toIntOrNull() ?: return null
        val bytes = pdb.record(n) ?: return null
        val mime = sniffImageMime(bytes) ?: return null // FONT/RESC 等非图片记录不外发
        return ImageBlob(bytes, mime)
    }

    override fun close() = pdb.close()

    companion object {
        fun create(pdb: PdbFile): MobiBookHandle {
            if (pdb.type != "BOOK" || pdb.creator != "MOBI") {
                throw BookParseException.Corrupted("不是 MOBI/AZW3 文件（PDB type=${pdb.type}/${pdb.creator}）")
            }
            val record0 = pdb.record(0) ?: throw BookParseException.Corrupted("MOBI record0 缺失")
            val h6 = MobiHeader(record0) // encryption != 0 时此处抛 DrmProtected

            // KF8 判定：纯 AZW3（version >= 8）/ combo（EXTH 121 boundary）/ 纯 MOBI7
            var boundary = 0
            var kf8: MobiHeader? = null
            if (h6.fileVersion >= 8 && h6.skeletonIndex != NULL_INDEX) {
                kf8 = h6
            } else {
                val b = h6.kf8BoundaryIndex?.toInt()
                if (b != null && b in 1 until pdb.numRecords) {
                    runCatching {
                        val r = pdb.record(b)
                        if (r != null && r.hasMagic(16, "MOBI")) {
                            val h8 = MobiHeader(r)
                            if (h8.fileVersion >= 8) {
                                kf8 = h8
                                boundary = b
                            }
                        }
                    }
                }
            }

            // 元数据统一取第一个 record0 的 EXTH（combo 文件两份 EXTH 内容一致）
            val title = h6.updatedTitle ?: h6.fullName ?: pdb.name.ifBlank { "未命名" }
            val language = h6.exthString(524)

            return if (kf8 != null) {
                createKf8(pdb, h6, kf8!!, boundary, title, language)
            } else {
                createMobi7(pdb, h6, title, language)
            }
        }

        // ---- KF8 路径 ----

        private fun createKf8(
            pdb: PdbFile,
            h6: MobiHeader,
            h8: MobiHeader,
            boundary: Int,
            title: String,
            language: String?,
        ): MobiBookHandle {
            val record: (Int) -> ByteArray? = { i -> pdb.record(boundary + i) }
            val rawMl = MobiText.extract(h8, record)
            val result = Kf8Reader(h8, record).read(rawMl, title)

            // KF8 资源区起点：firstImageIndex（相对 KF8 record0）；无则紧跟正文记录
            val kf8ResourceBase =
                if (h8.firstImageIndex != NULL_INDEX) h8.firstImageIndex + boundary
                else (h8.textRecordCount + boundary + 1).toLong()
            // combo 文件的 MOBI7 部分图片区（recindex 用）
            val mobi7ImageBase =
                if (boundary > 0 && h6.firstImageIndex != NULL_INDEX) h6.firstImageIndex else kf8ResourceBase

            val chapters = result.chapterTitles.mapIndexed { i, t -> ChapterRef(i, t) }
            val toc = result.toc.ifEmpty { chapters.map { TocEntry(it.title, it.index) } }
            val cover = findCover(pdb, h6, if (boundary > 0) h6.firstImageIndex else kf8ResourceBase)
            val metadata = BookMetadata(title, h6.author, h6.description, language, h6.isbn, cover)
            return MobiBookHandle(pdb, metadata, chapters, toc, result.chapterHtml, mobi7ImageBase, kf8ResourceBase)
        }

        // ---- MOBI7 路径 ----

        private fun createMobi7(
            pdb: PdbFile,
            h6: MobiHeader,
            title: String,
            language: String?,
        ): MobiBookHandle {
            val text = MobiText.extract(h6) { i -> pdb.record(i) }
            // 用 Latin-1 视图做位置计算：字符下标 == 字节偏移（filepos 是解码前字节偏移）
            val latin = String(text, Charsets.ISO_8859_1)

            // 目录来源优先级：NCX INDX → guide 内联 TOC 的 filepos 锚点 → <mbp:pagebreak/> → 整书一章
            var tocPairs = readMobi7Ncx(pdb, h6, text.size)
            if (tocPairs.isEmpty()) tocPairs = readInlineToc(latin)
            val boundaries = sortedSetOf(0)
            tocPairs.forEach { boundaries += it.pos }
            if (tocPairs.isEmpty()) {
                PAGEBREAK.findAll(latin).forEach { boundaries += it.range.first }
            }
            var starts = boundaries.filter { it in 0 until text.size }.sorted()
            // 前导切片只有 head/guide 没有可见内容时丢弃（TOC 第一项才是正文开头）
            if (starts.size > 1 && starts[0] == 0) {
                val lead = latin.substring(0, starts[1])
                val visible = lead.replace(TAG, "").isNotBlank() ||
                    lead.contains("<img", ignoreCase = true)
                if (!visible) starts = starts.drop(1)
            }

            // 切片在字节层面做，再逐章解码
            val chapterHtml = starts.mapIndexed { i, s ->
                val e = if (i + 1 < starts.size) starts[i + 1] else text.size
                String(text, s, e - s, h6.charset).replace("\u0000", "")
            }

            val startIndex = starts.withIndex().associate { (i, s) -> s to i }
            val toc = tocPairs.mapNotNull { t ->
                startIndex[t.pos]?.let { TocEntry(t.label, it, null, t.depth) }
            }

            // 章节标题：TOC 覆盖的用 TOC 标题，否则取 h1-h3，再沿用上一章
            val titles = arrayOfNulls<String>(starts.size)
            toc.forEach { e -> if (titles[e.chapterIndex] == null) titles[e.chapterIndex] = e.title }
            var last = title
            val chapters = chapterHtml.mapIndexed { i, html ->
                val t = titles[i] ?: firstHeading(html) ?: last
                last = t
                ChapterRef(i, t)
            }

            val imageBase =
                if (h6.firstImageIndex != NULL_INDEX) h6.firstImageIndex
                else (h6.textRecordCount + 1).toLong()
            val cover = findCover(pdb, h6, imageBase)
            val metadata = BookMetadata(title, h6.author, h6.description, language, h6.isbn, cover)
            return MobiBookHandle(
                pdb, metadata, chapters,
                toc.ifEmpty { chapters.map { TocEntry(it.title, it.index) } },
                chapterHtml, imageBase, imageBase,
            )
        }

        private class TocPos(val pos: Int, val label: String, val depth: Int)

        /** MOBI7 NCX：tag1 = pos（正文字节偏移），tag3 = label(cncx)，tag4 = depth */
        private fun readMobi7Ncx(pdb: PdbFile, h6: MobiHeader, textLen: Int): List<TocPos> {
            if (h6.ncxIndex == NULL_INDEX) return emptyList()
            val ncx = IndxParser.read(h6.ncxIndex.toInt(), { i -> pdb.record(i) }, h6.charset)
                ?: return emptyList()
            return ncx.entries.mapNotNull { e ->
                val pos = e.tags[1]?.firstOrNull()?.toInt() ?: return@mapNotNull null
                val label = ncx.cncxString(e.tags[3]?.firstOrNull())?.trim()?.ifBlank { null }
                    ?: return@mapNotNull null
                if (pos !in 0 until textLen) return@mapNotNull null
                TocPos(pos, label, (e.tags[4]?.firstOrNull() ?: 0L).toInt().coerceAtLeast(0))
            }
        }

        /** guide 的 <reference type="toc" filepos=N> → TOC 页内 <a filepos=N>label</a> 锚点 */
        private fun readInlineToc(latin: String): List<TocPos> {
            val ref = REFERENCE.findAll(latin).firstOrNull { TOC_TYPE.containsMatchIn(it.value) }
                ?: return emptyList()
            val tocPos = FILEPOS.find(ref.value)?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
            if (tocPos !in latin.indices) return emptyList()
            // TOC 页范围：到下一个 pagebreak 为止（找不到则给一个安全窗口）
            val end = PAGEBREAK.find(latin, startIndex = tocPos + 1)?.range?.first
                ?: minOf(latin.length, tocPos + 200_000)
            return ANCHOR.findAll(latin.substring(tocPos, end)).mapNotNull { m ->
                val pos = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val label = m.groupValues[2].replace(TAG, "")
                    .let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
                    .replace(Regex("\\s+"), " ").trim()
                if (label.isBlank() || pos !in latin.indices) null else TocPos(pos, label, 0)
            }.toList()
        }

        private fun firstHeading(html: String): String? =
            HEADING.find(html)?.groupValues?.get(1)
                ?.replace(TAG, "")
                ?.let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
                ?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }

        /** 封面：EXTH 201 coverOffset + 图片区起点；无则取首个图片记录 */
        private fun findCover(pdb: PdbFile, exthHeader: MobiHeader, imageBase: Long): ImageBlob? {
            exthHeader.coverOffset?.let { off ->
                val idx = imageBase + off
                if (idx in 0 until pdb.numRecords) {
                    pdb.record(idx.toInt())?.let { bytes ->
                        sniffImageMime(bytes)?.let { return ImageBlob(bytes, it) }
                    }
                }
            }
            // 退化：从图片区起点向后找第一条图片记录
            if (imageBase in 0 until pdb.numRecords) {
                for (i in imageBase.toInt() until minOf(pdb.numRecords, imageBase.toInt() + 32)) {
                    val bytes = pdb.record(i) ?: continue
                    sniffImageMime(bytes)?.let { return ImageBlob(bytes, it) }
                }
            }
            return null
        }

        private fun sniffImageMime(bytes: ByteArray): String? = when {
            bytes.size > 3 && bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8 -> "image/jpeg"
            bytes.size > 8 && bytes.u8(0) == 0x89 && bytes.hasMagic(1, "PNG") -> "image/png"
            bytes.hasMagic(0, "GIF8") -> "image/gif"
            bytes.size > 12 && bytes.hasMagic(0, "RIFF") && bytes.hasMagic(8, "WEBP") -> "image/webp"
            bytes.size > 2 && bytes.u8(0) == 0x42 && bytes.u8(1) == 0x4D -> "image/bmp"
            else -> null
        }

        private val PAGEBREAK = Regex("<mbp:pagebreak", RegexOption.IGNORE_CASE)
        private val REFERENCE = Regex("<reference[^>]*>", RegexOption.IGNORE_CASE)
        private val TOC_TYPE = Regex("type=[\"']?toc[\"'\\s/>]", RegexOption.IGNORE_CASE)
        private val FILEPOS = Regex("filepos=[\"']?(\\d+)", RegexOption.IGNORE_CASE)
        private val ANCHOR = Regex(
            "<a[^>]+filepos=[\"']?(\\d+)[\"']?[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val HEADING = Regex(
            "<h[1-3][^>]*>(.*?)</h[1-3]>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val TAG = Regex("<[^>]*>")
    }
}
