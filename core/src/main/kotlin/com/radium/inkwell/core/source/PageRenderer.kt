package com.radium.inkwell.core.source

/**
 * 执行页面 JS 后再取 HTML 的抓取器。
 *
 * 越来越多站点把正文塞进 JS 渲染（如创世中文网的 `<div id="article">` 静态抓下来是空的），
 * 纯 HTTP + Jsoup 无论规则怎么写都拿不到。core 是纯 JVM 模块，实现由 Android 侧（WebView）注入。
 *
 * 代价不小：一次渲染是秒级，且实现通常需要串行化。引擎只在「静态抓取成功但规则解析为空」时
 * 才回退到这里，且不用于搜索/发现（会向大量书源并发扇出）。
 */
interface PageRenderer {

    /**
     * @param userAgent 为空时由实现决定（通常沿用其内置 UA）
     * @return 渲染完成后的页面；失败（超时/加载错误）返回 null，由调用方沿用静态结果
     */
    suspend fun render(
        url: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
    ): FetchedPage?
}
