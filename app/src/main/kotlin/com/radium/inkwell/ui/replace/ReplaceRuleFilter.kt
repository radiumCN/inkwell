package com.radium.inkwell.ui.replace

import com.radium.inkwell.data.db.entity.ReplaceRuleEntity

/** 净化替换页搜索框下面那排筛选（字和来源管理一致：已启用 / 已禁用） */
enum class RuleFilter(val label: String) {
    ALL("全部"),
    ENABLED("已启用"),
    DISABLED("已禁用"),
    /** 阅读页长按选中一句话建的、只管那一本书的规则 */
    BOOK_ONLY("本书专属"),
}

/**
 * 搜索 + 筛选之后列表上显示的规则。顺序不动：净化按列表顺序一条条替换，列表就是执行顺序。
 *
 * 搜名称、替换什么、替换成什么、作用范围四样（不分大小写）：想清掉某个站的规则时，站名常常只写在规则内容里
 * （「最新网址：www.xxx.la」）或作用范围里，名字里没有 —— 只搜名字就漏了。
 */
internal fun filterRules(rules: List<ReplaceRuleEntity>, query: String, filter: RuleFilter): List<ReplaceRuleEntity> {
    val q = query.trim()
    return rules.filter { r ->
        val kept = when (filter) {
            RuleFilter.ALL -> true
            RuleFilter.ENABLED -> r.enabled
            RuleFilter.DISABLED -> !r.enabled
            RuleFilter.BOOK_ONLY -> r.bookId.isNotEmpty()
        }
        kept && (
            q.isEmpty() ||
                r.name.contains(q, ignoreCase = true) ||
                r.pattern.contains(q, ignoreCase = true) ||
                r.replacement.contains(q, ignoreCase = true) ||
                r.scope.contains(q, ignoreCase = true)
            )
    }
}
