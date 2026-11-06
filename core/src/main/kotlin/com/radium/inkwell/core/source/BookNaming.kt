package com.radium.inkwell.core.source

/**
 * 入库、预览页用的（书名, 作者）：搜索 / 发现列表里有的就用列表里的，详情页只补空缺；
 * 书源开了 [BookInfoRuleSet.canReName] 才让详情页盖过列表。这是 Legado 的规矩
 * （BookInfo 里 `canReName && !infoRule.canReName.isNullOrBlank()` 那一段）。
 *
 * 为什么不是「详情页有就覆盖」：搜索页、发现页认「这本已在书架」靠的是**列表上**的书名 + 作者，
 * 跨书源合并成一本也靠它。从前入库取详情页的写法 —— 详情页作者规则取出空串（`?:` 兜不住空串）、
 * 或者写成「天蚕土豆 著」、书名多个后缀，书进了书架，列表上那一行却对不上，点完「加入」提示
 * 「已加入书架」，按钮照旧是「加入」，再点一次就从别的源加出第二本（被指出过）。
 *
 * 空白一律当作没有：详情页规则取到空元素时给的是空串而不是 null。
 */
fun bookNameAndAuthor(detail: RemoteBookDetail, listed: SearchResult?): Pair<String, String> {
    val listedTitle = listed?.title?.trim().orEmpty()
    val listedAuthor = listed?.author?.trim().orEmpty()
    val detailTitle = detail.title.trim()
    val detailAuthor = detail.author?.trim().orEmpty()
    val title = detailTitle.takeIf { it.isNotEmpty() && (detail.canReName || listedTitle.isEmpty()) } ?: listedTitle
    val author = detailAuthor.takeIf { it.isNotEmpty() && (detail.canReName || listedAuthor.isEmpty()) } ?: listedAuthor
    return title to author
}
