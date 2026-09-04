package com.radium.inkwell.data.net

/**
 * 开发者提供的 WebDAV 同步服务。
 *
 * 和 [InkwellServer] 同一写法：域名只写这一处。账号 API 与 DAV 是两个主机名
 * （网页反代 Next，DAV 必须直打 Gin），漏改一边会出现「能登录、同步 404」。
 *
 * DAV 根必须带尾斜杠，见 [com.radium.inkwell.core.webdav.WebDavClient] 的集合根注释。
 */
object OfficialWebDav {
    const val API = "https://webdav-api.skylark.run"
    const val SITE = "https://webdav.skylark.run"
    const val DAV = "https://webdav-api.skylark.run/dav/"

    /** 写入服务端应用码备注；换机带 replace 时按这个名字作废旧码 */
    const val APP_PASSWORD_NAME = "Inkwell Android"
}
