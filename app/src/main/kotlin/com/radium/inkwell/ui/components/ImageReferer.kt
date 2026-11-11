package com.radium.inkwell.ui.components

import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

/**
 * 图片防盗链的兜底：**先不带 Referer 取，失败了再带上文章所在网站取一次**。
 *
 * 有的网站的图床只认自己站点来的请求（旅行漫记 synyan.cn：不带 Referer 一律 403，带 `https://synyan.cn/` 才给图），
 * 浏览器里看得见是因为浏览器会带上网页的来源。我们取图从前什么都不带，这类图一张都出不来。
 *
 * 为什么不一上来就带：另一类图床正好反过来 —— 微信公众号的图（mmbiz.qpic.cn，国内订阅里极常见）不带 Referer 好好的，
 * 带了别的网站就换成一张「此图片来自微信公众平台」的占位图，而且是**正常返回**的，不会走到失败分支、没有机会重试。
 * 先不带，现在出得来的图一张都不受影响；只有本来就出不来的，才多一次带 Referer 的请求。
 */
internal fun ImageRequest.Builder.referer(value: String?): ImageRequest.Builder =
    if (value == null) this else httpHeaders(NetworkHeaders.Builder().set("Referer", value).build())

/**
 * 带 Referer 时用的值：文章网址的**来源**（`https://synyan.cn/`），不是完整网址 —— 浏览器跨站取图默认就只带来源
 * （strict-origin-when-cross-origin），图床认的也是它。不是 http(s) 网址（脚本地址、data:）返回 null，不重试。
 */
internal fun siteOrigin(pageUrl: String?): String? {
    val uri = runCatching { java.net.URI(pageUrl?.trim().orEmpty()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "$scheme://$host$port/"
}
