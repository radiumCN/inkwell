package com.radium.inkwell.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

object BookType {
    const val LOCAL_TXT = 0
    const val LOCAL_EPUB = 1
    const val LOCAL_MOBI = 2
    const val NET = 3
}

@Entity(tableName = "book")
data class BookEntity(
    @PrimaryKey val id: String,
    val type: Int,
    val title: String,
    val author: String = "",
    val coverPath: String? = null,
    val intro: String? = null,
    /** 本地书：应用私有目录内的副本路径 */
    val localPath: String? = null,
    /** 网络书：当前书源与地址（换源时更新） */
    val sourceId: String? = null,
    val bookUrl: String? = null,
    val tocUrl: String? = null,
    val latestChapterTitle: String? = null,
    val totalChapters: Int = 0,
    /** 阅读进度：真身为 (章节索引, 章内字符偏移) */
    val readChapterIndex: Int = 0,
    val readCharOffset: Int = 0,
    /** 进度更新时间戳，WebDAV 同步冲突裁决用 */
    val readAt: Long = 0,
    val addedAt: Long,
    val updatedAt: Long,
    /** 书源脚本在详情阶段存下的变量（JSON）；见 ChapterEntity.variable */
    val variable: String = "",
    /** 书架分组；空 = 未分组 */
    val groupName: String = "",
    /**
     * 从书架隐藏。**不是删除** —— 书、进度、缓存都还在，只是列表里不显示。
     * 想看回来：书架顶栏「⋮ → 显示隐藏的书」。
     */
    val hidden: Boolean = false,
    /**
     * 上次刷新目录时**新增了几章**，且你还没打开过这本书。书架上的红点读的就是它。
     *
     * 不能用「totalChapters - readChapterIndex」当红点 —— 那是"还有多少没读完"，
     * 对几乎每本书都成立，红点会永远亮着，等于没有红点。红点要回答的是
     * 「**自从我上次看过之后，它更新了吗**」，这是两件事。
     *
     * 打开这本书就清零（打开 = 已知晓），刷新时累加。
     */
    val newChapterCount: Int = 0,
    /**
     * 软删除墓碑。删除不真的删行，只打标记 —— 否则「删过」这件事在多设备同步里
     * 无从表达：本地行没了，远端还在，合并就把它当成「别的设备新加的」补回来。
     *
     * 打标记时**必须同时更新 updatedAt** —— 合并靠它做 LWW 裁决。
     * 所有面向用户的查询都要过滤掉 deleted = 1。
     */
    val deleted: Boolean = false,
)
