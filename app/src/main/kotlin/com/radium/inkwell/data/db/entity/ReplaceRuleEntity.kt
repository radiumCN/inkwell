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
    /**
     * 只对这一本书生效（阅读页长按选字建的规则）。空 = 通用规则。
     *
     * 与 [scope] 是两个维度：scope 限的是「哪些书源」，bookId 限的是「哪一本书」。
     * 书内规则在**渲染时**应用，而不是抓取时 —— 抓取时应用的话，用户刚建的规则对
     * 已经缓存的章节毫无反应，而他恰恰是对着眼前这一页建的。
     */
    val bookId: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
)
