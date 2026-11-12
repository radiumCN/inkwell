package com.radium.inkwell.ui.rss

import com.radium.inkwell.data.db.entity.RssSourceEntity
import com.radium.inkwell.ui.components.splitGroups

/** 订阅源页搜索框下面那排筛选（字和来源管理一致） */
enum class RssSourceFilter(val label: String) {
    ALL("全部"),
    ENABLED("已启用"),
    /** 关掉的源不出现在「订阅」里：一下挑出来，看看要不要开回来或者删掉 */
    DISABLED("已禁用"),
}

/** 订阅源页的列表：全部（判断空态）、搜索筛选之后显示的、出现过的分组（分组 chip） */
data class RssSourceList(
    val all: List<RssSourceEntity>,
    val visible: List<RssSourceEntity>,
    val groups: List<String>,
)

/**
 * 搜索（名称、地址、分组，不分大小写）+ 开关筛选 + 分组。顺序不动（同库里的排序）。
 * 记得是哪个站、不记得订的时候叫什么名字，搜地址就行。
 *
 * @param group null = 不按分组筛；指着一个已经没有的组时也当不筛（见 [RssSourceViewModel] 里的重置）
 */
internal fun buildRssSourceList(
    all: List<RssSourceEntity>,
    query: String,
    filter: RssSourceFilter,
    group: String?,
): RssSourceList {
    val groups = all.flatMap { splitGroups(it.groupName) }.distinct().sorted()
    val g = group?.takeIf { it in groups }
    val q = query.trim()
    val visible = all.filter { s ->
        val kept = when (filter) {
            RssSourceFilter.ALL -> true
            RssSourceFilter.ENABLED -> s.enabled
            RssSourceFilter.DISABLED -> !s.enabled
        }
        kept &&
            (g == null || g in splitGroups(s.groupName)) &&
            (
                q.isEmpty() ||
                    s.name.contains(q, ignoreCase = true) ||
                    s.id.contains(q, ignoreCase = true) ||
                    s.groupName.contains(q, ignoreCase = true)
                )
    }
    return RssSourceList(all = all, visible = visible, groups = groups)
}
