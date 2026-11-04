package com.radium.inkwell.core.source.detect

import com.radium.inkwell.core.source.LegadoSelector
import com.radium.inkwell.core.source.SearchRuleSet
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 搜索结果页的识别结论 */
sealed interface SearchDetection {
    /** [rules] 就是 Legado `ruleSearch`；配上 searchUrl 交给引擎 */
    data class Found(val rules: SearchRuleSet, val resultCount: Int) : SearchDetection

    /** 书的链接不到两条：一条结果看不出「一条」从哪到哪，零条多半是没搜出东西或停在了别的页 */
    data class TooFew(val count: Int) : SearchDetection
}

/**
 * 搜索结果页识别：找出结果列表，生成 `ruleSearch`（列表 + 书名 / 地址 / 作者 / 封面 / 最新章节）。
 *
 * **锚点是书页地址的形状**：向导前面已经认过一本书，它的地址形状（`/book/#/`）就是这个站「书页」的样子；
 * 结果页上同形状的链接就是书。拿不到（结果链去另一种详情页）再退回「标题里带着关键词」的链接去找形状。
 * 同形状的链接侧栏「热门推荐」里也有，所以照目录识别的办法分组，取带关键词最多的那组。
 *
 * **一条结果的范围**：书链接们的最近公共祖先下面，每条书链接所在的那个直接子元素（`li` / `tr` / `div.item`）。
 * 字段规则都相对于它写：Legado 的列表规则先选出一条条结果，字段在每一条里面求值。
 *
 * **字段规则必须恰好选中一个元素**：引擎对文本字段的多个匹配按换行拼起来，表格一行里有两个链接时，
 * `td a@text` 取到的是「书名\n最新章节」。所以每条字段规则都用 [LegadoSelector]（与引擎同一个实现）
 * 在每一条结果上验证过；CSS 写法验证不过就退回 Legado 的下标写法（`tag.td.0@tag.a.0`）。
 */
object SearchListDetector {

    fun detect(doc: Document, keyword: String, bookUrlShape: String?): SearchDetection {
        val links = SiteLinks.collect(doc).filter { !GENERIC_LINK.matches(it.text) }
        val books = bookLinks(links, keyword, bookUrlShape)
        fun hit(l: SiteLink) = keyword.isNotBlank() && l.text.contains(keyword, ignoreCase = true)
        // 页面上有带关键词的书链接，结果列表就该在含它们的那组里：只有一条结果时，
        // 侧栏「热门推荐」六七本、地址同形，单论个数它会赢，拿它当结果列表就全错了
        val keywordHits = books.filter(::hit).distinctBy { it.url }
        val group = LinkGroups.of(books, minSize = 2)
            .filter { g -> keywordHits.isEmpty() || g.members.any(::hit) }
            .maxByOrNull { g -> g.members.count(::hit) * 3 + g.members.size }
            ?: return SearchDetection.TooFew(keywordHits.ifEmpty { books.distinctBy { it.url } }.size)
        val members = group.members.distinctBy { it.url }
        if (members.size < 2) return SearchDetection.TooFew(members.size)

        val items = members.map { LinkGroups.childOnPath(group.container, it.el) ?: it.el }
        // 一条结果里有两本书（两个同形状链接落在同一个子元素下）：分不出条目，每个链接自己就是一条
        val perLink = items.toSet().size < items.size || group.relTags.size == 1
        val rows = if (perLink) members.map { it.el } else items

        val titles = members.map { it.el }
        val (list, title) = pickRules(doc, group, rows, titles, perLink, hrefFilterOf(members.first().url))
            ?: return SearchDetection.TooFew(members.size)
        val header = if (!perLink) tableHeader(rows) else emptyMap()
        val rules = SearchRuleSet(
            bookList = list,
            name = fieldRule(title, "text"),
            bookUrl = fieldRule(title, "href"),
            author = if (perLink) null else authorRule(rows, header),
            coverUrl = if (perLink) null else coverRule(rows),
            lastChapter = if (perLink) null else lastChapterRule(rows, members.map { it.el }, header),
        )
        return SearchDetection.Found(rules, members.size)
    }

