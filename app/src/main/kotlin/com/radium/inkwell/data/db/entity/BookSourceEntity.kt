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
    /**
     * 该书源的 legado 原文。书源在导入时就被转换成我们的规则格式存库了，
     * 升级 App 不会重转 —— 留着原文才能在转换器修好后重新转换。
     * 空串 = 手写的书源（非 legado 导入），或旧版本导入的（没法重转，只能让用户重新导入）。
     */
    val sourceJson: String = "",
    /** 转换时的 [com.radium.inkwell.core.source.legado.LegadoConverter.VERSION] */
    val converterVersion: Int = 0,
)
