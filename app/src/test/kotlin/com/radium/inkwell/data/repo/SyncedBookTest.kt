package com.radium.inkwell.data.repo

import com.radium.inkwell.core.webdav.BackupBook
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 合并结果落到本机最新书行上的裁决（WebDavRepository.applyBooks 的纯逻辑部分）。
 *
 * 算错一次的代价：别的设备加上架的书在这台永远看不见，或换源后进度与目录错位、落后好几章。
 */
class SyncedBookTest {

    @Test
    fun `移出书架同步保留文件与阅读记录且不当成远端删除`() {
        val existing = local(readChapter = 12).copy(localPath = "/books/kept.txt")
        val synced = applySyncedBook(existing, remote(updatedAt = 200).copy(removedFromShelf = true))
        assertFalse(synced.entity.inShelf)
        assertTrue(synced.entity.removedFromShelf)
        assertFalse(synced.deletedRemotely)
        assertEquals(existing.localPath, synced.entity.localPath)
        assertEquals(12, synced.entity.readChapterIndex)
    }

    @Test
    fun `主动移出不属于试读且不能被旧备份重新上架`() {
        val existing = local(updatedAt = 200, readChapter = 12, inShelf = false).copy(removedFromShelf = true)
        val synced = applySyncedBook(existing, remote(updatedAt = 100))
        assertFalse(synced.entity.inShelf)
        assertTrue(synced.entity.removedFromShelf)
        assertEquals(12, synced.entity.readChapterIndex)
    }

    @Test
    fun `另一台重新加入后可以同步恢复到书架`() {
        val existing = local(updatedAt = 100, inShelf = false).copy(removedFromShelf = true)
        val synced = applySyncedBook(existing, remote(updatedAt = 200))
        assertTrue(synced.entity.inShelf)
        assertFalse(synced.entity.removedFromShelf)
    }

    private fun local(
        source: String = "s",
        readChapter: Int = 0,
        readAt: Long = 100,
        updatedAt: Long = 100,
        totalChapters: Int = 50,
        inShelf: Boolean = true,
        deleted: Boolean = false,
        groupName: String = "",
    ) = BookEntity(
        id = "b", type = BookType.NET, title = "网书",
        sourceId = source, bookUrl = "https://$source/book", tocUrl = "https://$source/toc",
        totalChapters = totalChapters,
        readChapterIndex = readChapter, readAt = readAt,
        addedAt = 1, updatedAt = updatedAt,
        groupName = groupName, inShelf = inShelf, deleted = deleted,
    )

    private fun remote(
        source: String = "s",
        readChapter: Int = 0,
        readAt: Long = 100,
        updatedAt: Long = 100,
        totalChapters: Int = 50,
        deleted: Boolean = false,
        groupName: String = "",
    ) = BackupBook(
        id = "b", type = BookType.NET, title = "网书",
        sourceId = source, bookUrl = "https://$source/book", tocUrl = "https://$source/toc",
        totalChapters = totalChapters,
        readChapterIndex = readChapter, readAt = readAt, updatedAt = updatedAt,
        groupName = groupName, deleted = deleted,
    )

    // ---------- 试读行 vs 远端在架 ----------

    /**
     * B 在 A 把书加上架**之后**点开预览看了一眼：本机试读行的 updatedAt 更新，按 LWW 永远赢，
     * 这本书在 B 上就一直看不见。试读不算表态，在架状态听远端的。
     */
    @Test
    fun `本机只是试读 远端在架 —— 不论时间戳都上架`() {
        val existing = local(inShelf = false, updatedAt = 200)
        val synced = applySyncedBook(existing, remote(updatedAt = 100, groupName = "追更"))
        assertTrue(synced.entity.inShelf)
        assertFalse(synced.entity.deleted)
        assertEquals("追更", synced.entity.groupName)
        assertEquals(100, synced.entity.updatedAt)
        assertFalse(synced.sourceChanged)
    }

