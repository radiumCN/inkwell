package com.radium.inkwell.core.parser.epub

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookMetadata
import com.radium.inkwell.core.model.BookParseException
import com.radium.inkwell.core.model.BookParser
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ChapterRef
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.ImageBlob
import com.radium.inkwell.core.model.TocEntry
import com.radium.inkwell.core.parser.html.HtmlToElements
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * 自研 EPUB 解析：zip + container.xml + OPF + nav.xhtml/NCX。
 * CSS 全部丢弃，章节 = spine item，逻辑目录单独解析（支持锚点与嵌套）。
 */
class EpubParser : BookParser {

    override fun sniff(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        // zip magic "PK\x03\x04"
        if (!(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte())) return false
        return file.extension.equals("epub", ignoreCase = true) ||
            runCatching {
                ZipFile(file).use { it.getEntry("META-INF/container.xml") != null }
            }.getOrDefault(false)
    }

    override fun open(file: File): BookHandle {
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw BookParseException.Corrupted("无法打开 EPUB: ${file.name}", e)
        }
        try {
            return EpubBookHandle.create(zip)
        } catch (e: BookParseException) {
            zip.close()
            throw e
        } catch (e: Exception) {
            zip.close()
            throw BookParseException.Corrupted("EPUB 解析失败: ${file.name}", e)
        }
    }
}

