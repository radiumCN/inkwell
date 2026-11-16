package com.radium.inkwell.ui.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.rss.RssArticle
import com.radium.inkwell.core.source.rss.RssEngine
import com.radium.inkwell.data.repo.RssFeedRepository
import com.radium.inkwell.data.repo.RssRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 导航参数：只带短字段。文章的全文（description，不少源就是全文）**不进参数** ——
 * 导航返回栈会被 onSaveInstanceState 序列化进 Bundle，全文（几十 KB）叠上几层就撑爆 Binder 事务上限（~1MB），
 * 直接 TransactionTooLargeException 崩溃。它在订阅缓存里（rss_article），文章页按 [feed] + [articleKey] 取。
 *
 * 从前取全文走的是一张进程内的表（按链接暂存、只进不出）：点开一篇就在内存里多攒一份全文，攒到进程结束，
 * 进程被杀之后又取不回来。缓存表里本来就有，用不着另存一份。
 */
@Serializable
data class RssArticleArgs(
    val sourceId: String,
    val title: String,
    val link: String,
    val pubDate: String? = null,
    /** 这篇在订阅缓存里的主键（RssArticleEntity 的 feed + articleKey）；不是从时间线点进来的为 null */
    val feed: String? = null,
    val articleKey: String? = null,
)

data class RssArticleUiState(
    val title: String = "",
    val link: String = "",
    val pubDate: String? = null,
    val elements: List<ContentElement> = emptyList(),
    val loading: Boolean = true,
    /** feed 只给了摘要，正在去原网页整理全文：摘要先亮着，文末一行转圈 */
    val fetchingFullText: Boolean = false,
    /** 正文是从原网页整理出来的：那个网站的域名（「正文取自原网页 · sspai.com」） */
    val fullTextHost: String? = null,
    /** 原网页没整理出全文的原因。摘要照旧显示，主按钮换成「在应用内打开原网页」 */
    val fullTextFailure: String? = null,
    val error: String? = null,
) {
    /** 原文是个网页，能在应用内打开（脚本地址、data: 地址不是） */
    val hasWebPage: Boolean
        get() = link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)

    /** 这里看不到正文（没有、没取到、只有摘要又整理不出全文）：原网页是主出口 */
    val webPageIsPrimary: Boolean
        get() = hasWebPage && !fetchingFullText && (elements.isEmpty() || error != null || fullTextFailure != null)
}

class RssArticleViewModel(
    private val args: RssArticleArgs,
    private val repo: RssRepository,
    private val feeds: RssFeedRepository,
) : ViewModel(), KoinComponent {

    private val engine: RssEngine by inject()

    private val _state = MutableStateFlow(
        RssArticleUiState(title = args.title, link = args.link, pubDate = args.pubDate),
    )
    val state: StateFlow<RssArticleUiState> = _state.asStateFlow()

    /** 这一次的抓取；重试时先取消上一次。写在 init 前面：属性按声明顺序初始化，写在后面会把 init 里存进来的 Job 覆盖成 null */
    private var job: Job? = null

    init {
        load()
    }

    /**
     * 抓正文。失败了页面上给「重试」（从前只能退出去再进来）。
     *
     * feed 只给了摘要的（少数派这类）：摘要先亮出来，再去原网页整理全文，整理出来就整个换掉；
     * 整理不出来摘要留着，说一句原因，原网页做主出口（从前只给「在浏览器中打开原文」，看全文只能离开应用）。
     */
    fun load() {
        job?.cancel()
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            fetchingFullText = false,
            fullTextHost = null,
            fullTextFailure = null,
        )
        job = viewModelScope.launch {
            val rule = repo.getRule(args.sourceId)
            if (rule == null) {
                _state.value = _state.value.copy(loading = false, error = "订阅源不存在")
                return@launch
            }
            // 缓存里没有（刚被清理掉）就当 feed 没给全文：有正文规则的照常抓，没有的去原网页整理
            val description = if (args.feed != null && args.articleKey != null) {
                feeds.description(args.feed, args.articleKey)
            } else {
                null
            }
            val article = RssArticle(
                sourceId = args.sourceId,
                title = args.title,
                link = args.link,
                description = description,
                pubDate = args.pubDate,
            )
            val content = try {
                engine.content(rule, article)
            } catch (e: Exception) {
                // 退出页面会取消这次抓取：不放出去的话被取消的协程还会接着往下把「抓取失败」写进状态
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(loading = false, error = e.message?.take(120) ?: "抓取失败")
                return@launch
            }
            _state.value = _state.value.copy(
                loading = false,
                elements = content.elements,
                fetchingFullText = content.teaser,
            )
            if (!content.teaser) return@launch

            val full = try {
                engine.fullText(rule, article, content.elements)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    fetchingFullText = false,
                    fullTextFailure = "原网页没打开（${e.message?.take(80) ?: "网络错误"}）",
                )
                return@launch
            }
            _state.value = if (full != null) {
                _state.value.copy(elements = full, fetchingFullText = false, fullTextHost = hostOf(args.link))
            } else {
                _state.value.copy(fetchingFullText = false, fullTextFailure = NOT_EXTRACTED)
            }
        }
    }

    private fun hostOf(link: String): String? =
        runCatching { java.net.URI(link).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    private companion object {
        /** 静态 HTML 里认不出正文：多半是要跑脚本才出内容的网页 */
        const val NOT_EXTRACTED = "原网页没能整理成正文（这类网页多半要跑脚本才出内容）"
    }
}
