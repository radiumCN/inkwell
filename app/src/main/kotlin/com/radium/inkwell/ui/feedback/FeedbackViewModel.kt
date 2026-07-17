package com.radium.inkwell.ui.feedback

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.repo.FeedbackRepository
import com.radium.inkwell.data.repo.FeedbackResult
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FeedbackUiState(
    val content: String = "",
    val contact: String = "",
    val submitting: Boolean = false,
    /** 提交成功后的公开 issue 地址；非空即切到成功态 */
    val successUrl: String? = null,
) {
    val overLimit: Boolean get() = content.length > FeedbackRepository.MAX_CONTENT_LENGTH
    val canSubmit: Boolean get() = content.isNotBlank() && !overLimit && !submitting
}

class FeedbackViewModel(
    // lint 会报 StaticFieldLeak，但注入进来的是 Koin 的 androidContext()，即 Application ——
    // 不持有 Activity，不会泄漏。RssSourceViewModel / SourceManageViewModel 也是同样的写法。
    private val context: Context,
    private val repo: FeedbackRepository,
    private val appPrefs: AppPrefs,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    val messages = MessageBus()

    fun setContent(v: String) {
        _state.value = _state.value.copy(content = v)
    }

    fun setContact(v: String) {
        _state.value = _state.value.copy(contact = v)
    }

    fun submit() {
        val s = _state.value
        // 双击会建出两个 issue，而且是在公开仓库里 —— 拦住
        if (!s.canSubmit) return
        _state.value = s.copy(submitting = true)
        viewModelScope.launch {
            val result = repo.submit(
                content = s.content.trim(),
                contact = s.contact.trim().takeIf { it.isNotEmpty() },
                appVersion = appVersion(),
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersion = "Android ${Build.VERSION.RELEASE}",
                channel = appPrefs.updateChannel.first().name.lowercase(),
            )
            _state.value = _state.value.copy(submitting = false)
            when (result) {
                is FeedbackResult.Success ->
                    _state.value = _state.value.copy(successUrl = result.url)
                FeedbackResult.InvalidContent -> messages.emit("请填写反馈内容")
                // UI 层已按 MAX_CONTENT_LENGTH 拦过，走到这里说明拦漏了
                FeedbackResult.TooLong ->
                    messages.emit("内容超过 ${FeedbackRepository.MAX_CONTENT_LENGTH} 字，请精简后再试")
                is FeedbackResult.RateLimited -> messages.emit(rateLimitMessage(result.retryAfterSec))
                // 服务端可能已经把 issue 建出来了，只是回程失败 —— 措辞别催用户重试
                FeedbackResult.ServerDown -> messages.emit("服务暂时不可用，请稍后再试")
                is FeedbackResult.NetworkError ->
                    messages.emit("提交失败：${result.message ?: "网络异常"}")
            }
        }
    }

    /**
     * 限流是**按来源 IP** 算的（每小时若干条），同一个 WiFi 下别人提过就会占掉额度 ——
     * 所以这不是用户做错了什么，措辞得让人知道「等等就好」，而不是以为自己被封了。
     */
    private fun rateLimitMessage(retryAfterSec: Long?): String {
        val wait = when {
            retryAfterSec == null -> null
            retryAfterSec >= 3600 -> "${retryAfterSec / 3600} 小时"
            retryAfterSec >= 60 -> "${retryAfterSec / 60} 分钟"
            else -> "$retryAfterSec 秒"
        }
        return if (wait != null) "提交过于频繁，请 $wait 后再试" else "提交过于频繁，请稍后再试"
    }

    private fun appVersion(): String? = runCatching {
        "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
}
