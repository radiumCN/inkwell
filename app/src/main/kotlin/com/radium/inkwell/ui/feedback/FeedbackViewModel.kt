package com.radium.inkwell.ui.feedback

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.prefs.FeedbackReceipt
import com.radium.inkwell.data.repo.FeedbackRepository
import com.radium.inkwell.data.repo.FeedbackResult
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.radium.inkwell.util.RunLogRecorder
import com.radium.inkwell.util.UsageLog
import java.io.File
import java.util.UUID

data class FeedbackUiState(
    val content: String = "",
    val contact: String = "",
    val submitting: Boolean = false,
    val attachLogs: Boolean = false,
    val collectingLogs: Boolean = false,
    val attachments: List<FeedbackAttachment> = emptyList(),
    val readingAttachments: Boolean = false,
    val packingAttachments: Boolean = false,
    val pending: Boolean = false,
    val requestId: String = UUID.randomUUID().toString(),
    val submitted: FeedbackReceipt? = null,
) {
    val overLimit: Boolean get() = content.length > FeedbackRepository.MAX_CONTENT_LENGTH
    val canSubmit: Boolean get() = content.isNotBlank() && !overLimit && !submitting && !pending && !readingAttachments
}

class FeedbackViewModel(
    // lint 会报 StaticFieldLeak，但注入进来的是 Koin 的 androidContext()，即 Application ——
    // 不持有 Activity，不会泄漏。RssSourceViewModel / SourceManageViewModel 也是同样的写法。
    private val context: Context,
    private val repo: FeedbackRepository,
    private val appPrefs: AppPrefs,
    private val recorder: RunLogRecorder,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()
    val messages = MessageBus()
    private val replyNonce = "${UUID.randomUUID()}${UUID.randomUUID()}".replace("-", "")

    private val device = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val osVersion = "Android ${Build.VERSION.RELEASE}"

    /**
     * 提交时附带的环境信息，原样写给用户看（「v0.5.1-beta.9 · Xiaomi 24129PN74C · Android 16」）。
     * 从前只写「会附带应用版本、机型与系统版本」，附带的具体是什么得自己猜；现在和提交的是同一份。
     */
    val attachedInfo: String = listOfNotNull(appVersion(), device, osVersion).joinToString(" · ")

    fun setContent(v: String) {
        if (_state.value.submitting || _state.value.pending || _state.value.content == v) return
        _state.value = _state.value.copy(content = v, requestId = UUID.randomUUID().toString())
    }

    fun setContact(v: String) {
        if (_state.value.submitting || _state.value.pending || _state.value.contact == v) return
        _state.value = _state.value.copy(contact = v, requestId = UUID.randomUUID().toString())
    }

    fun setAttachLogs(v: Boolean) {
        if (_state.value.submitting || _state.value.pending || _state.value.attachLogs == v) return
        UsageLog.event("feedback.attach_logs", "enabled" to v)
        _state.value = _state.value.copy(attachLogs = v, requestId = UUID.randomUUID().toString())
    }

    fun addAttachments(uris: List<Uri>) {
        val s = _state.value
        if (uris.isEmpty() || s.submitting || s.pending || s.readingAttachments) return
        val newUris = uris.distinct().filter { uri -> s.attachments.none { it.uri == uri } }
        if (newUris.isEmpty()) return
        if (s.attachments.size + newUris.size > MAX_FEEDBACK_FILES) {
            messages.emit("最多添加 ${MAX_FEEDBACK_FILES} 个附件")
            return
        }
        _state.value = s.copy(readingAttachments = true)
        viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) { newUris.map { context.readFeedbackAttachment(it) } }
                val total = (s.attachments + files).fold(0L) { sum, file ->
                    sum + (file.sizeBytes ?: 0).coerceAtMost(MAX_FEEDBACK_FILE_BYTES + 1)
                }
                if (total > MAX_FEEDBACK_FILE_BYTES) {
                    messages.emit("附件合计最多 ${MAX_FEEDBACK_FILE_MIB} MiB，可裁短录屏或减少文件")
                    return@launch
                }
                _state.value = _state.value.copy(
                    attachments = s.attachments + files,
                    requestId = UUID.randomUUID().toString(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                messages.emit("无法读取所选附件，请重新选择")
            } finally {
                _state.value = _state.value.copy(readingAttachments = false)
            }
        }
    }

    fun removeAttachment(uri: Uri) {
        val s = _state.value
        if (s.submitting || s.pending || s.readingAttachments || s.attachments.none { it.uri == uri }) return
        _state.value = s.copy(
            attachments = s.attachments.filterNot { it.uri == uri },
            requestId = UUID.randomUUID().toString(),
        )
    }

    fun submit() {
        val s = _state.value
        // 双击会建出两个 issue，而且是在公开仓库里 —— 拦住
        if (!s.canSubmit) return
        UsageLog.event("ui.click", "action" to "feedback.submit", "attach_logs" to s.attachLogs,
            "attachment_count" to s.attachments.size)
        _state.value = s.copy(submitting = true)
        viewModelScope.launch {
            var logs: File? = null
            var attachments: File? = null
            try {
                if (s.attachments.isNotEmpty()) {
                    _state.value = _state.value.copy(packingAttachments = true)
                    try {
                        withContext(Dispatchers.IO) { attachments = context.packFeedbackAttachments(s.attachments) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: FeedbackFilesTooLarge) {
                        messages.emit("附件合计最多 ${MAX_FEEDBACK_FILE_MIB} MiB，可裁短录屏或减少文件")
                        return@launch
                    } catch (_: Exception) {
                        messages.emit("无法读取附件，请移除后重新选择")
                        return@launch
                    } finally {
                        _state.value = _state.value.copy(packingAttachments = false)
                    }
                }
                if (s.attachLogs) {
                    _state.value = _state.value.copy(collectingLogs = true)
                    try {
                        logs = recorder.exportForFeedback()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        UsageLog.event("feedback.collect.failed", "error" to e.javaClass.simpleName)
                        messages.emit("日志收集失败，可取消勾选后提交反馈")
                        return@launch
                    } finally {
                        _state.value = _state.value.copy(collectingLogs = false)
                    }
                }
                val result = repo.submit(
                    content = s.content.trim(),
                    contact = s.contact.trim().takeIf { it.isNotEmpty() },
                    appVersion = appVersion(),
                    device = device,
                    osVersion = osVersion,
                    channel = appPrefs.updateChannel.first().name.lowercase(),
                    requestId = s.requestId,
                    logs = logs,
                    replyNonce = replyNonce,
                    attachments = attachments,
                )
                UsageLog.event("feedback.result", "outcome" to when (result) {
                    is FeedbackResult.Success -> "success"
                    FeedbackResult.Pending -> "pending"
                    is FeedbackResult.NetworkError -> "network_error"
                    is FeedbackResult.RateLimited -> "rate_limited"
                    else -> "rejected"
                })
                when (result) {
                    is FeedbackResult.Success -> {
                        val title = s.content.trim().lineSequence().first().take(60)
                        val receipt = FeedbackReceipt(result.issue, result.url, title, result.replyToken)
                        try {
                            val saved = withContext(NonCancellable) { appPrefs.addFeedbackReceipt(receipt) }
                            if (!saved) messages.emit("反馈已提交，本机回复凭证保存失败")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            messages.emit("反馈已提交，本机记录保存失败")
                        }
                        _state.value = _state.value.copy(submitted = receipt)
                    }
                    FeedbackResult.InvalidContent -> messages.emit("请填写反馈内容")
                    FeedbackResult.InvalidAttachment -> messages.emit("服务端无法接收附件，可移除后重新选择")
                    // UI 层已按 MAX_CONTENT_LENGTH 拦过，走到这里说明拦漏了
                    FeedbackResult.TooLong ->
                        messages.emit("内容超过 ${FeedbackRepository.MAX_CONTENT_LENGTH} 字，请精简后再试")
                    FeedbackResult.AttachmentTooLarge -> messages.emit(
                        if (s.attachments.isEmpty()) "日志附件过大，可取消勾选后提交反馈"
                        else "上传内容超过限制，可减少附件或取消附带日志",
                    )
                    FeedbackResult.Pending -> {
                        _state.value = _state.value.copy(pending = true)
                        messages.emit("提交结果待确认，请勿重复提交")
                    }
                    is FeedbackResult.RateLimited -> messages.emit(rateLimitMessage(result.retryAfterSec))
                    // 服务端可能已经把 issue 建出来了，只是回程失败 —— 措辞别催用户重试
                    FeedbackResult.ServerDown -> messages.emit("服务暂时不可用，请稍后再试")
                    is FeedbackResult.NetworkError ->
                        messages.emit("提交失败：${result.message ?: "网络异常"}")
                }
            } finally {
                logs?.delete()
                attachments?.delete()
                _state.value = _state.value.copy(submitting = false, collectingLogs = false)
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
