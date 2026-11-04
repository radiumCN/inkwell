package com.radium.inkwell.core.source.detect

import org.jsoup.nodes.Element
import java.util.IdentityHashMap

/** 一组并排的同构链接：同一个容器下、从容器往下到链接的标签路径（[relTags]）相同 */
internal class LinkGroup(val container: Element, val relTags: List<String>, val members: List<SiteLink>)

/**
 * 链接分组。目录识别（章节列表）和搜索识别（结果列表）共用：两者都是「页面上并排的一组同构条目」，
 * 都要和导航栏、侧栏推荐这类同样成组的链接分开。
 *
 * 从每条链接往上走，每一级祖先都按「它 + 往下到链接的标签路径」记一组：`#list` 下的 `dl>dd>a` 只含章节，
 * `body` 下的 `div>dl>dd>a` 可能连导航一起兜进来；分卷目录则要到包住所有分卷的那一级才完整。
 * 同一批链接在多级祖先上重复出现时只留最深的容器（就是它们的最近公共祖先）。
 */
internal object LinkGroups {

    fun of(links: List<SiteLink>, minSize: Int): List<LinkGroup> {
        val byContainer = IdentityHashMap<Element, HashMap<String, MutableList<Int>>>()
        links.forEachIndexed { i, link ->
            val path = ArrayList<String>()
            path += link.el.normalName()
            var p = link.el.parent()
            var up = 0
            while (p != null && up < MAX_UP) {
                val key = path.asReversed().joinToString(">")
                byContainer.getOrPut(p) { HashMap() }.getOrPut(key) { mutableListOf() } += i
                if (p.normalName() == "body") break
                path += p.normalName()
                p = p.parent()
                up++
            }
        }
        val raw = byContainer.flatMap { (container, byPath) ->
            val depth = container.parents().size
            byPath.mapNotNull { (key, idx) ->
                if (idx.size < minSize) null else Triple(depth, container, key to idx)
            }
        }
        // 深的容器先来：同一批链接只留最近公共祖先那一组
        val seen = HashSet<List<Int>>()
        return raw.sortedByDescending { it.first }
            .filter { (_, _, pair) -> seen.add(pair.second) }
            .map { (_, container, pair) -> LinkGroup(container, pair.first.split('>'), pair.second.map { links[it] }) }
    }

    /** [el] 往上走到 [container] 的直接子元素；[el] 不在它下面返回 null */
    fun childOnPath(container: Element, el: Element): Element? {
        var cur: Element? = el
        while (cur != null && cur.parent() !== container) cur = cur.parent()
        return cur
    }

    /** 从链接往上最多看几层容器：分卷目录 `div#catalog > div.volume > div > ul > li > a` 要五层 */
    private const val MAX_UP = 8
}
