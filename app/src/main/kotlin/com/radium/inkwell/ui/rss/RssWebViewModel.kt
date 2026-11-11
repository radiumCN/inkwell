package com.radium.inkwell.ui.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.repo.RssRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RssWebUiState(
    /** 顶栏标题：订阅源的名字 */
    val title: String = "",
    /** 要打开的地址；null = 还在读订阅源（或读失败了，见 [error]） */
    val url: String? = null,
    /** 订阅源里写的请求头；User-Agent 单独给 WebView 设，其余随第一次请求带上 */
    val headers: Map<String, String> = emptyMap(),
    val enableJs: Boolean = true,
    /** 订阅源不在了、或者算不出地址 */
    val error: String? = null,
)

/**
 * 单网页源的网页页（阅读的 singleUrl：点开就是用内置浏览器打开一个网页）。
 *
 * 这里只管从订阅源里读出地址、请求头和脚本开关；网页自己的状态（进度、能不能后退、打没打开）
 * 跟着 WebView 走，放在页面里（见 RssWebScreen）。
 */
class RssWebViewModel(
    private val sourceId: String,
    private val repo: RssRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RssWebUiState())
    val state: StateFlow<RssWebUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch {
            val rule = repo.getRule(sourceId)
            if (rule == null) {
                _state.value = _state.value.copy(error = "订阅源不存在")
                return@launch
            }
            val url = rule.pageUrl()
            _state.value = if (url == null) {
                // 导入时就挡掉了算不出地址的；走到这里是订阅源被改过（同步来的、别的版本导的）
                RssWebUiState(title = rule.name, error = "这个订阅源的网页地址要跑脚本才算得出来，暂不支持")
            } else {
                RssWebUiState(title = rule.name, url = url, headers = rule.headers, enableJs = rule.enableJs)
            }
        }
    }
}