    // ---- 书链接 ----

    private fun bookLinks(links: List<SiteLink>, keyword: String, shape: String?): List<SiteLink> {
        if (shape != null) {
            val same = links.filter { SiteLinks.shapeOf(it.url) == shape }
            if (same.distinctBy { it.url }.size >= 2) return same
        }
        if (keyword.isBlank()) return emptyList()
        val withKeyword = links.filter { it.text.contains(keyword, ignoreCase = true) }
        val dominant = withKeyword.groupingBy { SiteLinks.shapeOf(it.url) }.eachCount().maxByOrNull { it.value }?.key
            ?: return emptyList()
        return links.filter { SiteLinks.shapeOf(it.url) == dominant }
    }

    // ---- 列表 + 书名 ----

    /**
     * 列表规则 + 书名选择器，取第一对「按引擎的取法正好取出这些书」的（见 [yieldsExactly]）。
     *
     * 两样要一起验：列表规则多选进来的条目（表头行、广告项）本身无害，只要书名选择器在它们上面取不到东西 ——
     * 引擎拿不到书名就跳过这一条。从前只验了「列表按顺序选得到每一条结果」：同一个 `<p>` 里排着的作者链接、
     * 和结果同 class 的广告项，都成了搜索结果。
     */
    private fun pickRules(
        doc: Document,
        group: LinkGroup,
        rows: List<Element>,
        titles: List<Element>,
        perLink: Boolean,
        hrefFilter: String?,
    ): Pair<String, String>? {
        val titleForms = if (perLink) listOf("") else titleForms(rows, titles, hrefFilter)
        val listForms = listForms(group, rows, perLink, hrefFilter)
        for (title in titleForms) {
            for (list in listForms) {
                if (yieldsExactly(doc, list, title, titles)) return list to title
            }
        }
        return null
    }

    /** 列表规则的候选，从短到长 */
    private fun listForms(group: LinkGroup, rows: List<Element>, perLink: Boolean, hrefFilter: String?): List<String> {
        val cSel = CssPaths.unique(group.container)
        val forms = if (perLink) {
            listOfNotNull(
                "$cSel ${group.relTags.takeLast(2).joinToString(" ")}",
                "$cSel > ${group.relTags.joinToString(" > ")}",
                // 每个链接自己就是一条、旁边还排着作者链接：只能按地址把书挑出来
                hrefFilter?.let { "$cSel a$it" },
            )
        } else {
            val tag = group.relTags[0]
            val shared = rows.map { r -> r.classNames().filter(CssPaths::isStable).toSet() }
                .reduce { a, b -> a intersect b }
            shared.map { "$cSel > $tag.$it" } + "$cSel > $tag"
        }
        return forms.distinct().map { "@css:$it" }
    }

    /**
     * 按引擎的取法 —— 列表选出条目，每条上用书名选择器取元素（[title] 为空时条目自己就是书名链接）——
     * 取出来的恰好是 [titles]、顺序一致。取不到的条目跳过（引擎也跳过），一条上取到两个就不行（引擎会把它们拼起来）。
     */
    private fun yieldsExactly(doc: Document, list: String, title: String, titles: List<Element>): Boolean {
        val got = LegadoSelector.elements(doc, list).mapNotNull { row ->
            if (title.isEmpty()) return@mapNotNull row
            val hits = LegadoSelector.elements(row, title)
            if (hits.size > 1) return false
            hits.firstOrNull()
        }
        return got.size == titles.size && got.indices.all { got[it] === titles[it] }
    }

