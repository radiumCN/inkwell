package com.radium.inkwell.data.db.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

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
    /**
     * 封面。同一列对两类书是两种东西：本地书存**本机绝对路径**（`filesDir/covers/xx.img`），
     * 网络书存**远程 URL** —— Coil 两种都吃，所以 UI 侧不用分。
     *
     * 但凡跨设备就必须分：WebDAV 只带得走后者，前者换台机器就是条死路径
     * （封面文件本身不上传）。见 `BackupBook.coverUrl`。
     */
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
     * 想看回来：长按书架顶栏的「书架」标题展开隐藏区（刻意做成暗号，菜单里平时看不到）。
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
    /**
     * 是否在书架上。阅读器要靠书行+目录才能翻页，所以从预览「直接读」也必须先落库；
     * 但那不是用户点了「加入书架」。false = 试读：书架列表不显示，退出阅读再问要不要留下。
     *
     * 默认 true：老数据、导入、显式加架都是真正在架上的书。只有预览直读会写成 false。
     */
    val inShelf: Boolean = true,
)
