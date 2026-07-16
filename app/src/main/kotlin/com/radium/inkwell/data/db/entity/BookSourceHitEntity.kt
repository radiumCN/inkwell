package com.radium.inkwell.data.db.entity

import androidx.room.Entity

/**
 * 「**这本书**在这个源里搜到过没有」的记忆。
 *
 * book_source 上的 checkStatus/respondTime 是**全局健康度**，回答的是"这个源活着吗、快吗"。
 * 但换源真正要问的是"这个源有没有**这本书**" —— 一个又快又健康的源照样可能根本没有这本书，
 * 全局健康度对此一无所知。这张表补的就是这个按书维度的信号。
 *
 * 只记「搜索**跑通了**」的结论：[hit] 为真=搜到了，为假=确实没有。
 * 超时/报错**不写行** —— 那什么都没证明，记下来的话一次网络抖动就能把好源永久打入冷宫。
 * 没有行 = 没搜过，排序时居中。
 */
@Entity(tableName = "book_source_hit", primaryKeys = ["bookId", "sourceId"])
data class BookSourceHitEntity(
    val bookId: String,
    val sourceId: String,
    /** true=这个源搜得到这本书；false=搜索跑通了但没这本书 */
    val hit: Boolean,
    /**
     * 命中时这本书在该源的地址。
     *
     * 现在还没用上 —— 留着是因为它**只在搜索那一刻拿得到**：不趁现在记，
     * 将来想做"命中过的源跳过搜索、直接换"就得让所有人先把每本书重搜一遍才有数据。
     */
    val bookUrl: String? = null,
    val checkedAt: Long,
)