private class EpubBookHandle(
    private val zip: ZipFile,
    override val metadata: BookMetadata,
    override val chapters: List<ChapterRef>,
    override val toc: List<TocEntry>,
    /** spine index → zip 内规范化路径 */
    private val spinePaths: List<String>,
    private val entryIndex: Map<String, String>,
) : BookHandle {

    override fun loadChapter(index: Int): ChapterContent {
        val path = spinePaths[index]
        val bytes = readEntry(path) ?: return ChapterContent(emptyList())
        // 内容文件用 html 模式解析（容错非良构 XHTML）
        val doc = Jsoup.parse(bytes.inputStream(), null, "")
        val dir = path.substringBeforeLast('/', "")
        val converter = HtmlToElements(resolveImage = { img ->
            val src = img.attr("src").ifBlank { img.attr("xlink:href") }.ifBlank { img.attr("href") }
            if (src.isBlank()) null else resolvePath(dir, src)
        })
        val elements = converter.convert(doc.body())
        return ChapterContent(elements)
    }

    override fun loadResource(resourceId: String): ImageBlob? {
        val bytes = readEntry(resourceId) ?: return null
        return ImageBlob(bytes, guessMime(resourceId))
    }

    override fun close() = zip.close()

    private fun readEntry(path: String): ByteArray? {
        val real = entryIndex[path.lowercase()] ?: return null
        val entry = zip.getEntry(real) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    companion object {
        fun create(zip: ZipFile): EpubBookHandle {
            // 大小写不敏感的 entry 索引（真实 EPUB 常见 href 大小写不匹配）
            val entryIndex = buildMap {
                zip.entries().asSequence().forEach { put(it.name.lowercase(), it.name) }
            }

            fun read(path: String): ByteArray? {
                val real = entryIndex[path.lowercase()] ?: return null
                return zip.getInputStream(zip.getEntry(real)).use { it.readBytes() }
            }

            fun readXml(path: String): Document? = read(path)?.let {
                Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser())
            }

            // DRM 检测
            read("META-INF/encryption.xml")?.let { enc ->
                val doc = Jsoup.parse(String(enc, Charsets.UTF_8), "", Parser.xmlParser())
                if (doc.select("EncryptedData, enc|EncryptedData").isNotEmpty()) {
                    throw BookParseException.DrmProtected("该 EPUB 受 DRM 保护，无法打开")
                }
            }

            // container.xml → OPF 路径
            val container = readXml("META-INF/container.xml")
                ?: throw BookParseException.Corrupted("缺少 META-INF/container.xml")
            val opfPath = container.select("rootfile").firstOrNull()?.attr("full-path")
                ?.takeIf { it.isNotBlank() }
                ?: throw BookParseException.Corrupted("container.xml 中无 rootfile")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = readXml(opfPath) ?: throw BookParseException.Corrupted("缺少 OPF: $opfPath")

            // metadata
            val title = opf.select("metadata > dc|title, dc|title").firstOrNull()?.text()?.trim()
                ?.ifBlank { null } ?: "未命名"
            val author = opf.select("metadata > dc|creator, dc|creator").firstOrNull()?.text()?.trim()
            val language = opf.select("dc|language").firstOrNull()?.text()?.trim()
            val description = opf.select("dc|description").firstOrNull()?.text()?.trim()
            val identifier = opf.select("dc|identifier").firstOrNull()?.text()?.trim()

            // manifest: id → (href, media-type, properties)
            data class Item(val href: String, val mediaType: String, val properties: String)
            val manifest = opf.select("manifest > item").associate { el ->
                el.attr("id") to Item(el.attr("href"), el.attr("media-type"), el.attr("properties"))
            }

            fun itemPath(item: Item): String = resolvePath(opfDir, item.href)

            // spine
            val spineIds = opf.select("spine > itemref").map { it.attr("idref") }
            val spinePaths = spineIds.mapNotNull { id -> manifest[id]?.let { itemPath(it) } }
            if (spinePaths.isEmpty()) throw BookParseException.Corrupted("EPUB spine 为空")

            // 封面：EPUB3 properties → EPUB2 meta → 猜测
            val coverItem = manifest.values.firstOrNull { it.properties.contains("cover-image") }
                ?: opf.select("metadata > meta[name=cover]").firstOrNull()?.attr("content")
                    ?.let { manifest[it] }
                ?: manifest.entries.firstOrNull { (id, item) ->
                    item.mediaType.startsWith("image/") &&
                        (id.contains("cover", true) || item.href.contains("cover", true))
                }?.value
            val cover = coverItem?.let { item ->
                read(itemPath(item))?.let { ImageBlob(it, item.mediaType.ifBlank { null }) }
            }

            // 目录：EPUB3 nav 优先，回退 NCX
            val spineIndexByPath = spinePaths.withIndex().associate { (i, p) -> p.lowercase() to i }
            val navItem = manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
            val toc: List<TocEntry> = when {
                navItem != null -> parseNav(read(itemPath(navItem)), itemPath(navItem), spineIndexByPath)
                else -> {
                    val ncxId = opf.select("spine").attr("toc").ifBlank { null }
                    val ncxItem = ncxId?.let { manifest[it] }
                        ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                    ncxItem?.let { parseNcx(readXml(itemPath(it)), itemPath(it), spineIndexByPath) }
                        ?: emptyList()
                }
            }

            // 章节标题：TOC 覆盖到的用 TOC 标题，未覆盖的沿用最近一条
            val titles = arrayOfNulls<String>(spinePaths.size)
            toc.forEach { e -> if (titles[e.chapterIndex] == null && e.anchor == null) titles[e.chapterIndex] = e.title }
            var lastTitle = title
            val chapters = spinePaths.mapIndexed { i, _ ->
                val t = titles[i] ?: lastTitle
                lastTitle = t
                ChapterRef(i, t)
            }

            return EpubBookHandle(
                zip = zip,
                metadata = BookMetadata(title, author, description, language, identifier, cover),
                chapters = chapters,
                toc = toc.ifEmpty { chapters.map { TocEntry(it.title, it.index) } },
                spinePaths = spinePaths,
                entryIndex = entryIndex,
            )
        }

        private fun parseNav(
            bytes: ByteArray?,
            navPath: String,
            spineIndexByPath: Map<String, Int>,
        ): List<TocEntry> {
            bytes ?: return emptyList()
            val doc = Jsoup.parse(String(bytes, Charsets.UTF_8))
            val nav = doc.select("nav[epub:type=toc], nav#toc, nav").firstOrNull() ?: return emptyList()
            val navDir = navPath.substringBeforeLast('/', "")
            val out = mutableListOf<TocEntry>()

            fun walk(ol: Element, level: Int) {
                ol.children().filter { it.tagName() == "li" }.forEach { li ->
                    val a = li.selectFirst("> a")
                    if (a != null) {
                        val href = a.attr("href")
                        val path = resolvePath(navDir, href.substringBefore('#'))
                        val anchor = href.substringAfter('#', "").ifBlank { null }
                        spineIndexByPath[path.lowercase()]?.let { idx ->
                            out += TocEntry(a.text().trim(), idx, anchor, level)
                        }
                    }
                    li.selectFirst("> ol")?.let { walk(it, level + 1) }
                }
            }
            nav.selectFirst("ol")?.let { walk(it, 0) }
            return out
        }

        private fun parseNcx(
            doc: Document?,
            ncxPath: String,
            spineIndexByPath: Map<String, Int>,
        ): List<TocEntry> {
            doc ?: return emptyList()
            val ncxDir = ncxPath.substringBeforeLast('/', "")
            val out = mutableListOf<TocEntry>()

            fun walk(navPoint: Element, level: Int) {
                val label = navPoint.select("> navLabel > text").firstOrNull()?.text()?.trim() ?: ""
                val src = navPoint.select("> content").firstOrNull()?.attr("src") ?: ""
                if (label.isNotEmpty() && src.isNotEmpty()) {
                    val path = resolvePath(ncxDir, src.substringBefore('#'))
                    val anchor = src.substringAfter('#', "").ifBlank { null }
                    spineIndexByPath[path.lowercase()]?.let { idx ->
                        out += TocEntry(label, idx, anchor, level)
                    }
                }
                navPoint.children().filter { it.tagName() == "navPoint" }.forEach { walk(it, level + 1) }
            }
            doc.select("navMap > navPoint").forEach { walk(it, 0) }
            return out
        }
    }
}

/** 以 baseDir 为基准解析相对路径，处理 ../ 与 URL 编码 */
internal fun resolvePath(baseDir: String, href: String): String {
    val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
    val parts = ArrayDeque<String>()
    if (baseDir.isNotEmpty() && !decoded.startsWith("/")) {
        baseDir.split('/').filter { it.isNotEmpty() }.forEach { parts.addLast(it) }
    }
    decoded.trimStart('/').split('/').forEach { seg ->
        when (seg) {
            "", "." -> {}
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(seg)
        }
    }
    return parts.joinToString("/")
}

internal fun guessMime(path: String): String? = when (path.substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    else -> null
}
