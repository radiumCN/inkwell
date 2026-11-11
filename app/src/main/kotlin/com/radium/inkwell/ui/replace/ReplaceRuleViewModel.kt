package com.radium.inkwell.ui.replace

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.data.repo.ReplaceRuleRepository
import com.radium.inkwell.data.repo.RuleImportItem
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.core.source.ImportedPurifyRule
import com.radium.inkwell.core.source.PurifyImportException
import com.radium.inkwell.core.source.PurifyRule
import com.radium.inkwell.core.source.PurifyRuleImport
import com.radium.inkwell.core.source.PurifyTimeoutException
import com.radium.inkwell.core.source.Purifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class ReplaceRuleUiState(
    val rules: List<ReplaceRuleEntity> = emptyList(),
    /** 规则列表已从库里读过一遍；在这之前 [rules] 的空不代表「还没有净化规则」，别闪空态 */
    val rulesLoaded: Boolean = false,
    /** 正在编辑的规则；null = 没开编辑面板 */
    val editing: ReplaceRuleEntity? = null,
    /** 打开编辑面板那一刻的规则，用来判断有没有没保存的改动 */
    val editingOriginal: ReplaceRuleEntity? = null,
    /** 编辑面板里的试算：用当前规则跑一遍示例文本 */
    val sampleText: String = "",
    /**
     * 试算结果；null = 还没有可展示的结果（「替换什么」还空着，或正则写错了 —— 错误另见 [patternError]）。
     *
     * 必须和空串分开：空串的意思是「整段被删光了」，页面会标红警告。从前没填规则时也给空串，
     * 刚粘进一段样例、还没开始写规则，就先被红字警告「这条规则大概太贪婪」。
     */
    val sampleResult: String? = null,
    val patternError: String? = null,
    /** 保存中：按钮转圈、不再接受第二下 */
    val saving: Boolean = false,
    /** 正在读文件 / 下载链接 / 解析（顶栏「导入」转圈） */
    val importLoading: Boolean = false,
    /** 导入面板：读出来的规则，逐条带状态；null = 面板没开 */
    val importItems: List<RuleImportItem>? = null,
    /** 导入面板里勾上的（规则 id） */
    val importSelected: Set<String> = emptySet(),
) {
    /** 编辑面板里有没保存的改动（只看规则本身；样例文本不存，不算） */
    val editorDirty: Boolean get() = editing != null && editing != editingOriginal
}

