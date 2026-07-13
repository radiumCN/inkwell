package com.radium.inkwell.ui.sourceedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.data.repo.BookSourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SourceEditUiState(
    val jsonText: String = TEMPLATE,
    val testKeyword: String = "剑",
    val testing: Boolean = false,
    val testReport: String = "",
    val message: String? = null,
) {
    companion object {
        val TEMPLATE = """
            {
              "schemaVersion": 1,
              "id": "com.example.mysite",
              "name": "示例书源",
              "baseUrl": "https://www.example.com",
              "version": 1,
              "charset": null,
              "headers": {},
              "search": {
                "request": { "url": "/search?q={{keyword}}", "method": "GET" },
                "list": "css:div.result-item",
                "fields": {
                  "title": "css:h3 a@text",
                  "bookUrl": "css:h3 a@href",
                  "author": "css:.author@text"
                }
              },
              "detail": {
                "fields": { "intro": "css:.intro@text", "tocUrl": "css:a.toc@href" }
              },
              "toc": {
                "list": "css:dd a",
                "fields": { "title": "css:@text", "url": "css:@href" }
              },
              "content": {
                "content": "css:div#content@html",
                "purify": []
              }
            }
        """.trimIndent()
    }
}

class SourceEditViewModel(
    private val sourceId: String?,
    private val sourceRepo: BookSourceRepository,
    private val engine: BookSourceEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceEditUiState())
    val state: StateFlow<SourceEditUiState> = _state.asStateFlow()

    init {
        if (sourceId != null) {
            viewModelScope.launch {
                sourceRepo.getEntity(sourceId)?.let {
                    _state.value = _state.value.copy(jsonText = it.json)
                }
            }
        }
    }

    fun setJson(text: String) {
        _state.value = _state.value.copy(jsonText = text)
    }

    fun setTestKeyword(text: String) {
        _state.value = _state.value.copy(testKeyword = text)
    }

    fun formatJson() {
        sourceRepo.parseRule(_state.value.jsonText)
            .onSuccess {
                _state.value = _state.value.copy(
                    jsonText = sourceRepo.encodeRule(it),
                    message = "校验通过",
                )
            }
            .onFailure { _state.value = _state.value.copy(message = "JSON 错误: ${it.message?.take(120)}") }
    }

    fun save() {
        viewModelScope.launch {
            sourceRepo.importJson(_state.value.jsonText)
                .onSuccess {
                    _state.value = _state.value.copy(
                        message = if (it.imported > 0) "已保存" else "保存失败: ${it.skipped.firstOrNull() ?: "未知原因"}",
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = "保存失败: ${it.message?.take(120)}") }
        }
    }

    /** 搜索→取第1本→详情→目录→第1章正文，一键冒烟 */
    fun testSearchChain() {
        val rule = sourceRepo.parseRule(_state.value.jsonText).getOrElse {
            _state.value = _state.value.copy(message = "JSON 错误: ${it.message?.take(120)}")
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

                val detail = engine.getDetail(rule, first.bookUrl)
                report.appendLine("✔ 详情: ${detail.title} tocUrl=${detail.tocUrl.take(60)}")

                val toc = engine.getToc(rule, detail.tocUrl.ifBlank { first.bookUrl })
                report.appendLine("✔ 目录: ${toc.size} 章")
                val firstChapter = toc.firstOrNull() ?: error("目录为空")
                report.appendLine("  首章: ${firstChapter.title}")

                val content = engine.getContent(rule, firstChapter.url)
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

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
