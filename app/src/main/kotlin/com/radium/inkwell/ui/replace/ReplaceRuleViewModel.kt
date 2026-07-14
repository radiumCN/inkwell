package com.radium.inkwell.ui.replace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.data.repo.ReplaceRuleRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReplaceRuleUiState(
    val rules: List<ReplaceRuleEntity> = emptyList(),
    /** 正在编辑的规则；null = 没开编辑面板 */
    val editing: ReplaceRuleEntity? = null,
    /** 编辑面板里的试算：用当前规则跑一遍示例文本 */
    val sampleText: String = "",
    val sampleResult: String = "",
    val patternError: String? = null,
)

class ReplaceRuleViewModel(
    private val repo: ReplaceRuleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReplaceRuleUiState())
    val state: StateFlow<ReplaceRuleUiState> = _state.asStateFlow()

    val messages = MessageBus()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { rules ->
                _state.value = _state.value.copy(rules = rules)
            }
        }
    }

    fun setEnabled(rule: ReplaceRuleEntity, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(rule.id, enabled) }
    }

    fun delete(rule: ReplaceRuleEntity) {
        viewModelScope.launch { repo.delete(listOf(rule.id)) }
    }

    fun newRule() {
        _state.value = _state.value.copy(
            editing = ReplaceRuleEntity(id = "", name = "", pattern = ""),
            sampleText = "",
            sampleResult = "",
            patternError = null,
        )
    }

    fun edit(rule: ReplaceRuleEntity) {
        _state.value = _state.value.copy(
            editing = rule,
            sampleText = "",
            sampleResult = "",
            patternError = null,
        )
    }

    fun dismissEditor() {
        _state.value = _state.value.copy(editing = null)
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
        if (draft.pattern.isEmpty()) {
            _state.value = _state.value.copy(patternError = null, sampleResult = "")
            return
        }
        if (!draft.isRegex) {
            _state.value = _state.value.copy(
                patternError = null,
                sampleResult = sample.replace(draft.pattern, draft.replacement),
            )
            return
        }
        val regex = runCatching { Regex(draft.pattern) }
        regex.fold(
            onSuccess = { re ->
                _state.value = _state.value.copy(
                    patternError = null,
                    sampleResult = re.replace(sample, draft.replacement),
                )
            },
            onFailure = { e ->
                _state.value = _state.value.copy(
                    patternError = e.message?.take(120) ?: "正则表达式有误",
                    sampleResult = "",
                )
            },
        )
    }

    fun save() {
        val draft = _state.value.editing ?: return
        if (draft.pattern.isBlank()) {
            messages.emit("要替换的内容不能为空")
            return
        }
        if (draft.isRegex && runCatching { Regex(draft.pattern) }.isFailure) {
            messages.emit("正则表达式有误，改好再存")
            return
        }
        viewModelScope.launch {
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
            _state.value = _state.value.copy(editing = null)
            messages.emit("已保存")
        }
    }
}
