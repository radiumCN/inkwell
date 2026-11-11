package com.radium.inkwell.core.source.rss

/**
 * 订阅页「粘贴地址」粘进来的那个地址里装的是什么。
 *
 * 从前只看粘进来的字是不是 `http(s)://` 开头，是就当 feed 地址：源仓库给的订阅源合集地址
 * （`…/yuedu/rsss/json/id/205.json`，一个阅读订阅源的 JSON 数组）被当成 feed 建了一个订阅源，名字是域名、
 * 没有任何规则，导入时还说「新增 1」，点开才报「既不是 RSS/Atom feed，也没有配文章列表规则」（被指出过）。
 * 现在先下载、按内容分：
 */
enum class RssPayload {
    /** 阅读的订阅源（单条或合集）：按合集导入 */
    LEGADO_SOURCES,

    /** RSS / Atom feed：这个地址本身就是一个订阅源 */
    FEED,

    /** 阅读的书源（来源）合集：放错了地方，该去来源管理导 */
    BOOK_SOURCES,

    /** 都不是（普通网页、别的 JSON）：不建订阅源，说清楚 */
    UNKNOWN;

    companion object {
        fun of(body: String): RssPayload {
            val text = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (text.startsWith('[') || text.startsWith('{')) {
                return when {
                    text.contains("\"bookSourceUrl\"") -> BOOK_SOURCES
                    LegadoRssConverter.looksLikeLegadoRss(text) -> LEGADO_SOURCES
                    else -> UNKNOWN
                }
            }
            return if (RssXmlParser.looksLikeFeed(text)) FEED else UNKNOWN
        }
    }
}