    /**
     * 按书的地址挑链接的 CSS 条件：地址形状里第一个数字之前的那段（`/book/#/` → `[href*="/book/"]`）。
     * 只剩一个 `/` 的形状（`/#.html`）挑不出什么，不用。
     */
    private fun hrefFilterOf(url: String): String? {
        val literal = SiteLinks.shapeOf(url).substringBefore('#')
        if (literal.length < 2 || '"' in literal) return null
        return "[href*=\"$literal\"]"
    }

    /**
     * 每条结果里恰好选中书名链接的选择器（不含提取器），按优先顺序列出所有验证过的。CSS 写法在前，读得懂、
     * 对小改版宽容；都不行就按下标：`tag.td.0@tag.a.0` —— Legado 的下标作用于匹配集，表格里第几列第几个链接，稳。
     */
    private fun titleForms(rows: List<Element>, titles: List<Element>, hrefFilter: String?): List<String> {
        val forms = buildList {
            val linkClasses = titles.map { t -> t.classNames().filter(CssPaths::isStable).toSet() }.reduce { a, b -> a intersect b }
            linkClasses.forEach { add("@css:a.$it") }
            val parents = titles.map { it.parent() }
            if (parents.all { it != null }) {
                val parentTag = parents.map { it!!.normalName() }.distinct().singleOrNull()
                if (parentTag != null) {
                    val parentClasses = parents.map { p -> p!!.classNames().filter(CssPaths::isStable).toSet() }
                        .reduce { a, b -> a intersect b }
                    parentClasses.forEach { add("@css:$parentTag.$it a") }
                    add("@css:$parentTag a")
                }
            }
            // 和结果长得一样的广告项：只有按地址才分得开
            hrefFilter?.let { add("@css:a$it") }
            add("@css:a")
            indexPath(rows.first(), titles.first())?.let { add(it) }
        }
        return forms.distinct().filter { sel ->
            rows.indices.all { i -> LegadoSelector.elements(rows[i], sel).singleOrNull() === titles[i] }
        }
    }

    /** Legado 下标路径：[row] 里第几个某标签 → 它里面第几个 `a`。[row] 自己就是链接时为 null */
    private fun indexPath(row: Element, link: Element): String? {
        if (row === link) return null
        val cell = LinkGroups.childOnPath(row, link) ?: return null
        val linkAt = cell.getElementsByTag("a").indexOf(link)
        if (cell === link) return "tag.a.${row.getElementsByTag("a").indexOf(link)}"
        val cellAt = row.getElementsByTag(cell.normalName()).indexOf(cell)
        if (cellAt < 0 || linkAt < 0) return null
        return "tag.${cell.normalName()}.$cellAt@tag.a.$linkAt"
    }

    /** 选择器 + 提取器拼成字段规则；选择器为空（每个链接自己就是一条）时直接取它自己的 */
    private fun fieldRule(selector: String, extractor: String): String =
        if (selector.isEmpty()) extractor else "$selector@$extractor"

    // ---- 作者 / 最新章节 / 封面 ----

    /**
     * 表格式结果页的表头：列名 → 第几列。老站的搜索结果几乎都是
     * `<tr><th>文章名称</th><th>最新章节</th><th>作者</th>…`，按表头认列比猜内容可靠得多。
     */
    private fun tableHeader(rows: List<Element>): Map<String, Int> {
        val first = rows.first()
        if (first.normalName() != "tr") return emptyMap()
        val table = first.parents().firstOrNull { it.normalName() == "table" } ?: return emptyMap()
        val head = table.select("tr").firstOrNull { tr -> tr.children().any { it.normalName() == "th" } }
            ?: table.select("tr").firstOrNull { tr -> tr !in rows && tr.select("a").isEmpty() }
            ?: return emptyMap()
        val out = HashMap<String, Int>()
        head.children().forEachIndexed { i, cell ->
            val text = cell.text().trim()
            when {
                AUTHOR_HEAD.containsMatchIn(text) -> out.putIfAbsent(AUTHOR, i)
                LATEST_HEAD.containsMatchIn(text) -> out.putIfAbsent(LATEST, i)
            }
        }
        return out
    }

