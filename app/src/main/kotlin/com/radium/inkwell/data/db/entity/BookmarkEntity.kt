package com.radium.inkwell.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书签。
 *
 * 位置存成 (章节序号, 章内字符偏移) —— 与阅读进度同一套坐标。**不能存页码**：
 * 页是排版的产物，用户改个字号、换个字体，页码就全变了，书签会指到别处去。
 */
@Entity(tableName = "bookmark")
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val chapterTitle: String,
    /** 书签处的正文摘录，列表上给用户认位置用 */
    val excerpt: String,
    val createdAt: Long,
)
