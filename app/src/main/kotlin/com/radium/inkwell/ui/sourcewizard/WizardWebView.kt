package com.radium.inkwell.ui.sourcewizard

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.detect.FormField
import com.radium.inkwell.data.source.WebViewNetworkGuard
import org.json.JSONObject

/** 网页里提交的一次表单（[WebMode.Capture] 下报上来） */
data class CapturedForm(val action: String, val method: String, val charset: String?, val fields: List<FormField>)

enum class WebMode {
    /** 让用户照常用网站，记下每次表单提交（找搜索请求） */
    Capture,

    /** 点链接不跳转，报回点中的链接（指出目录里的一章） */
    PickLink,

    /** 点哪都不跳转，报回点中元素的文字（指出正文） */
    PickText,
}

/**
 * 向导里的网页：用户在这里搜一次（P2），或者点一下告诉向导「目录 / 正文在这」（P3）。
 *
 * 和阅读器的渲染回退（WebViewPageRenderer）是两回事 —— 那个是后台无界面地跑完脚本取 HTML，
 * 这个是给人看、给人点的。但有两条与它一致：
 * - **UA 一致**：用抓取客户端的 [SourceHttpClient.DEFAULT_UA]。站点按 UA 给不同的页面，用户在这里看到的
 *   和引擎抓到的必须是同一套，否则用户点中的链接在静态页里根本找不到。
 * - **内网拦截**：页面里的脚本同样不许碰内网，与它共用 [WebViewNetworkGuard]。
 *
 * 脚本桥对页面上的**所有** frame 开放（广告 iframe 也调得到）。所以桥只上报数据、不做任何事；
 * 报上来的东西由 ViewModel 校验（表单必须是这个站的、点选结果要在静态页上重认一遍）。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WizardWebView(
    url: String,
    mode: WebMode,
    highlight: Color,
    onPageUrl: (String) -> Unit,
    onFormSubmit: (CapturedForm) -> Unit,
    onPick: (href: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageUrl by rememberUpdatedState(onPageUrl)
    val submit by rememberUpdatedState(onFormSubmit)
    val pick by rememberUpdatedState(onPick)
    val script = when (mode) {
        WebMode.Capture -> CAPTURE_JS
        WebMode.PickLink -> pickJs("link", highlight)
        WebMode.PickText -> pickJs("text", highlight)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 第三方页面不该摸到本机文件和其它应用的 content://（allowContentAccess 默认是开的）
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.userAgentString = SourceHttpClient.DEFAULT_UA
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        // 桥在 WebView 自己的线程上被调，回主线程再交出去
                        @JavascriptInterface
                        fun onSubmit(json: String) {
                            parseForm(json)?.let { form -> main.post { submit(form) } }
                        }

                        @JavascriptInterface
                        fun onPick(json: String) {
                            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
                            main.post { pick(o.optString("href"), o.optString("text")) }
                        }
                    },
                    BRIDGE,
                )
                webViewClient = object : WebViewClient() {
                    private val guard = WebViewNetworkGuard()

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        guard.intercept(request)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        guard.blocksNavigation(request)

                    override fun onPageStarted(view: WebView, startedUrl: String, favicon: Bitmap?) {
                        pageUrl(startedUrl)
                    }

                    /**
                     * 页面第一次画出来就装上脚本：用户只点得到看得见的东西。从前等 [onPageFinished] ——
                     * 广告多的页要等所有子资源加载完，这期间点目录里的一章会真的跳走、提交的 POST 搜索也没记下。
                     * 每页都要重装（脚本随页面一起没了）；两处都装，靠脚本里的标记只装一次。
                     */
                    override fun onPageCommitVisible(view: WebView, visibleUrl: String) {
                        view.evaluateJavascript(script, null)
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        pageUrl(finishedUrl)
                        view.evaluateJavascript(script, null)
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun parseForm(json: String): CapturedForm? = runCatching {
    val o = JSONObject(json)
    val arr = o.getJSONArray("fields")
    val fields = (0 until arr.length()).map { i ->
        val f = arr.getJSONObject(i)
        FormField(f.optString("name"), f.optString("value"), f.optString("type"))
    }
    CapturedForm(
        action = o.optString("action"),
        method = o.optString("method").ifBlank { "get" },
        charset = o.optString("charset").takeIf { it.isNotBlank() },
        fields = fields,
    )
}.getOrNull()

private const val BRIDGE = "InkwellBridge"

/**
 * 记下表单提交。两条路都要截：用户点按钮 / 回车走 `submit` 事件；站点脚本自己调 `form.submit()`
 * 不触发这个事件，所以把原型上的 submit 也包一层。重复注入（同一页装了两次）靠标记挡掉。
 *
 * 表单的 action / method / 编码 / 字段列表都从 `HTMLFormElement.prototype` 上的原生 getter 读，不读 `f.action`：
 * 表单里有个 `<input name="action">` 时，`f.action` 拿到的是那个输入框（DOM clobbering），捕获被悄悄丢掉；
 * 有 `name="method"` 的字段，POST 搜索就成了 GET。原生 getter 还顺手把 action 解析成了绝对地址。
 */
private val CAPTURE_JS = """
    (function () {
      if (window.__inkwellCapture) return;
      window.__inkwellCapture = true;
      var proto = HTMLFormElement.prototype;
      function prop(f, name) {
        var d = Object.getOwnPropertyDescriptor(proto, name);
        return d && d.get ? d.get.call(f) : undefined;
      }
      function report(f) {
        try {
          var fields = [];
          var els = prop(f, 'elements');
          for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (!el.name || el.disabled) continue;
            var t = (el.type || '').toLowerCase();
            if (t === 'submit' || t === 'button' || t === 'image' || t === 'reset' || t === 'file') continue;
            if ((t === 'checkbox' || t === 'radio') && !el.checked) continue;
            fields.push({ name: el.name, value: el.value || '', type: t });
          }
          $BRIDGE.onSubmit(JSON.stringify({
            action: prop(f, 'action') || location.href,
            method: prop(f, 'method') || 'get',
            charset: prop(f, 'acceptCharset') || document.characterSet || '',
            fields: fields
          }));
        } catch (e) {}
      }
      document.addEventListener('submit', function (e) { report(e.target); }, true);
      var native = proto.submit;
      proto.submit = function () { report(this); return native.apply(this, arguments); };
    })();
""".trimIndent()

/**
 * 点选：在捕获阶段截下点击，不让页面跳转、也不让页面自己的点击处理跑（`location.href = …` 那种），
 * 给点中的元素描一圈边，再把它的链接与文字报上来。描边色由调用方从主题里取。
 */
private fun pickJs(mode: String, highlight: Color): String {
    val css = "#%06X".format(highlight.toArgb() and 0xFFFFFF)
    return """
        (function (mode) {
          if (window.__inkwellPick) return;
          window.__inkwellPick = true;
          var last = null;
          document.addEventListener('click', function (e) {
            var el = e.target;
            if (mode === 'link') {
              while (el && el.tagName !== 'A') el = el.parentElement;
              if (!el) return;
            }
            e.preventDefault();
            e.stopPropagation();
            if (last) last.style.outline = '';
            el.style.outline = '3px solid $css';
            last = el;
            var text = (el.innerText || el.textContent || '').trim().slice(0, 200);
            $BRIDGE.onPick(JSON.stringify({ href: el.href || '', text: text }));
          }, true);
        })('$mode');
    """.trimIndent()
}
