package com.radium.inkwell.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 净化替换规则（Legado 的「替换净化」）：把正文里的广告、水印、防盗段落替换掉。
 *
 * [scope] 为空表示对所有书源生效；否则是逗号分隔的书源标识或书源名片段，
 * 只要命中其一就应用。这样既能写一条通用的「去掉『请记住本站域名』」，
 * 也能只针对某个脏源做定向清理。
 */
@Entity(tableName = "replace_rule")
data class ReplaceRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 要匹配的内容；isRegex 为真时是正则，否则是纯文本 */
    val pattern: String,
    /** 替换成什么；空串即删除 */
    val replacement: String = "",
    val isRegex: Boolean = true,
    val scope: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
)