class ReplaceRuleViewModel(
    private val context: Context,
    private val repo: ReplaceRuleRepository,
    /** 「从网络链接导入」用注入的那个客户端：它配了超时（裸 OkHttpClient() 没有总闸） */
    private val http: OkHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ReplaceRuleUiState())
    val state: StateFlow<ReplaceRuleUiState> = _state.asStateFlow()

    val messages = MessageBus()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { rules ->
                _state.value = _state.value.copy(rules = rules, rulesLoaded = true)
            }
        }
    }

    fun setEnabled(rule: ReplaceRuleEntity, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(rule.id, enabled) }
    }

    fun delete(rule: ReplaceRuleEntity) {
        viewModelScope.launch { repo.delete(listOf(rule.id)) }
    }

    fun newRule() = openEditor(ReplaceRuleEntity(id = "", name = "", pattern = ""))

    fun edit(rule: ReplaceRuleEntity) = openEditor(rule)

    private fun openEditor(rule: ReplaceRuleEntity) {
        // 上一个面板还在飞的正则试算别落到这一个上
        recomputeJob?.cancel()
        _state.value = _state.value.copy(
            editing = rule,
            editingOriginal = rule,
            sampleText = "",
            sampleResult = null,
            patternError = null,
        )
    }

    fun dismissEditor() {
        recomputeJob?.cancel()
        _state.value = _state.value.copy(editing = null, editingOriginal = null)
    }

    fun updateDraft(draft: ReplaceRuleEntity) {
        _state.value = _state.value.copy(editing = draft)
        recompute(draft, _state.value.sampleText)
    }

    fun updateSample(text: String) {
        _state.value = _state.value.copy(sampleText = text)
        _state.value.editing?.let { recompute(it, text) }
    }

    /**
     * 试算：正则写错、或者写出个能把整段吃掉的规则，用户得在保存前就看见。
     * 净化是有损的 —— 一条贪婪的正则能把正文删空，而这在阅读时表现为「正文规则未匹配到内容」，
     * 根本查不到是净化干的。
     */
    private fun recompute(draft: ReplaceRuleEntity, sample: String) {
        // 每条路径都先掐掉上一次还在飞的正则试算 —— 不只是正则这条。从前只有正则分支 cancel：
        // 正则试到一半把开关拨成纯文本（或把规则删空），旧任务随后落地，把「正则有误」和它的结果
        // 写到一条纯文本规则上，保存按钮跟着 patternError 一直灰着，怎么改都点不动。
        recomputeJob?.cancel()
        if (draft.pattern.isEmpty()) {
            _state.value = _state.value.copy(patternError = null, sampleResult = null)
            return
        }
        if (!draft.isRegex) {
            _state.value = _state.value.copy(
                patternError = null,
                sampleResult = sample.replace(draft.pattern, draft.replacement),
            )
            return
        }
        // 编译和替换都要兜：`Regex(pattern)` 只管模式本身，替换串是另一套 Java 模板语法 ——
        // 写个 `$1` 而模式里没有分组、或者以 `$`/`\` 结尾，炸的是 `replace` 这一步
        // （IndexOutOfBounds / IllegalArgument）。不兜就是当场崩。
        //
        // 按键即算，而用户敲到一半的正则（`(a+)+`）碰上样例文本就可能陷进灾难性回溯 —— 从前直接在
        // 主线程上跑，当场卡死（ANR）。挪到 Default、走正式净化同一条路（Purifier → 看门狗）：
        // 超时就报「太复杂」，新的一次按键取消上一次。语义与正式净化完全一致，试算对、书里也就对。
        recomputeJob = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    Purifier.strict(listOf(PurifyRule(draft.pattern, draft.replacement, isRegex = true)))
                        .apply(sample)
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    _state.value = _state.value.copy(patternError = null, sampleResult = result)
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    val message = if (e is PurifyTimeoutException) {
                        "正则太复杂，在样例上执行超时（多半是嵌套的重复，如 (a+)+）"
                    } else {
                        // strict 把原始错误包成 SourceException，真正有用的说明在 cause 上
                        (e.cause ?: e).message?.take(120) ?: "正则表达式有误"
                    }
                    _state.value = _state.value.copy(patternError = message, sampleResult = null)
                },
            )
        }
    }

    private var recomputeJob: Job? = null

    fun save() {
        val s = _state.value
        // 连点两下「保存」：新建规则的 editing 要等 repo.create 回来才清空，这期间第二下照样进得来，
        // 于是建出两条一模一样的规则
        if (s.saving) return
        val draft = s.editing ?: return
        if (draft.pattern.isBlank()) {
            messages.emit("要替换的内容不能为空")
            return
        }
        if (draft.isRegex && runCatching { Regex(draft.pattern) }.isFailure) {
            messages.emit("正则表达式有误，改好再存")
            return
        }
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            try {
                if (draft.id.isEmpty()) {
                    repo.create(
                        name = draft.name.ifBlank { draft.pattern.take(20) },
                        pattern = draft.pattern,
                        replacement = draft.replacement,
                        isRegex = draft.isRegex,
                        scope = draft.scope,
                    )
                } else {
                    repo.save(draft.copy(name = draft.name.ifBlank { draft.pattern.take(20) }))
                }
                recomputeJob?.cancel()
                _state.value = _state.value.copy(editing = null, editingOriginal = null)
                messages.emit("已保存")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 面板留着，改的东西还在，用户可以再点一次
                messages.emit("保存失败: ${e.message?.take(80)}")
            } finally {
                _state.value = _state.value.copy(saving = false)
            }
        }
    }

    // ---- 导入（阅读的净化规则） ----

    private var importJob: Job? = null

    /**
     * 读进来先不入库：解析、和库里比一遍，开导入面板让人勾（见 ReplaceRuleImportSheet）。
     * 一次只读一份 —— 读个大链接要几秒，用户以为没点上又点一次，两份读出来的面板会互相顶掉。
     *
     * @param failPrefix 读不到时提示的开头（「读取失败」「下载失败」）；读到了但不是规则，统一「导入失败」
     */
    private fun startImport(failPrefix: String, read: suspend () -> String) {
        if (importJob?.isActive == true) {
            messages.emit("上一次导入还没完成")
            return
        }
        importJob = viewModelScope.launch {
            _state.value = _state.value.copy(importLoading = true)
            try {
                val text = try {
                    read()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    messages.emit("$failPrefix: ${e.message?.take(80) ?: "未知错误"}")
                    return@launch
                }
                // 每条正则都要编译一遍看写没写坏：几百条的合集放在主线程上会卡一下
                val rules = withContext(Dispatchers.Default) { PurifyRuleImport.parse(text) }
                val items = repo.previewImport(rules)
                _state.value = _state.value.copy(
                    importItems = items,
                    // 能导的默认全勾；「已有」「不支持」勾不上
                    importSelected = items.filter { it.selectable }.mapTo(HashSet()) { it.rule.id },
                )
            } catch (e: PurifyImportException) {
                messages.emit("导入失败: ${e.message}")
            } finally {
                _state.value = _state.value.copy(importLoading = false)
            }
        }
    }

    fun importFromFile(uri: Uri) = startImport("读取失败") {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 边读边数，超了就停：误选一个几百 MB 的文件，别整个读进内存
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    check(out.size() <= MAX_IMPORT_BYTES) { "文件太大（超过 ${MAX_IMPORT_BYTES shr 20}MB），不像是净化规则" }
                }
                out.toByteArray().toString(Charsets.UTF_8)
            } ?: error("无法读取文件")
        }
    }

    /** 也认阅读的分享链接（legado://import/replaceRule?src=…），见 [PurifyRuleImport.unwrapAppLink] */
    fun importFromUrl(url: String) {
        val target = PurifyRuleImport.unwrapAppLink(url)
        if (target.isEmpty()) return
        startImport("下载失败") {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(target).build()).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    // 同来源导入：先预读确认没超上限（request 只往缓冲里填、不消费），再交给 body.string() 按 BOM / 字符集解
                    val body = resp.body
                    check(!body.source().request(MAX_IMPORT_BYTES + 1L)) {
                        "文件太大（超过 ${MAX_IMPORT_BYTES shr 20}MB），不像是净化规则"
                    }
                    body.string()
                }
            }
        }
    }

    fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            messages.emit("剪贴板是空的")
            return
        }
        // 复制的可能是一条链接而不是规则本身（阅读里「分享」出来的就是链接）：是链接就去下载
        val trimmed = text.trim()
        if (trimmed.none { it == '\n' } && LINK_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) }) {
            importFromUrl(trimmed)
        } else {
            startImport("读取失败") { text }
        }
    }

    fun toggleImport(id: String) {
        val s = _state.value
        val item = s.importItems?.firstOrNull { it.rule.id == id } ?: return
        if (!item.selectable) return
        _state.value = s.copy(
            importSelected = if (id in s.importSelected) s.importSelected - id else s.importSelected + id,
        )
    }

    /** 全选 / 全不选：只动勾得上的那些 */
    fun setAllImport(selected: Boolean) {
        val s = _state.value
        val items = s.importItems ?: return
        _state.value = s.copy(
            importSelected = if (selected) items.filter { it.selectable }.mapTo(HashSet()) { it.rule.id } else emptySet(),
        )
    }

    /** 面板退场动画播完才清（同编辑面板：先清的话面板当场从组合树摘掉，动画被掐断） */
    fun dismissImport() {
        _state.value = _state.value.copy(importItems = null, importSelected = emptySet())
    }

    /**
     * 写进库。[rules] 由面板在收起之前取好 —— 面板收起时先回调 [dismissImport]、再办事，
     * 到这里 state 里的勾选已经清掉了。
     */
    fun confirmImport(rules: List<ImportedPurifyRule>) {
        if (rules.isEmpty()) return
        viewModelScope.launch {
            try {
                val r = repo.importRules(rules)
                messages.emit(
                    when {
                        r.added > 0 && r.updated > 0 -> "已导入 ${r.added} 条，更新 ${r.updated} 条"
                        r.added > 0 -> "已导入 ${r.added} 条"
                        r.updated > 0 -> "已更新 ${r.updated} 条"
                        else -> "这些规则都已经有了"
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.emit("导入失败: ${e.message?.take(80)}")
            }
        }
    }

    private companion object {
        /** 净化规则文件的大小上限：几百条规则也就几百 KB，再大就不是规则了，别整个读进内存 */
        const val MAX_IMPORT_BYTES = 8 shl 20
        /** 剪贴板里是这几种开头的一行字：当链接去下载，不当规则解析 */
        val LINK_SCHEMES = listOf("http://", "https://", "legado://", "yuedu://")
    }
}
