package com.radium.inkwell.ui.sourcedetail

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.radium.inkwell.core.source.SourceLoginForm
import com.radium.inkwell.data.source.WebViewNetworkGuard
import com.radium.inkwell.core.source.sourceOrigin
import com.radium.inkwell.data.source.sourceLoginHttpUrl
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppFormField
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SpinnerSettingRow
import com.radium.inkwell.ui.components.ToolbarTitle
import org.json.JSONTokener
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
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
    onDone: (url: String, html: String) -> Unit,
) {
    var webView by remember(page) { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(page) { mutableStateOf(page.url) }
    var ready by remember(page) { mutableStateOf(false) }
    val host = currentUrl.sourceLoginHttpUrl()?.host.orEmpty()
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
                    AppIconButton(enabled = ready, onClick = {
                        val view = webView ?: return@AppIconButton
                        val url = view.url ?: return@AppIconButton
                        val origin = url.sourceLoginHttpUrl()?.sourceOrigin()?.toString()
                        val initialOrigin = page.url.sourceLoginHttpUrl()?.sourceOrigin()?.toString()
                        val approved = origin != null && (origin == initialOrigin || origin == page.returnOrigin)
                        if (page.requireCookies || !approved) {
                            onDone(url, "")
                        } else {
                            view.evaluateJavascript("document.documentElement.outerHTML") { result ->
                                if (view.url == url) {
                                    val html = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                                    onDone(url, html.orEmpty())
                                }
                            }
                        }
                    }) {
                        Icon(MiuixIcons.Basic.Check, contentDescription = "完成")
                    }
                },
                title = { ToolbarTitle(host.ifEmpty { page.title.ifBlank { "来源登录" } }) },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                WebView(context).apply {
                    WebViewCompat.setProfile(this, page.profile)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // 和抓取同一个 UA：阅读导入的来源发桌面 UA，这里也得是它，登录拿到的会话抓取时才认
                    settings.userAgentString = page.userAgent
                    webViewClient = object : WebViewClient() {
                        private val guard = WebViewNetworkGuard()

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            guard.intercept(request)

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            guard.blocksNavigation(request) ||
                                (request.isForMainFrame && request.url.toString().sourceLoginHttpUrl() == null)

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            currentUrl = url
                            ready = false
                        }

                        override fun onPageCommitVisible(view: WebView, url: String) {
                            currentUrl = url
                            ready = true
                        }
                    }
                    webView = this
                    loadUrl(page.url)
                }
            },
            onRelease = { view ->
                webView = null
                view.destroy()
            },
        )
    }
}
