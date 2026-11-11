package com.radium.inkwell.ui.rss

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.source.WebViewNetworkGuard
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.rememberExternalLinkOpener
import com.radium.inkwell.ui.components.settingsPageColor
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * 单网页源的网页页：窄顶栏 + 网页铺满（阅读的 singleUrl 点开就是内置浏览器，照做）。
 *
 * - 顶栏是实色：WebView 不在 Compose 里画，录不进毛玻璃取样的图层（同来源向导的网页）。窄栏而不是大标题栏：
 *   WebView 不接嵌套滚动，大标题收不起来，白占一截。
 * - 标题是订阅源的名字；右边刷新、用浏览器打开（先把完整地址亮出来问一句，同文章页的「浏览器打开原文」）。
 * - 系统返回先在网页里后退，退到头才离开；顶栏的 ‹ 直接回订阅列表。
 * - 加载进度是叠在网页顶上的一条线，不放进顶栏 —— 放进顶栏的话它一出一收，顶栏跟着变高变矮，整页网页上下跳。
 * - 主文档打不开（断网、域名失效）盖一层错误态 +「重试」，别留一张 WebView 自己的报错页。
 */
@Composable
fun RssWebScreen(
    onBack: () -> Unit,
    viewModel: RssWebViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages = remember { MessageBus() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(messages, snackbar)
    val links = rememberExternalLinkOpener { messages.emit(NO_BROWSER_MESSAGE) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    // 0..100；100 = 这一页加载完了，进度线收起
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // 当前在看的那一页（点进去之后不再是订阅源地址）：「用浏览器打开」打开的是它
    var currentUrl by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    fun reload() {
        pageError = null
        webView?.reload()
    }

    val pageColor = settingsPageColor()
    AppScaffold(
        blurBars = false,
        containerColor = pageColor,
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            AppToolbar(
                navigationIcon = { BackButton(onClick = onBack) },
                containerColor = pageColor,
                actions = {
                    val url = currentUrl ?: state.url
                    if (url != null) {
                        AppIconButton(onClick = ::reload) {
                            Icon(MiuixIcons.Refresh, contentDescription = "刷新")
                        }
                        AppIconButton(onClick = { links.open(url) }) {
                            Icon(MiuixIcons.Forward, contentDescription = "用浏览器打开")
                        }
                    }
                },
            ) {
                ToolbarTitle(state.title)
            }
        },
    ) { padding ->
        // 不滚动的整页：整块让开系统栏；网页里有输入框，键盘弹起时把网页一起顶上去
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            val url = state.url
            val error = state.error
            when {
                error != null -> ErrorState(message = error, onRetry = viewModel::load)
                url == null -> LoadingState()
                else -> {
                    SourceWebView(
                        url = url,
                        headers = state.headers,
                        enableJs = state.enableJs,
                        onCreated = { webView = it },
                        onReleased = { webView = null },
                        onProgress = { progress = it },
                        onPageStarted = {
                            currentUrl = it
                            pageError = null
                        },
                        onHistoryChanged = { canGoBack = it },
                        onMainFrameError = { pageError = it },
                        onExternalLink = links::open,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (progress in 0 until 100) {
                        DeterminateProgressBar(
                            progress = progress / 100f,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                    pageError?.let { message ->
                        ErrorState(
                            message = message,
                            // 盖住 WebView 自己那张报错页
                            modifier = Modifier.background(pageColor),
                            onRetry = ::reload,
                            secondaryLabel = "用浏览器打开",
                            onSecondary = { links.open(currentUrl ?: url) },
                        )
                    }
                }
            }
        }
    }

    // 切到后台、盖上别的页：网页里的视频、计时器停下来（WebView 不会自己停）
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * 网页本身。和来源向导的 WizardWebView 是两回事（那个要截表单、点选元素），只有两条一致：
 * 页面不许碰本机文件和内网（[WebViewNetworkGuard]，同一道拦截）。
 *
 * - 请求头照订阅源里写的：User-Agent 设给 WebView（之后每个请求都带），其余随第一次请求带上（WebView 只给 loadUrl 这一下带头）。
 *   没写 UA 就用 WebView 自己的（手机版 Chrome）—— 给人看的网页，站点按它出手机版。
 * - `weixin://`、`intent://` 这类拉起别的应用的链接、下载，交给 [onExternalLink]（先问一句再跳）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SourceWebView(
    url: String,
    headers: Map<String, String>,
    enableJs: Boolean,
    onCreated: (WebView) -> Unit,
    onReleased: () -> Unit,
    onProgress: (Int) -> Unit,
    onPageStarted: (String) -> Unit,
    onHistoryChanged: (canGoBack: Boolean) -> Unit,
    onMainFrameError: (String) -> Unit,
    onExternalLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by rememberUpdatedState(onProgress)
    val started by rememberUpdatedState(onPageStarted)
    val history by rememberUpdatedState(onHistoryChanged)
    val mainError by rememberUpdatedState(onMainFrameError)
    val external by rememberUpdatedState(onExternalLink)
    val released by rememberUpdatedState(onReleased)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = enableJs
                settings.domStorageEnabled = true
                // 第三方页面不该摸到本机文件和其它应用的 content://（allowContentAccess 默认是开的）
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // 电脑版的页面按整页宽度缩进屏幕，能双指放大；手机版的页面不受影响
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                if (!ua.isNullOrBlank()) settings.userAgentString = ua
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        progress(newProgress)
                    }
                }
                webViewClient = object : WebViewClient() {
                    private val guard = WebViewNetworkGuard()

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        guard.intercept(request)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (guard.blocksNavigation(request)) return true
                        return when (request.url.scheme?.lowercase()) {
                            "http", "https" -> false
                            // 不出网、留在页面里的那几种照常
                            "about", "data", "blob", "javascript" -> false
                            else -> {
                                // 拉起别的应用：只认人点出来的那一下，页面脚本自己跳的不理（广告页常这么干）
                                if (request.hasGesture()) external(request.url.toString())
                                true
                            }
                        }
                    }

                    override fun onPageStarted(view: WebView, startedUrl: String, favicon: Bitmap?) {
                        started(startedUrl)
                    }

                    override fun doUpdateVisitedHistory(view: WebView, historyUrl: String?, isReload: Boolean) {
                        history(view.canGoBack())
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        // 只管主文档：页面里一张图、一个广告脚本加载失败不算「打不开」
                        if (request.isForMainFrame) mainError(error.description?.toString().orEmpty().ifBlank { "网页打不开" })
                    }
                }
                setDownloadListener { downloadUrl, _, _, _, _ -> external(downloadUrl) }
                onCreated(this)
                loadUrl(url, headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
            }
        },
        onRelease = {
            released()
            it.stopLoading()
            it.destroy()
        },
    )
}
