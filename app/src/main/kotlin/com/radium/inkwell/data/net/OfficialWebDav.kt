package com.radium.inkwell.data.net

/**
 * 开发者提供的 WebDAV 同步服务。
 *
 * 和 [InkwellServer] 同一写法：域名只写这一处。账号 API 仍在独立主机，
 * DAV 根走站点主机（`webdav.skylark.run/dav/`）。漏改一边会出现「能登录、同步 404」。
 *
 * DAV 根必须带尾斜杠，见 [com.radium.inkwell.core.webdav.WebDavClient] 的集合根注释。
 */
object OfficialWebDav {
    const val API = "https://webdav-api.skylark.run"
    const val SITE = "https://webdav.skylark.run"
    const val DAV = "https://webdav.skylark.run/dav/"

    /**
     * 0.3.1 及更早写入的 DAV 根。服务端把 DAV 从 api 主机挪到站点主机后，
     * 本地还躺着这条的用户会同步 404，启动时改写成 [DAV]。
     */
    const val LEGACY_DAV = "https://webdav-api.skylark.run/dav/"

    /** 写入服务端应用码备注；换机带 replace 时按这个名字作废旧码 */
    const val APP_PASSWORD_NAME = "Inkwell Android"

    /**
     * 服务端 / 本地可能仍吐旧主机。空串回落到 [DAV]；旧根改写成新根；其余原样（补尾斜杠）。
     */
    fun resolveDavUrl(fromServer: String): String {
        val raw = fromServer.trim()
        val withSlash = when {
            raw.isEmpty() -> DAV
            raw.endsWith("/") -> raw
            else -> "$raw/"
        }
        return if (isLegacyDav(withSlash)) DAV else withSlash
    }

    fun isLegacyDav(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        return withSlash == LEGACY_DAV
    }
}
