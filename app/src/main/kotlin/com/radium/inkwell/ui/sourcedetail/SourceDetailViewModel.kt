package com.radium.inkwell.ui.sourcedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 书源详情（只读）。书源不在手机上编辑 —— 手机上编辑 JSON 体验差，规则一律靠导入。
 * 这里只做两件事：展示规则原文（只读）、跑一键全链路冒烟测试帮用户判断源好不好。
 */
data class SourceDetailUiState(
    /** 库读回来了没有。没读回来时转圈；读回来了却没有这一行（[missing]）给空态，别是一片空白还能点「测试」 */
    val loaded: Boolean = false,
    val missing: Boolean = false,
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
            val entity = sourceRepo.getEntity(sourceId)
            if (entity == null) {
                _state.value = _state.value.copy(loaded = true, missing = true)
                return@launch
            }
            // 解析 + 美化一整份规则 JSON（带大段脚本的源几十 KB），别在主线程上做
            val pretty = withContext(Dispatchers.Default) { prettyOrRaw(entity.json) }
            _state.value = _state.value.copy(loaded = true, name = entity.name, jsonText = pretty)
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
            // 每走完一步就把报告刷上去：四步全走完要十几秒，从前要么只看到转圈、要么最后一次性冒出来，
            // 卡在哪一步都看不出来
            fun step(line: String) {
                report.appendLine(line)
                _state.value = _state.value.copy(testReport = report.toString())
            }
            val finished = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                try {
                    val page = engine.search(rule, _state.value.testKeyword)
                    step("✔ 搜索: ${page.items.size} 条结果")
                    val first = page.items.firstOrNull() ?: error("搜索无结果")
                    step("  首条: ${first.title} / ${first.author ?: "?"}")

                    val (detail, toc) = netBookRepo.fetchDetailAndToc(rule, first.bookUrl)
                    step("✔ 详情: ${detail.title} tocUrl=${detail.tocUrl.take(60)}")
                    step("✔ 目录: ${toc.size} 章")
                    val firstChapter = toc.firstOrNull() ?: error("目录为空")
                    step("  首章: ${firstChapter.title}")

                    val content = engine.getContent(
                        rule, firstChapter.url,
                        toc.mapTo(HashSet()) { it.url },
                        chapterVariable = firstChapter.variable,
                        chapterTitle = firstChapter.title,
                        chapterIndex = firstChapter.index,
                        variables = detail.variables,
                    )
                    val preview = content.elements
                        .filterIsInstance<com.radium.inkwell.core.model.ContentElement.Paragraph>()
                        .take(2).joinToString(" / ") { it.text.take(40) }
                    step("✔ 正文: ${content.elements.size} 段")
                    step("  预览: $preview")
                } catch (e: CancellationException) {
                    // 离开页面掐掉了冒烟，不是书源坏了 —— 别往报告里写一条「失败」
                    throw e
                } catch (e: Exception) {
                    step("✘ 失败: ${e.message}")
                }
            }
            // 超时：withTimeoutOrNull 只在自己的超时上返回 null，离开页面的取消照样往外抛
            if (finished == null) report.appendLine("✘ 超时: ${TEST_TIMEOUT_MS / 1000} 秒内没走完，卡在上面最后一步之后")
            _state.value = _state.value.copy(testing = false, testReport = report.toString())
        }
    }

    private companion object {
        /** 全链路测试的总时限：四次网络往返，各自的超时叠起来可能好几分钟，人不会等那么久 */
        const val TEST_TIMEOUT_MS = 60_000L
    }
}
