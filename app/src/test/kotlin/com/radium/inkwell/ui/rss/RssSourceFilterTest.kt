package com.radium.inkwell.ui.rss

import com.radium.inkwell.data.db.entity.RssSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 订阅源页的搜索 + 筛选 + 分组（buildRssSourceList） */
class RssSourceFilterTest {

    private fun src(id: String, name: String, group: String = "", enabled: Boolean = true) =
        RssSourceEntity(id = id, name = name, groupName = group, enabled = enabled, json = "{}")

    private val all = listOf(
        src("https://sspai.com/feed", "少数派", group = "科技"),
        src("https://catcoding.me/atom.xml", "CatCoding", group = "博客,科技"),
        src("https://synyan.cn/feed", "旅行漫记", enabled = false),
        src("https://www.ruanyifeng.com/blog/atom.xml", "阮一峰的网络日志", group = "博客"),
    )

    private fun visible(query: String = "", filter: RssSourceFilter = RssSourceFilter.ALL, group: String? = null) =
        buildRssSourceList(all, query, filter, group).visible.map { it.name }

    @Test
    fun `搜名称和地址，不分大小写`() {
        assertEquals(listOf("CatCoding", "阮一峰的网络日志"), visible("ATOM"))
        assertEquals(listOf("少数派"), visible("少数"))
    }

    @Test
    fun `开关筛选`() {
        assertEquals(listOf("旅行漫记"), visible(filter = RssSourceFilter.DISABLED))
        assertEquals(3, visible(filter = RssSourceFilter.ENABLED).size)
    }

    @Test
    fun `分组：一个源可以挂几个组，列表去重排好`() {
        val list = buildRssSourceList(all, "", RssSourceFilter.ALL, null)
        assertEquals(listOf("博客", "科技"), list.groups)
        assertEquals(listOf("少数派", "CatCoding"), visible(group = "科技"))
        assertEquals(listOf("CatCoding", "阮一峰的网络日志"), visible(group = "博客"))
    }

    @Test
    fun `指着一个已经没有的组，当不筛 —— 不然列表空着、分组 chip 也没了，没有出口`() {
        assertEquals(4, visible(group = "已删掉的组").size)
    }

    @Test
    fun `全都没有分组时分组列表是空的（分组 chip 不出现）`() {
        assertEquals(emptyList<String>(), buildRssSourceList(listOf(src("u", "n")), "", RssSourceFilter.ALL, null).groups)
    }
}
