package com.radium.inkwell.ui.bookshelf

/**
 * 书架展示方式。默认网格（封面优先）；列表一行一书，方便扫最新章节与作者。
 *
 * 存在 [com.radium.inkwell.data.prefs.AppPrefs]，会进 WebDAV 备份。
 */
enum class BookshelfLayout(val label: String) {
    GRID("网格"),
    LIST("列表"),
}
