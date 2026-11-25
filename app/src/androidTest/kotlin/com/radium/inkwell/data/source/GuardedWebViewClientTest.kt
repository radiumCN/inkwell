package com.radium.inkwell.data.source

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardedWebViewClientTest {
    @Test fun rendererExitAllowsFreshWebViewToLoad() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rendererGone = CountDownLatch(1)
        var current: WebView? = null
        var detachedOnExit = false

        fun loadLocalPage() {
            val loaded = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val container = FrameLayout(instrumentation.targetContext)
                current = WebView(instrumentation.targetContext).apply {
                    webViewClient = object : GuardedWebViewClient({ failed ->
                        detachedOnExit = failed.parent == null
                        if (failed === current) current = null
                        failed.destroy()
                        rendererGone.countDown()
                    }) {
                        override fun onPageFinished(view: WebView, url: String) {
                            loaded.countDown()
                        }
                    }
                    container.addView(this)
                    loadData("<p>Inkwell</p>", "text/html", "utf-8")
                }
            }
            assertTrue("本地测试页加载超时", loaded.await(15, TimeUnit.SECONDS))
        }

        try {
            loadLocalPage()
            var terminating = false
            instrumentation.runOnMainSync { terminating = current?.webViewRenderProcess?.terminate() == true }
            assertTrue("测试 WebView 的渲染进程没有终止", terminating)
            assertTrue("未收到进程退出回调", rendererGone.await(15, TimeUnit.SECONDS))
            assertTrue("失效的 WebView 仍挂在父视图上", detachedOnExit)
            loadLocalPage()
        } finally {
            instrumentation.runOnMainSync {
                current?.let { view ->
                    (view.parent as? FrameLayout)?.removeView(view)
                    view.destroy()
                }
            }
        }
    }
}
