package com.radium.inkwell.core.parser.mobi

import com.radium.inkwell.core.model.TocEntry

/**
 * KF8（AZW3）结构重组：FDST 切 flows（只用 flow0），
 * skeleton + fragment 表拼回按序的 xhtml 文件，每个 skeleton-file 作为一个物理章节。
 * 流程与 calibre calibre/ebooks/mobi/reader/mobi8.py 对齐。
 * flow>0（css/svg）、aid 属性、kindle:pos 内链均忽略。
 */
internal class Kf8Reader(
    private val header: MobiHeader,
    /** 取记录的回调，下标相对 KF8 record0（joint 文件由调用方加 boundary 偏移） */
    private val record: (Int) -> ByteArray?,
) {

    class Result(
        val chapterHtml: List<String>,
        val chapterTitles: List<String>,
        val toc: List<TocEntry>,
    )

    private class Fragment(val insertPos: Long, val start: Long, val length: Long)

    private class Part(val bytes: ByteArray, val start: Long, val end: Long)

    fun read(rawMl: ByteArray, fallbackTitle: String): Result {
        val flows = readFlows(rawMl)
        val flow0 = flows[0]
        val fragments = readFragments()
        val parts = buildParts(flow0, fragments)
        val toc = readToc(parts, fragments)

        // 章节标题：TOC 覆盖的用 TOC 标题，否则取正文 h1-h3，再退化沿用上一章
        val titles = arrayOfNulls<String>(parts.size)
        toc.forEach { e -> if (titles[e.chapterIndex] == null) titles[e.chapterIndex] = e.title }
        val html = parts.map { String(it.bytes, header.charset).replace("\u0000", "") }
            .map { resolveSvgFlowImages(it, flows) }
        var last = fallbackTitle
        val chapterTitles = html.mapIndexed { i, h ->
            val t = titles[i] ?: firstHeading(h) ?: last
            last = t
            t
        }
        return Result(html, chapterTitles, toc)
    }

    /** FDST 记录切 flows：flow0 是正文 xhtml，其余是 css/svg；缺失/异常时整段正文视为 flow0 */
    private fun readFlows(rawMl: ByteArray): List<ByteArray> {
        val whole = listOf(rawMl)
        if (header.fdstIndex == NULL_INDEX) return whole
        val rec = record(header.fdstIndex.toInt()) ?: return whole
        if (!rec.hasMagic(0, "FDST")) return whole
        val secStart = rec.u32(4).toInt()
        val count = rec.u32(8).toInt()
        if (count <= 0) return whole
        val flows = mutableListOf<ByteArray>()
        for (i in 0 until count) {
            val start = rec.u32(secStart + i * 8).toInt()
            val end = rec.u32(secStart + i * 8 + 4).toInt()
            if (start < 0 || end <= start || end > rawMl.size) break
            flows += rawMl.copyOfRange(start, end)
        }
        return flows.ifEmpty { whole }
    }

    /**
     * ebookmaker/kindlegen 常把插图包成 svg flow：<img src="kindle:flow:N?mime=image/svg+xml"/>，
     * flow 内才是 <image xlink:href="kindle:embed:M"/>。这里直接换成指向 embed 资源的 img，
     * 其余 flow（css 等）不处理。
     */
    private fun resolveSvgFlowImages(html: String, flows: List<ByteArray>): String {
        if (!html.contains("kindle:flow:")) return html
        return SVG_FLOW_IMG.replace(html) { m ->
            val flowIdx = mobiBase32(m.groupValues[1])?.toInt()
            val flow = flowIdx?.let { flows.getOrNull(it) }
            val embed = flow?.let { EMBED_URL.find(String(it, header.charset))?.value }
            if (embed != null) "<img src=\"$embed\"/>" else ""
        }
    }

    /** fragment（DIVTBL）表：ident 即 insertPos，tag6 = (sequence?, startPos, length) 实为 (startPos, length) */
    private fun readFragments(): List<Fragment> {
        if (header.fragmentIndex == NULL_INDEX) return emptyList()
        val index = IndxParser.read(header.fragmentIndex.toInt(), record, header.charset) ?: return emptyList()
        return index.entries.mapNotNull { e ->
            val insertPos = e.ident.trim().toLongOrNull() ?: return@mapNotNull null
            val t6 = e.tags[6] ?: return@mapNotNull null
            Fragment(insertPos, t6.getOrNull(0) ?: return@mapNotNull null, t6.getOrNull(1) ?: 0L)
        }
    }

    /** skeleton 表：tag1 = fragmentCount，tag6 = (startPos, length)；逐个把 fragment 插回骨架 */
    private fun buildParts(flow0: ByteArray, fragments: List<Fragment>): List<Part> {
        val skel = if (header.skeletonIndex == NULL_INDEX) null
        else IndxParser.read(header.skeletonIndex.toInt(), record, header.charset)
        if (skel == null || skel.entries.isEmpty()) {
            return listOf(Part(flow0, 0, flow0.size.toLong()))
        }
        val parts = mutableListOf<Part>()
        var divptr = 0
        for (f in skel.entries) {
            val divCount = (f.tags[1]?.firstOrNull() ?: 0L).toInt()
            val t6 = f.tags[6]
            val skelStart = (t6?.getOrNull(0) ?: 0L).toInt().coerceIn(0, flow0.size)
            val skelLen = (t6?.getOrNull(1) ?: 0L).toInt().coerceIn(0, flow0.size - skelStart)
            var baseptr = skelStart + skelLen
            var skeleton = flow0.copyOfRange(skelStart, baseptr)
            for (i in 0 until divCount) {
                val e = fragments.getOrNull(divptr) ?: break
                val len = e.length.toInt().coerceIn(0, flow0.size - baseptr)
                val part = flow0.copyOfRange(baseptr, baseptr + len)
                val insert = (e.insertPos - skelStart).toInt().coerceIn(0, skeleton.size)
                skeleton = skeleton.copyOfRange(0, insert) + part + skeleton.copyOfRange(insert, skeleton.size)
                baseptr += len
                divptr++
            }
            parts += Part(skeleton, skelStart.toLong(), baseptr.toLong())
        }
        return parts
    }

    /** KF8 NCX：tag3 = label(cncx)，tag4 = depth，tag6 = pos:fid，tag1 = pos（旧式） */
    private fun readToc(parts: List<Part>, fragments: List<Fragment>): List<TocEntry> {
        if (header.ncxIndex == NULL_INDEX) return emptyList()
        val ncx = IndxParser.read(header.ncxIndex.toInt(), record, header.charset) ?: return emptyList()
        val toc = mutableListOf<TocEntry>()
        for (e in ncx.entries) {
            val label = ncx.cncxString(e.tags[3]?.firstOrNull())?.trim()?.ifBlank { null } ?: continue
            val depth = (e.tags[4]?.firstOrNull() ?: 0L).toInt().coerceAtLeast(0)
            val posFid = e.tags[6]
            // pos:fid 是 (fragment 下标, 段内偏移)；退化用 tag1 的正文绝对位置
            val pos = if (!posFid.isNullOrEmpty()) {
                fragments.getOrNull(posFid[0].toInt())?.let { it.insertPos + (posFid.getOrNull(1) ?: 0L) }
            } else {
                e.tags[1]?.firstOrNull()
            } ?: continue
            val idx = parts.indexOfFirst { pos >= it.start && pos < it.end }
            if (idx >= 0) toc += TocEntry(label, idx, null, depth)
        }
        return toc
    }

    private fun firstHeading(html: String): String? =
        HEADING.find(html)?.groupValues?.get(1)
            ?.replace(TAG, "")
            ?.let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            ?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }

    private companion object {
        val HEADING = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val TAG = Regex("<[^>]*>")
        val SVG_FLOW_IMG = Regex(
            "<img[^>]*src=[\"']kindle:flow:([0-9A-V]+)\\?mime=image/svg\\+xml[\"'][^>]*/?>",
            RegexOption.IGNORE_CASE,
        )
        val EMBED_URL = Regex("kindle:embed:[0-9A-V]+(\\?mime=[-a-zA-Z0-9/+.]+)?", RegexOption.IGNORE_CASE)
    }
}

/** kindle:embed / kindle:flow 的编号是 base32（0-9A-V） */
internal fun mobiBase32(s: String): Long? {
    if (s.isEmpty()) return null
    var v = 0L
    for (c in s.uppercase()) {
        val d = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'V' -> c - 'A' + 10
            else -> return null
        }
        v = v * 32 + d
        if (v > Int.MAX_VALUE) return null
    }
    return v
}
