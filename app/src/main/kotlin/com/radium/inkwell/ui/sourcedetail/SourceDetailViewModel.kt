package com.radium.inkwell.ui.sourcedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 书源详情（只读）。书源不在手机上编辑 —— 手机上编辑 JSON 体验差，规则一律靠导入。
 * 这里只做两件事：展示规则原文（只读）、跑一键全链路冒烟测试帮用户判断源好不好。
 */
data class SourceDetailUiState(
    val name: String = "",
    val jsonText: String = "",
    val testKeyword: String = "剑",
    val testing: Boolean = false,
    val testReport: String = "",
)

class SourceDetailViewModel(
    private val sourceId: String,
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceDetailUiState())
    val state: StateFlow<SourceDetailUiState> = _state.asStateFlow()

    /** 一次性提示：与其他管理页一样走共享 MessageBus + CollectMessages */
    val messages = MessageBus()

    init {
        viewModelScope.launch {
            sourceRepo.getEntity(sourceId)?.let {
                _state.value = _state.value.copy(name = it.name, jsonText = prettyOrRaw(it.json))
            }
        }
    }

    /** 规则原文按原生格式美化展示；解析不出来就原样显示（不因展示失败而空屏） */
    private fun prettyOrRaw(json: String): String =
        sourceRepo.parseRule(json).map { sourceRepo.encodeRule(it) }.getOrDefault(json)

    fun setTestKeyword(text: String) {
        _state.value = _state.value.copy(testKeyword = text)
    }

    /** 搜索→取第1本→详情→目录→第1章正文，一键冒烟 */
    fun testSearchChain() {
        val rule = sourceRepo.parseRule(_state.value.jsonText).getOrElse {
            messages.emit("规则解析失败: ${it.message?.take(120)}")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, testReport = "")
            val report = StringBuilder()
            try {
                val page = engine.search(rule, _state.value.testKeyword)
                report.appendLine("✔ 搜索: ${page.items.size} 条结果")
                val first = page.items.firstOrNull() ?: error("搜索无结果")
                report.appendLine("  首条: ${first.title} / ${first.author ?: "?"}")

                val (detail, toc) = netBookRepo.fetchDetailAndToc(rule, first.bookUrl)
                report.appendLine("✔ 详情: ${detail.title} tocUrl=${detail.tocUrl.take(60)}")
                report.appendLine("✔ 目录: ${toc.size} 章")
                val firstChapter = toc.firstOrNull() ?: error("目录为空")
                report.appendLine("  首章: ${firstChapter.title}")

                val content = engine.getContent(
                    rule, firstChapter.url,
                    toc.mapTo(HashSet()) { it.url },
                    chapterVariable = firstChapter.variable,
                )
                val preview = content.elements
                    .filterIsInstance<com.radium.inkwell.core.model.ContentElement.Paragraph>()
                    .take(2).joinToString(" / ") { it.text.take(40) }
                report.appendLine("✔ 正文: ${content.elements.size} 段")
                report.appendLine("  预览: $preview")
            } catch (e: Exception) {
                report.appendLine("✘ 失败: ${e.message}")
            }
            _state.value = _state.value.copy(testing = false, testReport = report.toString())
        }
    }
}
