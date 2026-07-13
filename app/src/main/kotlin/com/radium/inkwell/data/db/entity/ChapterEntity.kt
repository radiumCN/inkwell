package com.radium.inkwell.data.db.entity

import androidx.room.Entity

@Entity(tableName = "chapter", primaryKeys = ["bookId", "index"])
data class ChapterEntity(
    val bookId: String,
    val index: Int,
    val title: String,
    /** 网络章节 URL；本地书为 null */
    val url: String? = null,
    /** 本地书：章节在缓存文本中的字节偏移 */
    val start: Long = -1,
    val end: Long = -1,
    /** 网络书正文是否已缓存到磁盘 */
    val isCached: Boolean = false,
)
