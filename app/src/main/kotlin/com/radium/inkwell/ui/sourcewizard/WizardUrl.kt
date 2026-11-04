package com.radium.inkwell.ui.sourcewizard

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 把用户粘进来的东西整理成一个网址；整理不出来返回 null。
 *
 * 从浏览器「分享」出来的常是一段文案：「《某某书》最新章节 https://…」，从里面抠出第一个网址；
 * 只粘了 `www.example.com/book/1/` 没带协议的，补 https。主机名里没有点的（「abc」「localhost」）
 * 不是小说站，当作没填对。
 */
internal fun normalizeBookUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val found = URL_IN_TEXT.find(text)?.value ?: text.takeIf { it.none(Char::isWhitespace) } ?: return null
    val withScheme = if (SCHEME.containsMatchIn(found)) found else "https://$found"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if ('.' !in url.host) return null
    return url.toString()
}

private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)

/** 网址到空白或中文标点为止：分享文案里网址后面常紧跟着「，」「」」 */
private val URL_IN_TEXT = Regex("https?://[^\\s，。、；！？《》「」『』（）“”\"'<>]+", RegexOption.IGNORE_CASE)
