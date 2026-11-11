package com.radium.inkwell.core.util

import java.net.URLDecoder

/**
 * 阅读（Legado）的一键导入链接：源仓库、阅读里「分享」出来的都是这种，
 * `legado://import/rssSource?src=<真正的地址>`、`legado://import/replaceRule?src=…`，
 * 更早的版本是 `yuedu://rsssource/importonline?src=…`、`yuedu://replace/importonline?src=…`。
 *
 * 我们不注册这两个 scheme（不跟阅读抢链接），但用户会把它们复制下来粘进导入框：拆出 `src` 去下载就行。
 */
object LegadoLinks {

    private val SCHEMES = setOf("legado", "yuedu")

    /** 是不是阅读的一键导入链接（不管拆不拆得出地址） */
    fun isAppLink(text: String): Boolean =
        text.trim().substringBefore("://", "").lowercase() in SCHEMES

    /** 阅读的一键导入链接换成里面真正的地址；普通网址原样返回（去掉首尾空白）；拆不出 `src` 的也原样返回 */
    fun unwrap(url: String): String {
        val trimmed = url.trim()
        if (!isAppLink(trimmed)) return trimmed
        val src = trimmed.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("src=") }
            ?.removePrefix("src=")
            ?: return trimmed
        return runCatching { URLDecoder.decode(src, Charsets.UTF_8) }.getOrDefault(src).trim()
    }
}
