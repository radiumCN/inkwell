package com.radium.inkwell.data.db.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 两条覆盖索引，让书源列表与「启用源元数据」两个热查询**完全不碰表本身**。
 *
 * [json] 是每行几 KB 到几十 KB 的大字段，排在中间：一行装不进一页，溢出到链式的溢出页上，
 * 读它后面的列（updatedAt、groupName、校验结果……）就得顺着溢出页链走过去 —— 列表投影虽然没 SELECT json，
 * 磁盘上照样把每个源的 JSON 读一遍。校验时每存一条结果，列表流就整表重查一次，几千个源一跑几十分钟，
 * 就是几千遍「扫一遍全部 JSON」。索引里只有这些小列，按 sortOrder, name 排好，WHERE / ORDER BY 也一并走它。
 *
 * 列顺序要与 [InkwellDb.MIGRATION_18_19] 里建的完全一致：Room 开库时按 PRAGMA 逐项比对，对不上就拒绝开库。
 */
@Entity(
    tableName = "book_source",
    indices = [
        Index(
            value = [
                "deleted", "sortOrder", "name", "id", "enabled", "updatedAt", "groupName",
                "checkStatus", "checkMessage", "respondTime", "checkedAt",
            ],
            name = "idx_book_source_list",
        ),
        Index(
            value = [
                "enabled", "deleted", "sortOrder", "name", "id", "updatedAt",
                "checkStatus", "respondTime", "searchMs", "searchFails",
            ],
            name = "idx_book_source_enabled",
        ),
    ],
)
data class BookSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /**
     * 完整书源规则 JSON 原文（Legado 原生格式）。
     * **列表查询禁止 SELECT 本列** —— 见 [BookSourceListItem] / CursorWindow ~2MB 上限。
     */
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

    // ---- 搜索测速。与上面的校验结果分开存：校验量的是搜索→详情→目录→正文整条链，这里只量搜索这一个请求；
    // 混进 respondTime，「按响应速度」排序就成了两种口径比大小。每次搜索顺手记下，用户不点「校验」也有数 ——
    // 搜索靠它排先后、定超时（见 SearchScheduling）。与校验结果一样是本机专属，不跨设备同步。
    /** 搜索耗时的滑动平均(ms)；-1 = 还没搜成功过 */
    val searchMs: Long = -1,
    /** 连续搜索失败（超时 / 出错）的次数；成功一次清零 */
    val searchFails: Int = 0,
    /** 软删除墓碑，语义见 [BookEntity.deleted] */
    val deleted: Boolean = false,
)

object CheckStatus {
    const val UNCHECKED = 0
    const val OK = 1
    const val FAILED = 2
}
