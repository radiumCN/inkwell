package com.radium.inkwell.data.db.entity

/**
 * 启用书源的元数据投影：**不含** [BookSourceEntity.json]，理由同 [BookSourceListItem]。
 *
 * 规则正文走仓库里按 [updatedAt] 记的解码缓存，没命中才按 id 单条取；
 * 校验结果与测速（[checkStatus]、[searchMs] …）改了不动 updatedAt，所以每次现查，不进缓存。
 */
data class EnabledSourceMeta(
    val id: String,
    val updatedAt: Long,
    val checkStatus: Int,
    val respondTime: Long,
    val sortOrder: Int,
    val searchMs: Long,
    val searchFails: Int,
)
