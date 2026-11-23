package com.radium.inkwell.ui.sourcedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewFeature
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.allowsLoginReturn
import com.radium.inkwell.core.source.sourceOrigin
import com.radium.inkwell.core.source.SourceLoginForm
import com.radium.inkwell.core.source.SourceLoginException
import com.radium.inkwell.core.source.SourceLoginInteraction
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.js.StrResponse
import com.radium.inkwell.core.source.webLoginUrl
import com.radium.inkwell.core.source.webLoginUserAgent
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.data.source.SourceLoginSessions
import com.radium.inkwell.data.source.loginProfileName
import com.radium.inkwell.data.source.sourceLoginHttpUrl
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 书源详情（只读）。书源不在手机上编辑 —— 手机上编辑 JSON 体验差，规则一律靠导入。
 * 可打开来源登录、查看规则原文，或跑一键全链路测试。
 */
data class SourceDetailUiState(
    /** 库读回来了没有。没读回来时转圈；读回来了却没有这一行（[missing]）给空态，别是一片空白还能点「测试」 */
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val name: String = "",
    val jsonText: String = "",
    /**
     * 有没有搜索规则。「新建书源」生成的源没有：冒烟测试就不能从搜索开始，
     * 输入框改要一本书的网址，直接从详情页走起。
     */
    val searchable: Boolean = true,
    val testKeyword: String = "剑",
    val testing: Boolean = false,
    val testReport: String = "",
    val hasLogin: Boolean = false,
    val loginForm: SourceLoginForm? = null,
    val loginBusy: Boolean = false,
    val loginDiagnostic: String? = null,
    val loginBrowser: SourceLoginBrowserPage? = null,
)

data class SourceLoginBrowserPage(
    val url: String, val title: String, val requireCookies: Boolean, val profile: String, val returnOrigin: String?,
    /** 登录网页的 UA：和抓取这个来源发的同一个（BookSourceEngine.userAgentOf） */
    val userAgent: String,
    val desktopMode: Boolean = false,
)

