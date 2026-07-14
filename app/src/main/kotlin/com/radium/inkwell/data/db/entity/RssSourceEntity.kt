package com.radium.inkwell.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rss_source")
data class RssSourceEntity(
    /** 源地址 */
    @PrimaryKey val id: String,
    val name: String,
    val icon: String = "",
    val groupName: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /** 完整规则 JSON */
    val json: String,
    /** legado 原文，留着转换器升级后重转 */
    val sourceJson: String = "",
    val converterVersion: Int = 0,
    val updatedAt: Long = 0,
)
