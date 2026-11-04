package com.radium.inkwell.core.source.detect

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 目录里识别出的一章 */
data class DetectedChapter(val title: String, val url: String)

/**
 * 一组候选章节链接。
 *
 * @param listRule Legado `ruleToc.chapterList`（`@css:` 规则，倒序目录带前导 `-`）；
 *   配 `chapterName = text`、`chapterUrl = href` 用
 * @param chapters 用 [listRule] 在样本页上**实际选出来**的章节，按阅读顺序（已按 [reversed] 翻过），
 *   与引擎跑这条规则的结果一致，界面拿它给用户核对
 */
data class TocCandidate(
    val listRule: String,
    val chapters: List<DetectedChapter>,
    val reversed: Boolean,
    val score: Double,
) {
    /**
     * 拿来识别正文的那一章：开头一段里第一个正经章节，都不像就用第一章。
     * 第一章常是「作品相关」「上架感言」「请假条」，正文几十个字，拿它认正文会认到页脚上去。
     */
    val sampleChapter: DetectedChapter
        get() = chapters.take(SAMPLE_WINDOW).firstOrNull { ChapterTitles.isRegularChapter(it.title) }
            ?: chapters.first()

    private companion object {
        const val SAMPLE_WINDOW = 10
    }
}

/**
 * 目录页识别：在页面的所有站内链接里找出「章节列表」那一组，并生成选出它的规则。
 *
 * **分组**：一个容器元素 + 从容器往下到链接的标签路径（`dl>dd>a`）算一组。同一组链接在 DOM 上是
 * 并排的同构条目。从各级祖先都算一遍：`#list` 下的 `dl>dd>a` 只含章节，`body` 下的 `div>dl>dd>a`
 * 可能连导航一起兜进来；分卷目录则要到包住所有分卷的那一级才是完整的。同一组链接在多级祖先上
 * 重复出现时取最深的那个容器（就是它们的最近公共祖先）。
 *
 * **打分**：章节列表的特征是量大、地址同构（`/book/#/#.html`）、标题像「第X章」、地址带着本书书号。
 * 地址不同构的链接扣分 —— 兜进了导航栏的那一组因此输给纯章节的那组；分卷目录各卷地址同构，
 * 合起来的那组照样胜出。
 *
 * **最新章节块**：笔趣阁一类模板在完整目录上面先列十来个最新章节，和正文同在一个 `<dl>` 里。
 * 引擎按地址去重、保留**第一次**出现的那条，不排掉的话最新那十几章会排到目录最前面。
 * 识别出这种「开头一段在后面又出现了」的块，生成的规则把它排除掉（见 [exclusionSelectors]）。
 */
object TocDetector {

