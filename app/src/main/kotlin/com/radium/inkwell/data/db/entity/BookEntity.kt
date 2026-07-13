package com.radium.inkwell.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

object BookType {
    const val LOCAL_TXT = 0
    const val LOCAL_EPUB = 1
    const val LOCAL_MOBI = 2
    const val NET = 3
}

@Entity(tableName = "book")
data class BookEntity(
    @PrimaryKey val id: String,
    val type: Int,
    val title: String,
    val author: String = "",
    val coverPath: String? = null,
    val intro: String? = null,
    /** 本地书：应用私有目录内的副本路径 */
    val localPath: String? = null,
    /** 网络书：当前书源与地址（换源时更新） */
    val sourceId: String? = null,
    val bookUrl: String? = null,
    val tocUrl: String? = null,
    val latestChapterTitle: String? = null,
    val totalChapters: Int = 0,
    /** 阅读进度：真身为 (章节索引, 章内字符偏移) */
    val readChapterIndex: Int = 0,
    val readCharOffset: Int = 0,
    /** 进度更新时间戳，WebDAV 同步冲突裁决用 */
    val readAt: Long = 0,
    val addedAt: Long,
    val updatedAt: Long,
)
