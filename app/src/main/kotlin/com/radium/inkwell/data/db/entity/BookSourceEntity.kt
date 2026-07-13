package com.radium.inkwell.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book_source")
data class BookSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /** 完整书源规则 JSON 原文；name/enabled 为列表 UI 冗余列 */
    val json: String,
    val updatedAt: Long,
)