    private fun authorRule(rows: List<Element>, header: Map<String, Int>): String? {
        val strip = "##${BookInfoDetector.AUTHOR_STRIP.pattern}"
        val forms = buildList {
            header[AUTHOR]?.let { col -> add(columnRule(col) + "@text") }
            // class 里带 author 的元素：`<span class="author">作者：某某</span>`
            val classes = rows.flatMap { r -> r.allElements.drop(1).flatMap { it.classNames() } }
                .filter { CssPaths.isStable(it) && it.contains("author", ignoreCase = true) }
                .distinct()
            classes.forEach { add("@css:.$it@text") }
            // 「作者：某某」那一行，按文字认，不按位置
            add("@css::matchesOwn(${BookInfoDetector.AUTHOR_OWN})@text")
        }
        return forms.firstOrNull { rule -> coversMost(rows) { row -> single(row, rule)?.let(::stripAuthor) } }
            ?.let { rule -> if (rule.startsWith("tag.")) rule else rule + strip }
    }

    private fun stripAuthor(text: String): String? =
        BookInfoDetector.AUTHOR_STRIP.replace(text, "").trim().takeIf { it.isNotEmpty() }

    private fun lastChapterRule(rows: List<Element>, titles: List<Element>, header: Map<String, Int>): String? {
        header[LATEST]?.let { col ->
            val rule = columnRule(col) + "@text"
            if (coversMost(rows) { single(it, rule) }) return rule
        }
        // 每条结果里除书名外第一个像章节名的链接，位置各条一致才用
        val positions = rows.indices.map { i ->
            rows[i].getElementsByTag("a").indexOfFirst { a -> a !== titles[i] && ChapterTitles.isChapterLike(a.text()) }
        }
        val at = positions.groupingBy { it }.eachCount().filterKeys { it >= 0 }.maxByOrNull { it.value }?.key ?: return null
        val rule = "tag.a.$at@text"
        return rule.takeIf { coversMost(rows) { row -> single(row, it)?.takeIf(ChapterTitles::isChapterLike) } }
    }

    /** 列号 → Legado 下标规则。表头格可能是 th，数据行一律是 td，下标作用于行内的 td 匹配集 */
    private fun columnRule(col: Int) = "tag.td.$col"

    /** 封面：结果里第一张图。懒加载站真地址在 data-original / data-src 里，src 是张占位图 */
    private fun coverRule(rows: List<Element>): String? {
        for (attr in COVER_ATTRS) {
            val rule = "@css:img@$attr"
            if (coversMost(rows) { row -> single(row, rule)?.takeIf { !it.startsWith("data:") } }) return rule
        }
        return null
    }

    /** 规则在这一条上恰好取到一个非空值（多个匹配会被引擎换行拼接，不算） */
    private fun single(row: Element, rule: String): String? {
        val core = rule.substringBefore("##")
        return LegadoSelector.strings(row, core).singleOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 大多数条目都取得到：零星几条缺作者、缺封面是站点数据的事，不是规则不对 */
    private inline fun coversMost(rows: List<Element>, value: (Element) -> String?): Boolean =
        rows.count { value(it) != null } * 2 > rows.size

    private const val AUTHOR = "author"
    private const val LATEST = "latest"
    private val AUTHOR_HEAD = Regex("作\\s*者")
    private val LATEST_HEAD = Regex("最新|章节")
    private val COVER_ATTRS = listOf("data-original", "data-src", "data-lazy-src", "data-echo", "src")

    /** 结果条目里的动作链接：和书名链接同一个地址，文字却不是书名 */
    private val GENERIC_LINK = Regex("^(立即|开始|免费|在线|马上)?(阅读|试读|详情|查看|进入|加入书架|收藏|下载)(详情|全文)?$")
}