    /** 远端是墓碑时试读行不动：正在读的书别被同步删掉，退出阅读时用户自己决定 */
    @Test
    fun `本机试读 远端是旧墓碑 —— 保持试读`() {
        val existing = local(inShelf = false, updatedAt = 200)
        val synced = applySyncedBook(existing, remote(updatedAt = 100, deleted = true))
        assertEquals(existing, synced.entity)
        assertFalse(synced.deletedRemotely)
    }

    // ---------- 换源：书源身份跟着进度走 ----------

    /** A 换源后 B 同步：新源 + A 对齐过的章号一起来，旧源的目录要清掉 */
    @Test
    fun `远端换了源 —— 书源与进度一起采纳并清目录`() {
        val existing = local(source = "old", readChapter = 100, readAt = 100, updatedAt = 100, totalChapters = 500)
        val synced = applySyncedBook(
            existing,
            remote(source = "new", readChapter = 103, readAt = 200, updatedAt = 200, totalChapters = 503),
        )
        assertTrue(synced.sourceChanged)
        assertEquals("new", synced.entity.sourceId)
        assertEquals("https://new/toc", synced.entity.tocUrl)
        assertEquals(103, synced.entity.readChapterIndex)
        assertEquals(503, synced.entity.totalChapters)
    }

    /** 老版本换源不盖 readAt：打平时随元数据，同样不能留下「新源 + 自己的旧章号」 */
    @Test
    fun `远端换了源 readAt 打平 —— 跟元数据走 不拼旧章号`() {
        val existing = local(source = "old", readChapter = 100, readAt = 100, updatedAt = 100)
        val synced = applySyncedBook(
            existing,
            remote(source = "new", readChapter = 103, readAt = 100, updatedAt = 200),
        )
        assertTrue(synced.sourceChanged)
        assertEquals("new", synced.entity.sourceId)
        assertEquals(103, synced.entity.readChapterIndex)
    }

    /** 本机在旧源上读得更新：书源与进度留本机的，只采纳远端的分组等元数据 */
    @Test
    fun `本机在旧源上读得更新 —— 留在旧源 元数据照收`() {
        val existing = local(source = "old", readChapter = 150, readAt = 300, updatedAt = 100, totalChapters = 500)
        val synced = applySyncedBook(
            existing,
            remote(source = "new", readChapter = 103, readAt = 200, updatedAt = 200, totalChapters = 503, groupName = "追更"),
        )
        assertFalse(synced.sourceChanged)
        assertEquals("old", synced.entity.sourceId)
        assertEquals("https://old/toc", synced.entity.tocUrl)
        assertEquals(150, synced.entity.readChapterIndex)
        assertEquals(500, synced.entity.totalChapters, "另一个源的章数不该拿来比大小")
        assertEquals("追更", synced.entity.groupName)
    }

    // ---------- 同一个源 ----------

    @Test
    fun `同一个源 —— 进度按 readAt 章数取大`() {
        val existing = local(readChapter = 10, readAt = 100, totalChapters = 50)
        val synced = applySyncedBook(existing, remote(readChapter = 20, readAt = 200, totalChapters = 60))
        assertEquals(20, synced.entity.readChapterIndex)
        assertEquals(60, synced.entity.totalChapters)
        assertFalse(synced.sourceChanged)
    }

    /** 本机在拍快照之后又改过：远端没有更新的东西，整行原样（调用方据此跳过写库） */
    @Test
    fun `本机更新 —— 一个字段都不动`() {
        val existing = local(readChapter = 30, readAt = 300, updatedAt = 300, totalChapters = 60)
        val synced = applySyncedBook(existing, remote(readChapter = 20, readAt = 200, updatedAt = 200, totalChapters = 60))
        assertEquals(existing, synced.entity)
    }

    @Test
    fun `远端删得更晚 —— 打墓碑并清目录`() {
        val existing = local(updatedAt = 100)
        val synced = applySyncedBook(existing, remote(updatedAt = 200, deleted = true))
        assertTrue(synced.entity.deleted)
        assertFalse(synced.entity.inShelf)
        assertTrue(synced.deletedRemotely)
    }
}