    fun detect(doc: Document, limit: Int = 3): List<TocCandidate> {
        val links = SiteLinks.collect(doc)
        if (links.size < MIN_CHAPTERS) return emptyList()
        val pageUrl = doc.location()
        val ranked = LinkGroups.of(links, MIN_CHAPTERS)
            .mapNotNull { g -> score(g.container, g.relTags, g.members, pageUrl) }
            .sortedByDescending { it.score }
        // 同一批章节换个容器、或多兜了几条导航，都算同一个候选；只留分高的那个
        val picked = mutableListOf<Group>()
        for (g in ranked) {
            if (picked.none { overlaps(it, g) }) picked += g
            if (picked.size >= limit * 2) break
        }
        return picked.mapNotNull { toCandidate(doc, it) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * 用户在网页上点了目录里的一章（[linkUrl]）：只在**包含这条链接**的分组里挑分最高、规则生成得出来的那组。
     *
     * 自动识别排前面的是分最高的组；被它「重合」掉的组（[overlaps]）根本进不了候选 —— 真目录恰好
     * 跟一个更大的错误分组重合时，换候选也换不到它。点选绕开这一步：用户点中的链接说了算。
     */
    fun detectAround(doc: Document, linkUrl: String): TocCandidate? {
        val target = linkUrl.substringBefore('#')
        val links = SiteLinks.collect(doc)
        val pageUrl = doc.location()
        return LinkGroups.of(links, MIN_CHAPTERS)
            .filter { g -> g.members.any { it.url == target } }
            .mapNotNull { g -> score(g.container, g.relTags, g.members, pageUrl) }
            .sortedByDescending { it.score }
            .firstNotNullOfOrNull { toCandidate(doc, it) }
    }

    // ---- 打分 ----

    private class Group(
        val container: Element,
        val relTags: List<String>,
        /** 组内全部链接，文档顺序，含最新章节块 */
        val members: List<SiteLink>,
        /** 开头那段「后面还会再出现」的链接数（最新章节块），0 = 没有 */
        val lead: Int,
        val urls: Set<String>,
        val score: Double,
    )

    private fun score(container: Element, relTags: List<String>, members: List<SiteLink>, pageUrl: String): Group? {
        val lead = leadingRepeats(members)
        val distinct = members.drop(lead).distinctBy { it.url }
        if (distinct.size < MIN_CHAPTERS) return null
        val n = distinct.size.toDouble()
        val fit = distinct.groupingBy { SiteLinks.shapeOf(it.url) }.eachCount().values.max()
        val misfit = distinct.size - fit
        val titleRatio = distinct.count { ChapterTitles.isChapterLike(it.text) } / n
        val relatedRatio = distinct.count { SiteLinks.isRelated(it.url, pageUrl) } / n
        var score = fit * (1 + titleRatio + relatedRatio) - MISFIT_WEIGHT * misfit
        // 「1 2 3 4」页码条、「A B C」字母索引：地址同构、量也不小，但标题短得不像话
        if (distinct.sumOf { it.text.length } / n < 3) score *= SHORT_TITLE_FACTOR
        return Group(container, relTags, members, lead, distinct.mapTo(HashSet()) { it.url }, score)
    }

    /**
     * 开头有多少条链接在后面又出现了一次。不到两条不算块 —— 单条重复多半是同一章挂了两个链接
     * （标题 + 「免费」标记），不是最新章节块。
     */
    private fun leadingRepeats(members: List<SiteLink>): Int {
        val last = HashMap<String, Int>()
        members.forEachIndexed { i, l -> last[l.url] = i }
        var k = 0
        while (k < members.size && last.getValue(members[k].url) > k) k++
        return if (k >= 2 && k < members.size) k else 0
    }

    private fun overlaps(a: Group, b: Group): Boolean {
        val small = if (a.urls.size <= b.urls.size) a.urls else b.urls
        val big = if (small === a.urls) b.urls else a.urls
        return small.count { it in big } > small.size * OVERLAP
    }

    // ---- 生成规则 ----

    private fun toCandidate(doc: Document, g: Group): TocCandidate? {
        val (selector, keptLead) = listSelector(doc, g) ?: return null
        val host = SiteLinks.hostOf(doc.location()) ?: return null
        var chapters = doc.select(selector)
            .mapNotNull { SiteLinks.linkOf(it, host) }
            .distinctBy { it.url }
            .map { DetectedChapter(it.text, it.url) }
        if (chapters.size < MIN_CHAPTERS) return null
        val reversed = isDescending(chapters)
        if (reversed) chapters = chapters.asReversed()
        return TocCandidate(
            listRule = (if (reversed) "-" else "") + "@css:$selector",
            chapters = chapters,
            reversed = reversed,
            // 排不掉最新章节块：能用，但开头会多出十几章，排在能排掉的候选后面
            score = if (keptLead) g.score * KEPT_LEAD_FACTOR else g.score,
        )
    }

    /**
     * 选出这一组的选择器，与「是否只能连最新章节块一起选」。
     * 从短到长试几种写法，取第一个**恰好**选中这组链接的：短的对模板小改动更宽容。
     */
    private fun listSelector(doc: Document, g: Group): Pair<String, Boolean>? {
        val cSel = CssPaths.unique(g.container)
        val tags = g.relTags
        val forms = listOf(
            "$cSel ${tags.takeLast(2).joinToString(" ")}",
            "$cSel ${tags.joinToString(" ")}",
            "$cSel > ${tags.joinToString(" > ")}",
        ).distinct()
        val all = g.members.map { it.el }
        if (g.lead > 0) {
            val main = g.members.drop(g.lead).map { it.el }
            exclusionSelectors(cSel, g).firstOrNull { selectsExactly(doc, it, main) }?.let { return it to false }
        }
        return forms.firstOrNull { selectsExactly(doc, it, all) }?.let { it to (g.lead > 0) }
    }

    /**
     * 排除开头最新章节块的写法，按稳健程度排：
     * 1. 按分隔标题：`<dt>最新章节</dt>…<dt>正文</dt>…` → 「前面有两个 dt 的 dd」（`dt ~ dt ~ dd`）。
     *    换一本章节少的书，最新块变短，这条照样对。
     * 2. 按位置：`dd:gt(13)`。最新块固定十二条时可用，但书不满十二章时会切掉正文开头，所以排第二。
     */
    private fun exclusionSelectors(cSel: String, g: Group): List<String> {
        val firstMain = LinkGroups.childOnPath(g.container, g.members[g.lead].el) ?: return emptyList()
        val lastLead = LinkGroups.childOnPath(g.container, g.members[g.lead - 1].el) ?: return emptyList()
        if (firstMain === lastLead) return emptyList()
        val itemTag = g.relTags[0]
        val rest = g.relTags.drop(1).joinToString(" ")
        val tail = if (rest.isEmpty()) "" else " $rest"
        val out = mutableListOf<String>()
        val seps = firstMain.previousElementSiblings().filter { it.normalName() != itemTag }
        if (seps.isNotEmpty() && seps.all { it.normalName() == seps[0].normalName() }) {
            val sep = seps[0].normalName()
            out += "$cSel > " + List(seps.size) { sep }.joinToString(" ~ ") + " ~ $itemTag$tail"
        }
        out += "$cSel > $itemTag:gt(${lastLead.elementSiblingIndex()})$tail"
        return out
    }

    /**
     * 选择器选出的**可用**链接恰好是 [expected]（同一批元素、同样顺序）。
     * 只比可用的：同一列表里夹一条 `javascript:` 的 VIP 占位，引擎拿到也只是跳过，不该因此判这条选择器不对。
     */
    private fun selectsExactly(doc: Document, selector: String, expected: List<Element>): Boolean {
        val host = SiteLinks.hostOf(doc.location()) ?: return false
        val got = CssPaths.select(doc, selector)?.filter { SiteLinks.linkOf(it, host) != null } ?: return false
        return got.size == expected.size && got.indices.all { got[it] === expected[it] }
    }

    /** 能抠出序号的章节里，大多数是一路往小走的 → 倒序目录（新章在上） */
    private fun isDescending(chapters: List<DetectedChapter>): Boolean {
        val nums = chapters.mapNotNull { ChapterTitles.numberOf(it.title) }
        if (nums.size < MIN_NUMBERED) return false
        var down = 0
        var upward = 0
        for (i in 1 until nums.size) {
            if (nums[i] < nums[i - 1]) down++ else if (nums[i] > nums[i - 1]) upward++
        }
        return down > upward * 2
    }

    /** 至少这么多章才当目录：新书只有一两章的，宁可识别失败让用户另想办法，也别把导航栏当目录 */
    private const val MIN_CHAPTERS = 3

    /** 判断正倒序至少要能抠出这么多个序号 */
    private const val MIN_NUMBERED = 5

    private const val MISFIT_WEIGHT = 2.0
    private const val SHORT_TITLE_FACTOR = 0.3
    private const val KEPT_LEAD_FACTOR = 0.8

    /** 两组链接重合超过这个比例（按小的那组算）就算同一个候选 */
    private const val OVERLAP = 0.8
}
