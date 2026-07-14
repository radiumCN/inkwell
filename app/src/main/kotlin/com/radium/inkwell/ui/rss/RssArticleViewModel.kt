package com.radium.inkwell.ui.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.rss.RssArticle
import com.radium.inkwell.core.source.rss.RssEngine
import com.radium.inkwell.data.repo.RssRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 导航参数：文章本身随导航带过来，省得为了看一篇文章把整个列表再抓一遍 */
@Serializable
data class RssArticleArgs(
    val sourceId: String,
    val title: String,
    val link: String,
    val description: String? = null,
    val pubDate: String? = null,
)

data class RssArticleUiState(
    val title: String = "",
    val link: String = "",
    val pubDate: String? = null,
    val elements: List<ContentElement> = emptyList(),
    val loading: Boolean = true,
    /** 正文解析不出来 —— 只能拿浏览器打开原文 */
    val needsBrowser: Boolean = false,
    val error: String? = null,
)

class RssArticleViewModel(
    private val args: RssArticleArgs,
    private val repo: RssRepository,
) : ViewModel(), KoinComponent {

    private val engine: RssEngine by inject()

    private val _state = MutableStateFlow(
        RssArticleUiState(title = args.title, link = args.link, pubDate = args.pubDate),
    )
    val state: StateFlow<RssArticleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val rule = repo.getRule(args.sourceId)
            if (rule == null) {
                _state.value = _state.value.copy(loading = false, error = "订阅源不存在")
                return@launch
            }
            val article = RssArticle(
                sourceId = args.sourceId,
                title = args.title,
                link = args.link,
                description = args.description,
                pubDate = args.pubDate,
            )
            runCatching { engine.content(rule, article) }
                .onSuccess { elements ->
                    _state.value = _state.value.copy(
                        loading = false,
                        elements = elements.orEmpty(),
                        // 解析不出正文不是错误：很多源本来就只给标题+链接。
                        // 让用户去浏览器看原文，而不是对着一个空白页发呆
                        needsBrowser = elements.isNullOrEmpty(),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        needsBrowser = args.link.isNotBlank(),
                        error = it.message?.take(120),
                    )
                }
        }
    }
}
