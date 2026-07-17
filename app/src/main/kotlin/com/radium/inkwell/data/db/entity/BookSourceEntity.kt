package com.radium.inkwell.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book_source")
data class BookSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /** 完整书源规则 JSON 原文（Legado 原生格式）；name/enabled 为列表 UI 冗余列 */
    val json: String,
    val updatedAt: Long,

    /** 书源分组（legado 的 bookSourceGroup）；可能是逗号分隔的多个组 */
    val groupName: String = "",

    // ---- 校验结果。存库而不是只放内存：几百个源校验一轮好几分钟，
    // 退出页面就丢光的话，用户根本没法拿结果做后续处理（筛选、排序、批量禁用）。
    /** 0=未校验 1=可用 2=失效 */
    val checkStatus: Int = CheckStatus.UNCHECKED,
    /** 校验详情（可用 · N 章 / 失败原因） */
    val checkMessage: String = "",
    /** 全链路响应耗时(ms)；-1 = 未校验或失败 */
    val respondTime: Long = -1,
    val checkedAt: Long = 0,
    /** 软删除墓碑，语义见 [BookEntity.deleted] */
    val deleted: Boolean = false,
)

object CheckStatus {
    const val UNCHECKED = 0
    const val OK = 1
    const val FAILED = 2
}
