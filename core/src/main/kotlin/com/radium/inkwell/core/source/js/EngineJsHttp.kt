package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import kotlinx.coroutines.runBlocking

/**
 * 书源脚本的 HTTP 出口。
 *
 * 脚本是同步的（`var html = java.ajax(url)` 立刻要结果），所以这里只能阻塞等待。
 * 调用发生在引擎的抓取协程里，底层 OkHttp 又跑在 IO 线程池上，阻塞的是当前工作线程而非事件循环。
 */
class EngineJsHttp(private val http: SourceHttpClient) : JsHttp {

    override fun fetch(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): String? = runCatching {
        runBlocking { http.fetch(url, method = method, body = body, headers = headers).bodyText }
    }.getOrNull()

    override fun cookieOf(url: String): String = http.cookieOf(url)

    override fun setCookie(url: String, cookie: String) = http.setCookie(url, cookie)

    override fun removeCookie(url: String) = http.removeCookie(url)
}
