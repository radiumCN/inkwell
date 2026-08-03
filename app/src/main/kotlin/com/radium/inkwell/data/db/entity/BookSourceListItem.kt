package com.radium.inkwell.data.db.entity

/**
 * 书源列表投影：**不含** [BookSourceEntity.json]。
 *
 * Android CursorWindow 大约 2MB；`SELECT *` 把几百个源的完整规则一起塞进窗口时，
 * 会在某一行直接炸（Couldn't read row N, col 0）。列表 UI / 筛选 / 排序只需元数据，
 * 规则正文按 id 单条取。
 */
data class BookSourceListItem(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
    val groupName: String = "",
    val checkStatus: Int = CheckStatus.UNCHECKED,
    val checkMessage: String = "",
    val respondTime: Long = -1,
    val checkedAt: Long = 0,
    val deleted: Boolean = false,
)
