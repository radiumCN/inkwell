package com.radium.inkwell.ui.sourcedetail

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.SourceLoginForm
import com.radium.inkwell.data.source.GuardedWebViewClient
import com.radium.inkwell.data.source.WEB_RENDERER_ERROR
import com.radium.inkwell.core.source.sourceOrigin
import com.radium.inkwell.data.source.sourceLoginHttpUrl
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppFormField
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SpinnerSettingRow
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.blockTouchesBelow
import org.json.JSONTokener
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SourceLoginFields(
    form: SourceLoginForm,
    busy: Boolean,
    onChange: (String, String) -> Unit,
    onSubmit: (String?) -> Unit,
) {
    form.fields.forEach { field ->
        val value = form.values[field.name].orEmpty()
        val command = field.command
        when {
            field.isLabel || (field.isButton && command == null) ->
                Text(field.name, style = MiuixTheme.textStyles.body2)
            field.isButton -> SecondaryButton(
                text = field.name, onClick = { onSubmit(command) },
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
            )
            field.choices.isNotEmpty() -> SpinnerSettingRow(
                title = field.name,
                options = field.choices.map { PickerOption(it, it) },
                selectedId = value,
                onSelect = { onChange(field.name, it.id) },
                // 已经在「来源登录」那张卡里：不再自己套一张卡
                position = SettingGroupPosition.Alone,
                enabled = !busy,
            )
            else -> {
                val password = field.type.equals("password", ignoreCase = true)
                AppFormField(
                    value = value,
                    onValueChange = { onChange(field.name, it) },
                    label = field.name,
                    placeholder = field.placeholder,
                    enabled = !busy,
                    keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                )
            }
        }
    }
    if (form.canSubmit) {
        PrimaryButton(
            text = "确认", onClick = { onSubmit(null) }, enabled = !busy,
            loading = busy, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SourceLoginBrowser(
    page: SourceLoginBrowserPage,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onDone: (url: String, html: String, userAgent: String, status: Int) -> Unit,
) {
    var desktopMode by remember(page) { mutableStateOf(page.desktopMode) }
    var menuOpen by remember(page) { mutableStateOf(false) }
    var webView by remember(page, desktopMode) { mutableStateOf<WebView?>(null) }
    var rendererGone by remember(page, desktopMode) { mutableStateOf(false) }
    var currentUrl by remember(page, desktopMode) { mutableStateOf(page.url) }
    var ready by remember(page, desktopMode) { mutableStateOf(false) }
    var pageError by remember(page, desktopMode) { mutableStateOf<String?>(null) }
    var httpStatus by remember(page, desktopMode) { mutableStateOf<Int?>(null) }
    val host = currentUrl.sourceLoginHttpUrl()?.host.orEmpty()
    fun reload() {
        ready = false
        pageError = null
        httpStatus = null
        webView?.reload()
    }
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }
    AppScaffold(
        blurBars = false,
        topBar = {
            AppToolbar(
                navigationIcon = { BackButton(onBack) },
                actions = {
                    // 栏里只放图标按钮（文字按钮会被装进玻璃胶囊）：「完成」是一枚勾
                    AppIconButton(enabled = ready && pageError == null, onClick = {
                        val view = webView ?: return@AppIconButton
                        val url = view.url ?: return@AppIconButton
                        val origin = url.sourceLoginHttpUrl()?.sourceOrigin()?.toString()
                        val initialOrigin = page.url.sourceLoginHttpUrl()?.sourceOrigin()?.toString()
                        val approved = origin != null && (origin == initialOrigin || origin == page.returnOrigin)
                        val userAgent = view.settings.userAgentString
                        val status = httpStatus ?: 200
                        if (page.requireCookies || !approved) {
                            onDone(url, "", userAgent, status)
                        } else {
                            view.evaluateJavascript("document.documentElement.outerHTML") { result ->
                                if (view === webView && view.url == url && ready && pageError == null) {
                                    val html = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                                    onDone(url, html.orEmpty(), userAgent, status)
                                }
                            }
                        }
                    }) {
                        Icon(MiuixIcons.Basic.Check, contentDescription = "完成")
                    }
                    Box {
                        AppIconButton(onClick = { menuOpen = true }) {
                            Icon(MiuixIcons.More, contentDescription = "网页选项")
                        }
                        AppDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            items = listOf(
                                AppMenuItem("刷新", onClick = ::reload, enabled = webView != null),
                                AppMenuItem(
                                    if (desktopMode) "恢复来源默认网页" else "请求桌面网站",
                                    onClick = { desktopMode = !desktopMode },
                                ),
                            ),
                        )
                    }
                },
                bottomContent = {
                    httpStatus?.takeIf { pageError == null }?.let { status ->
                        Text(
                            "网页返回 HTTP $status",
                            modifier = Modifier.padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapS),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                },
                title = { ToolbarTitle(host.ifEmpty { page.title.ifBlank { "来源登录" } }) },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            // 从登录入口重开，站点才能按新 UA 选路由。Profile 继续使用这个来源自己的会话。
            if (!rendererGone) key(page, desktopMode) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            WebViewCompat.setProfile(this, page.profile)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            // 默认和抓取同一个 UA，供绑定 UA 的会话使用。
                            settings.userAgentString = if (desktopMode) SourceHttpClient.LEGADO_UA else page.userAgent
                            settings.displayZoomControls = false
                            webViewClient = object : GuardedWebViewClient({ failed ->
                                if (webView === failed) {
                                    webView = null
                                    ready = false
                                    httpStatus = null
                                    pageError = null
                                    rendererGone = true
                                }
                            }) {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    super.shouldOverrideUrlLoading(view, request) ||
                                        (request.isForMainFrame && request.url.toString().sourceLoginHttpUrl() == null)

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    val target = url.sourceLoginHttpUrl()
                                    val writerLogin = target?.sourceOrigin()?.toString() == FANQIE_ORIGIN &&
                                        target.encodedPath.trimEnd('/') == FANQIE_WRITER_LOGIN_PATH
                                    // 作家登录页还有桌面最小宽度。缩放整页，让右侧表单落在屏幕内。
                                    val wideViewport = desktopMode || writerLogin
                                    view.settings.useWideViewPort = wideViewport
                                    view.settings.loadWithOverviewMode = wideViewport
                                    view.settings.builtInZoomControls = wideViewport
                                    currentUrl = url
                                    ready = false
                                    pageError = null
                                    httpStatus = null
                                }

                                override fun onPageCommitVisible(view: WebView, url: String) {
                                    currentUrl = url
                                    ready = true
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (url.sourceLoginHttpUrl()?.sourceOrigin()?.toString() == FANQIE_ORIGIN) {
                                        view.evaluateJavascript(FANQIE_LOGIN_LAYOUT, null)
                                    }
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) pageError = "网页打不开，请检查网络后重试"
                                }

                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    // HTTP 错误仍可能带有验证或重试表单。保留原页面和触摸交互。
                                    if (request.isForMainFrame) httpStatus = response.statusCode
                                }
                            }
                            webView = this
                            loadUrl(page.url)
                        }
                    },
                    update = { view ->
                        view.visibility = if (pageError == null) View.VISIBLE else View.INVISIBLE
                    },
                    onRelease = { view ->
                        if (webView === view) webView = null
                        view.destroy()
                    },
                )
            }
            (if (rendererGone) WEB_RENDERER_ERROR else pageError)?.let { error ->
                ErrorState(
                    message = error,
                    modifier = Modifier.background(MiuixTheme.colorScheme.surface).blockTouchesBelow(),
                    onRetry = if (rendererGone) ({ rendererGone = false }) else ::reload,
                )
            }
        }
    }
}

