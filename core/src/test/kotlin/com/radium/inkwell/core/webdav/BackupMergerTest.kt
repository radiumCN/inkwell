package com.radium.inkwell.core.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupMergerTest {

    private fun book(
        id: String,
        readChapter: Int = 0,
        readAt: Long = 0,
        title: String = "书$id",
        updatedAt: Long = 0,
    ) = BackupBook(
        id = id, type = 0, title = title,
        readChapterIndex = readChapter, readAt = readAt, updatedAt = updatedAt,
    )

    private fun payload(vararg books: BackupBook, sources: List<BackupSource> = emptyList()) =
        BackupPayload(deviceId = "test", exportedAt = 0, books = books.toList(), sources = sources)

    @Test
    fun `newer progress wins regardless of side`() {
        val local = payload(book("a", readChapter = 10, readAt = 200))
        val remote = payload(book("a", readChapter = 5, readAt = 100))
        val result = BackupMerger.merge(local, remote)
        assertEquals(10, result.books.single().readChapterIndex)
        assertTrue(result.changedBooks.isEmpty()) // 本地已是最新，无需写回

        val result2 = BackupMerger.merge(remote, local)
        assertEquals(10, result2.books.single().readChapterIndex)
        assertEquals(1, result2.changedBooks.size) // 远端更新，需要写回本地
    }

    @Test
    fun `metadata and progress merge independently`() {
        // 本地进度新、远端元数据新 → 各取所长
        val local = payload(book("a", readChapter = 20, readAt = 300, title = "旧名", updatedAt = 100))
        val remote = payload(book("a", readChapter = 3, readAt = 100, title = "新名", updatedAt = 500))
        val merged = BackupMerger.merge(local, remote).books.single()
        assertEquals("新名", merged.title)
        assertEquals(20, merged.readChapterIndex)
        assertEquals(300, merged.readAt)
    }

    /**
     * 「本地无 + 远端有」仍然是**真·新增**，必须恢复 —— 本地压根没见过这条。
     * 这跟「本地删过」是两回事：删过的东西本地是**有行的**（带 deleted 标记），走不到这一支。
     */
    @Test
    fun `union of both sides - remote-only is a real addition`() {
        val local = payload(book("a"), book("b"))
        val remote = payload(book("b"), book("c"))
        val result = BackupMerger.merge(local, remote)
        assertEquals(setOf("a", "b", "c"), result.books.map { it.id }.toSet())
        assertEquals(listOf("c"), result.changedBooks.map { it.id })
    }

    // ---------- 删除墓碑 ----------

    /**
     * 从前删除在多设备间根本留不住：合并是并集，本地删掉的远端还在，下次同步就被当成
     * 「别的设备新加的」补回来，用户删一次它回来一次。删除必须能赢过远端的旧副本。
     */
    @Test
    fun `本地删除胜过远端的旧副本`() {
        val local = payload(book("a", updatedAt = 200).copy(deleted = true))
        val remote = payload(book("a", updatedAt = 100))
        val merged = BackupMerger.merge(local, remote).books.single()
        assertTrue(merged.deleted, "本地删得更晚，应保持删除")
    }

    /** 反过来也要成立：另一台设备删的，同步过来本地也得删掉 */
    @Test
    fun `远端删除会同步到本地`() {
        val local = payload(book("a", updatedAt = 100))
        val remote = payload(book("a", updatedAt = 200).copy(deleted = true))
        val result = BackupMerger.merge(local, remote)
        assertTrue(result.books.single().deleted)
        assertEquals(1, result.changedBooks.size, "远端的删除要写回本地")
    }

    /**
     * 删完又重新导入（updatedAt 更新）必须能复活 —— 否则用户删过一次的书源就再也加不回来了。
     * 这正是「删除只是一次普通的 updatedAt 更新」这个设计的好处：复活自然成立，不用写特例。
     */
    @Test
    fun `删除后重新添加能复活`() {
        val local = payload(book("a", updatedAt = 300)) // 重新导入，更新
        val remote = payload(book("a", updatedAt = 200).copy(deleted = true))
        assertTrue(!BackupMerger.merge(local, remote).books.single().deleted)
    }

    @Test
    fun `书源的删除同样按 updatedAt 裁决`() {
        val alive = BackupSource("s1", "源1", json = "{}", updatedAt = 100)
        val tomb = BackupSource("s1", "源1", json = "{}", updatedAt = 200, deleted = true)
        assertTrue(BackupMerger.merge(payload(sources = listOf(tomb)), payload(sources = listOf(alive)))
            .sources.single().deleted)
        assertTrue(BackupMerger.merge(payload(sources = listOf(alive)), payload(sources = listOf(tomb)))
            .sources.single().deleted)
    }

    /** 老备份没有 deleted 键 → 默认 false，读起来还是活的 */
    @Test
    fun `老备份没有 deleted 键时默认不删`() {
        val old = BackupCodec.decode(
            BackupCodec.encode(payload(book("a"))),
        )
        assertTrue(!old.books.single().deleted)
    }

    @Test
    fun `sources merge by updatedAt`() {
        val s1old = BackupSource("s1", "源1", enabled = true, json = "{}", updatedAt = 1)
        val s1new = BackupSource("s1", "源1改", enabled = false, json = "{\"v\":2}", updatedAt = 9)
        val result = BackupMerger.merge(
            payload(sources = listOf(s1old)),
            payload(sources = listOf(s1new)),
        )
        assertEquals("源1改", result.sources.single().name)
        assertEquals(1, result.changedSources.size)
    }

    @Test
    fun `codec roundtrip with gzip`() {
        val p = payload(book("a", readChapter = 7, readAt = 42))
        val decoded = BackupCodec.decode(BackupCodec.encode(p))
        assertEquals(p, decoded)
    }
}