class SourceDetailViewModel internal constructor(
    private val sourceId: String,
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
    private val sessions: SourceLoginSessions,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceDetailUiState())
    val state: StateFlow<SourceDetailUiState> = _state.asStateFlow()

    /** 一次性提示：与其他管理页一样走共享 MessageBus + CollectMessages */
    val messages = MessageBus()
    private var rule: BookSourceRule? = null
    private var browserResult: CompletableDeferred<StrResponse?>? = null
    private var browserCompletion: Job? = null

    init {
        viewModelScope.launch {
            val entity = sourceRepo.getEntity(sourceId)
            if (entity == null) {
                _state.value = _state.value.copy(loaded = true, missing = true)
                return@launch
            }
            // 解析 + 美化一整份规则 JSON（带大段脚本的源几十 KB），别在主线程上做
            val pretty = withContext(Dispatchers.Default) { prettyOrRaw(entity.json) }
            rule = sourceRepo.getRule(sourceId)
            val searchable = rule?.search != null
            _state.value = _state.value.copy(
                loaded = true,
                name = entity.name,
                jsonText = pretty,
                searchable = searchable,
                hasLogin = rule?.hasLogin == true,
                // 不能搜索的源要的是网址，预填的搜索词留着只会被当成网址去抓
                testKeyword = if (searchable) _state.value.testKeyword else "",
            )
        }
    }

    /** 规则原文按原生格式美化展示；解析不出来就原样显示（不因展示失败而空屏） */
    private fun prettyOrRaw(json: String): String =
        sourceRepo.parseRule(json).map { sourceRepo.encodeRule(it) }.getOrDefault(json)

    fun setTestKeyword(text: String) {
        _state.value = _state.value.copy(testKeyword = text)
    }

    fun openLogin() = launchLogin { source ->
        val webUrl = source.webLoginUrl
        if (webUrl == null) {
            val form = engine.getLoginForm(source)
            _state.update { it.copy(loginForm = form) }
        } else if (openBrowser(webUrl, source.name, requireCookies = true) != null) {
            messages.emit("已同步网页会话，可以重新校验来源")
        }
    }

    fun updateLoginField(name: String, value: String) {
        _state.update { state ->
            state.copy(loginForm = state.loginForm?.let { it.copy(values = it.values + (name to value)) })
        }
    }

    fun submitLogin(action: String? = null) = launchLogin { source ->
        val submitted = _state.value.loginForm?.values.orEmpty()
        var showedMessage = false
        var lastHttpStatus: Int? = null
        val interaction = object : SourceLoginInteraction {
            override fun message(text: String) {
                showedMessage = true
                messages.emit(text)
            }

            override fun updateFields(values: Map<String, String>) {
                values.forEach { (key, value) -> updateLoginField(key, value) }
            }

            override suspend fun openBrowser(url: String, title: String): StrResponse? =
                this@SourceDetailViewModel.openBrowser(url, title, requireCookies = false)

            override fun onHttpStatus(status: Int) {
                lastHttpStatus = status
            }
        }
        try {
            engine.submitLogin(source, submitted, action, interaction)
        } finally {
            lastHttpStatus?.takeIf { it == 401 || it == 403 }?.let { status ->
                recordLoginDiagnostic("站点拒绝了登录请求（HTTP $status）")
            }
        }
        val form = engine.getLoginForm(source)
        _state.update { state ->
            val edits = state.loginForm?.values.orEmpty().filter { (key, value) -> submitted[key] != value }
            val values = form.values.mapValues { (key, value) ->
                edits[key] ?: value.ifEmpty { submitted[key].orEmpty() }
            }
            state.copy(loginForm = form.copy(values = values))
        }
        if (action == null && !showedMessage && _state.value.loginDiagnostic == null) {
            messages.emit("登录请求已完成，可以重新校验来源")
        }
    }

    private fun launchLogin(block: suspend (BookSourceRule) -> Unit) {
        val source = rule ?: return
        if (_state.value.loginBusy) return
        _state.update { it.copy(loginBusy = true, loginDiagnostic = null) }
        viewModelScope.launch {
            try {
                block(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 来源脚本的异常可能含表单内容或请求凭据，不展示或记录原文。
                val message = _state.value.loginDiagnostic
                    ?: (e as? SourceLoginException)?.diagnostic?.message
                    ?: "来源登录操作失败，请检查来源规则"
                recordLoginDiagnostic(message)
                messages.emit(message)
            } finally {
                _state.update { it.copy(loginBusy = false) }
            }
        }
    }

    private fun recordLoginDiagnostic(message: String) {
        _state.update { it.copy(loginDiagnostic = message) }
    }

    private fun showBrowserDiagnostic(message: String) {
        recordLoginDiagnostic(message)
        messages.emit(message)
    }

    private suspend fun openBrowser(url: String, title: String, requireCookies: Boolean): StrResponse? =
        withContext(Dispatchers.Main.immediate) {
            val source = rule ?: return@withContext null
            val sessionId = source.loginSessionId ?: return@withContext null
            val target = url.sourceLoginHttpUrl()
            if (target == null) {
                showBrowserDiagnostic("该登录地址无法打开，请检查来源的 HTTPS 地址")
                return@withContext null
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                showBrowserDiagnostic("请更新 Android 系统 WebView 后再打开登录网页")
                return@withContext null
            }
            val userAgent = engine.userAgentOf(source)
            val desktopMode = userAgent != SourceHttpClient.LEGADO_UA &&
                withContext(Dispatchers.IO) {
                    sessions.webLoginUserAgent(sessionId, target) == SourceHttpClient.LEGADO_UA
                }
            val result = CompletableDeferred<StrResponse?>()
            browserResult = result
            val returnOrigin = source.baseUrl.sourceLoginHttpUrl()?.sourceOrigin()?.toString()
            val page = SourceLoginBrowserPage(
                target.toString(), title, requireCookies, loginProfileName(sessionId), returnOrigin, userAgent, desktopMode,
            )
            _state.update { it.copy(loginBrowser = page) }
            try {
                result.await()
            } finally {
                browserCompletion?.cancel()
                browserCompletion = null
                browserResult = null
                _state.update { it.copy(loginBrowser = null) }
            }
        }

    fun closeLoginBrowser() {
        val result = browserResult ?: return
        browserCompletion?.cancel()
        if (result.complete(null)) recordLoginDiagnostic("已取消网页登录")
    }

    fun finishLoginBrowser(url: String, html: String, userAgent: String, status: Int) {
        val page = _state.value.loginBrowser ?: return
        val result = browserResult ?: return
        if (result.isCompleted || browserCompletion?.isActive == true) return
        val start = page.url.sourceLoginHttpUrl() ?: return
        val source = rule ?: return
        val sessionId = source.loginSessionId ?: return
        val current = url.sourceLoginHttpUrl()?.takeIf { source.allowsLoginReturn(start, it) }
        if (current == null) {
            showBrowserDiagnostic("请完成网页登录并返回书源站点后再确认")
            return
        }
        browserCompletion = viewModelScope.launch {
            val cookies = withContext(Dispatchers.IO) {
                runCatching {
                    if (sessions.rememberWebLogin(sessionId, start, current, userAgent)) {
                        sessions.webCookies(sessionId, current).orEmpty()
                    } else null
                }.getOrNull()
            }
            if (result !== browserResult || result.isCompleted) return@launch
            val diagnostic = when {
                status == 401 || status == 403 -> "登录网页拒绝了请求（HTTP $status）"
                cookies == null -> "网页没有可同步的 Cookie，请确认是否已完成登录"
                else -> null
            }
            if (page.requireCookies && cookies == null) {
                showBrowserDiagnostic(checkNotNull(diagnostic))
                return@launch
            }
            _state.update { it.copy(loginDiagnostic = diagnostic) }
            if (cookies != null) engine.invalidateLoginPages(source)
            result.complete(StrResponse(bodyText = html, status = status, finalUrl = current.toString(),
                cookieStr = cookies.orEmpty()))
        }
    }

    /** 搜索→取第1本→详情→目录→第1章正文，一键冒烟；不能搜索的源从用户给的网址开始 */
    fun testSearchChain() {
        val rule = sourceRepo.parseRule(_state.value.jsonText).getOrElse {
            messages.emit("规则解析失败: ${it.message?.take(120)}")
            return
        }
        val input = _state.value.testKeyword.trim()
        if (rule.search == null && input.isEmpty()) {
            messages.emit("这个来源不能搜索，请填一本书的网址")
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
                    val hit = if (rule.search != null) {
                        val page = engine.search(rule, input)
                        step("✔ 搜索: ${page.items.size} 条结果")
                        val first = page.items.firstOrNull() ?: error("搜索无结果")
                        step("  首条: ${first.title} / ${first.author ?: "?"}")
                        first
                    } else {
                        step("· 不能搜索，从网址开始")
                        null
                    }

                    val (detail, toc) = netBookRepo.fetchDetailAndToc(
                        rule, hit?.bookUrl ?: input, hit?.variables.orEmpty(), trackReadHealth = false,
                    )
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
                    if (e is SourceLoginException) recordLoginDiagnostic(e.diagnostic.message)
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
