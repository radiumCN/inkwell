package com.radium.inkwell.ui.nav

import androidx.navigation3.runtime.NavKey
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.preview.BookPreviewCandidates
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ListDetailSceneStrategy.sceneKey：书架与设置各用一组，避免宽屏下互相抢 pane。 */
internal object NavPaneGroup {
    const val BOOKSHELF = "bookshelf"
    const val SETTINGS = "settings"
}

@Serializable
data object BookshelfRoute : NavKey

@Serializable
data class BookDetailRoute(val bookId: String) : NavKey

/**
 * 网络书籍预览（未入库）：看简介/目录，再决定加书架或直接读。
 *
 * 路由里只带**代表书源那一条**结果：不少 JSON API 书源的「详情页」其实是目录接口，解析不出
 * 书名/作者/封面（书名搜索时就给过了），得靠它回落。内容含 `/ ? &`，作为 path 参数会被切断，
 * 故 Base64 传递。
 *
 * 其余候选源（用来换源，可能有上百条、每条还带一整段简介）放进程内暂存，
 * 理由见 [BookPreviewCandidates] —— 全塞进路由会随返回栈进 Binder，撑爆就是崩溃。
 */
@Serializable
data class BookPreviewRoute(val resultsArg: String) : NavKey {
    /** 同一本书在各个书源下的搜索结果；预览页靠它换源。暂存丢了就只剩代表书源 */
    val results: List<SearchResult> get() {
        val representative: SearchResult = ROUTE_JSON.decodeFromString(decodeArg(resultsArg))
        return BookPreviewCandidates.get(BookPreviewCandidates.keyOf(representative))
            ?: listOf(representative)
    }

    companion object {
        fun of(results: List<SearchResult>): BookPreviewRoute {
            BookPreviewCandidates.put(results)
            return BookPreviewRoute(encodeArg(ROUTE_JSON.encodeToString(results.first())))
        }
    }
}

private val ROUTE_JSON = Json { ignoreUnknownKeys = true }

private fun encodeArg(s: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

private fun decodeArg(s: String): String =
    String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)

@Serializable
data class ReaderRoute(val bookId: String) : NavKey

@Serializable
data class SearchRoute(val initialQuery: String? = null) : NavKey

@Serializable
data object ExploreRoute : NavKey

@Serializable
data object RssSourceRoute : NavKey

@Serializable
data class RssArticlesRoute(val sourceId: String) : NavKey

/**
 * 文章阅读。整条文章随导航带过去 —— 为了看一篇文章把整个列表再抓一遍毫无道理，
 * 而且不少源的摘要（description）本来就是全文，重抓反而丢了它。
 * 内容含 `/ ? &`，作为 path 参数会被切断，故 Base64 传递。
 */
@Serializable
data class RssArticleRoute(val argsArg: String) : NavKey {
    val args: com.radium.inkwell.ui.rss.RssArticleArgs
        get() {
            val base: com.radium.inkwell.ui.rss.RssArticleArgs =
                ROUTE_JSON.decodeFromString(decodeArg(argsArg))
            // 正文（description）不进导航参数，从进程内暂存按链接取回；取不到（进程被杀）就为 null
            return base.copy(
                description = base.description
                    ?: com.radium.inkwell.ui.rss.RssArticleContent.get(base.link),
            )
        }

    companion object {
        fun of(args: com.radium.inkwell.ui.rss.RssArticleArgs): RssArticleRoute {
            // 全文正文放进程内暂存，导航参数只带短字段，避免撑爆 Binder 事务上限
            com.radium.inkwell.ui.rss.RssArticleContent.put(args.link, args.description)
            return RssArticleRoute(
                encodeArg(ROUTE_JSON.encodeToString(args.copy(description = null))),
            )
        }
    }
}

@Serializable
data object ReplaceRuleRoute : NavKey

@Serializable
data object SourceManageRoute : NavKey

/** 书源详情（只读）；书源不在应用内编辑，只能导入 */
@Serializable
data class SourceDetailRoute(val sourceId: String) : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data object AppearanceSettingsRoute : NavKey

@Serializable
data object ReadingSettingsRoute : NavKey

@Serializable
data object UpdateSettingsRoute : NavKey

@Serializable
data object AboutSettingsRoute : NavKey

@Serializable
data object ThemeSettingsRoute : NavKey

@Serializable
data object WebDavSettingsRoute : NavKey

@Serializable
data object FeedbackRoute : NavKey

@Serializable
data object DisclaimerRoute : NavKey

/**
 * 设置树里可作为 detail pane 的页面在树中的**层级**（`SettingsRoute` 自身算 1，非设置页为 null）。
 *
 * 为什么要层级而不是一个「是不是设置详情」的布尔：`InkwellNavigator.go` 得区分两件事 ——
 * **兄弟替换**（宽屏 list-detail 只有一格 detail，从设置列表点另一项时必须把当前那格换掉）
 * 和**下钻**（关于 → 意见反馈）。只有布尔的话两者写法一样，下钻也被当成替换，
 * 于是「关于」被从返回栈里抹掉：从意见反馈按返回，直接跳回设置一级页。
 *
 * 树形（层级即缩进深度）：
 * ```
 * 设置 (1)
 * ├─ 外观 (2) ── 主题 (3)
 * ├─ 阅读 (2) ─┬ 书源管理 (3) ── 书源详情 (4)
 * │            └ 替换规则 (3)
 * ├─ RSS 源 (2) ── 文章列表 (3) ── 文章 (4)
 * ├─ WebDAV (2)
 * ├─ 更新 (2)
 * └─ 关于 (2) ─┬ 意见反馈 (3)
 *              └ 免责声明 (3)
 * ```
 * 加新设置页时**记得在这里登记层级**：漏了会被当成非设置页，宽屏下拿不到 detail pane。
 */
internal fun NavKey.settingsDetailDepth(): Int? = when (this) {
    is AppearanceSettingsRoute,
    is ReadingSettingsRoute,
    is UpdateSettingsRoute,
    is AboutSettingsRoute,
    is WebDavSettingsRoute,
    is RssSourceRoute,
    -> 2

    is ThemeSettingsRoute,
    is FeedbackRoute,
    is DisclaimerRoute,
    is ReplaceRuleRoute,
    is SourceManageRoute,
    is RssArticlesRoute,
    -> 3

    is SourceDetailRoute,
    is RssArticleRoute,
    -> 4

    else -> null
}