private const val FANQIE_ORIGIN = "https://fanqienovel.com/"
private const val FANQIE_WRITER_LOGIN_PATH = "/main/writer/login"

// 番茄的移动登录弹窗在部分 WebView 中把滑块容器和表单算成零高度，触摸也被裁掉。
// 样式预先挂在文档上，打开弹窗或切换到验证码表单时都能生效。
private const val FANQIE_LOGIN_LAYOUT = """
    (() => {
        if (location.origin + '/' !== '$FANQIE_ORIGIN' || document.getElementById('inkwell-login-layout')) return;
        const style = document.createElement('style');
        style.id = 'inkwell-login-layout';
        const mobileLayout = '.slogin-mobile-login-form .swiper, .slogin-mobile-login-form .slogin-mobile-form-layout { height: 100% !important; }';
        if (location.pathname === '$FANQIE_WRITER_LOGIN_PATH' || location.pathname === '$FANQIE_WRITER_LOGIN_PATH/') {
            // 作家页使用的视口单位在 WebView 里算成 0，改用实测的布局视口高度。
            const syncHeight = () => {
                style.textContent = mobileLayout +
                    ' div:has(> .immersive-content > .writer-login), .immersive-content:has(> .writer-login)' +
                    ' { height: ' + window.innerHeight + 'px !important; }';
            };
            syncHeight();
            window.addEventListener('resize', syncHeight);
        } else {
            style.textContent = mobileLayout;
        }
        document.head.appendChild(style);
    })();
"""
