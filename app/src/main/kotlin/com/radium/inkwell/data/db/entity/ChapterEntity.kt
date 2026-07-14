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
    /**
     * 书源脚本在目录阶段存下的变量（JSON）。Legado 里目录规则用 @put/java.put 存参数，
     * 正文规则再用 @get 取 —— 不落库这条链路就是断的。
     */
    val variable: String = "",
)
